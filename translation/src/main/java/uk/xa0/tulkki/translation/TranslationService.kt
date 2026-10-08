package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.translation.UsageOrigin
import java.time.ZoneId

/**
 * Turns a queue of received messages into translations.
 *
 * One pass of this is deliberately boring and event-driven: take what is due, decide locally, look
 * the answer up in the cache, and only if all three say "buy it" make a request. It never waits: when
 * there is nothing due it returns, and the caller schedules the next pass for the moment the queue
 * says it is worth waking up for. A message that fails retryably moves its own due time into the
 * future instead of being hammered.
 *
 * The request is bought for a **batch**: the items that are due at that moment and share a target
 * language, up to [MAX_BATCH], go out under one request and one instruction. The instruction is the
 * dominant fixed cost - the shipped translate prompt is roughly 80 tokens against maybe 20 for a
 * chat message - so a chatty room or a restart draining the queue pays it once per batch instead of
 * once per message. A batch pays the app's own envelope clause on top
 * ([DeepSeekClient.BATCH_CLAUSE]), which is why the saving grows with the burst rather than arriving
 * at two messages; see [MAX_BATCH]. Nothing waits for a batch to fill: a single due item is a batch
 * of one and is bought exactly as it always was.
 *
 * Order matters in one place: the answer is written to the cache *before* the message and the queue
 * row are updated, so a crash between the two never buys the same text twice.
 *
 * The daily cap is checked immediately before each request. Free work - the offline language decision
 * and a cache hit - still happens at the cap, because it costs nothing; the first message that would
 * need a request stops the pass and stays queued for the next day.
 *
 * One bad item never poisons a batch: an item the answer does not mention fails alone while the
 * others are applied, an id nobody sent is ignored, a duplicated id takes the first answer, and each
 * item still goes through [LanguageCheck] on its own. A failure of the whole request - transport, a
 * non-JSON answer, a refusal - falls back to exactly the single-message failure path for every item
 * in the batch, and the queue's own retry is what tries again: nothing is re-sent one at a time here.
 *
 * Pure Kotlin against small interfaces, so the whole thing is exercised by JVM unit tests.
 */
class TranslationService(
        private val queue: TranslationQueue,
        private val cache: TranslationCache,
        private val settings: TranslationSettings,
        private val activity: TranslationActivityPort,
        private val clients: ClientFactory,
        private val writer: Writer,
        private val zone: ZoneId,
        private val log: Log,
) {

    /**
     * The DeepSeek calls, as this class needs them.
     *
     * Two methods rather than one because a batch of one must stay the request it always was:
     * [pump] calls [translate] when only one message is due, so an ordinary message gains neither an
     * envelope nor a batch clause.
     */
    interface Client {
        @Throws(DeepSeekClient.TranslationException::class)
        fun translate(text: String?, targetLanguage: String?): DeepSeekClient.Result

        /**
         * The same translation, asked again with the app's own clause when [reAsk] is true
         * ([DeepSeekClient.RE_ASK_CLAUSE]). The owner's template stays a byte-for-byte prefix either
         * way; only the app-owned half changes.
         */
        @Throws(DeepSeekClient.TranslationException::class)
        fun translate(text: String?, targetLanguage: String?, reAsk: Boolean): DeepSeekClient.Result

        /**
         * The same translation for several messages that share a target language, in one request.
         * Every item carries the id the answer is asked to echo; the answer need not mention every
         * id, and the caller decides what a missing one means.
         */
        @Throws(DeepSeekClient.TranslationException::class)
        fun translateBatch(
                targetLanguage: String?,
                items: List<DeepSeekClient.BatchRequest>
        ): DeepSeekClient.BatchResult

        companion object {
            /** The interface over the one client that talks to the API. */
            @JvmStatic
            fun over(deepSeek: DeepSeekClient): Client =
                    object : Client {
                        override fun translate(
                                text: String?,
                                targetLanguage: String?
                        ): DeepSeekClient.Result = deepSeek.translate(text, targetLanguage)

                        override fun translate(
                                text: String?,
                                targetLanguage: String?,
                                reAsk: Boolean
                        ): DeepSeekClient.Result = deepSeek.translate(text, targetLanguage, reAsk)

                        override fun translateBatch(
                                targetLanguage: String?,
                                items: List<DeepSeekClient.BatchRequest>
                        ): DeepSeekClient.BatchResult =
                                deepSeek.translateBatch(targetLanguage, items)
                    }
        }
    }

    /** Builds the client for the key that is configured right now. */
    fun interface ClientFactory {
        fun create(apiKey: String): Client
    }

    /**
     * Debug logging, as this class needs it. The line worth having is the one that records when the
     * offline detector and the model name different languages, because the choice made there decides
     * what language the owner's next message goes out in.
     */
    fun interface Log {
        fun debug(message: String)
    }

    /** Where a finished translation goes. */
    interface Writer {
        /**
         * Stores the outcome on the message itself. [translatedBody] is `null` when the message
         * needed no translation, so a message never renders as empty.
         */
        fun writeTranslation(
                conversationUuid: String?,
                messageUuid: String?,
                translatedBody: String?,
                language: String?,
                state: Int
        )

        /**
         * Remembers the language this conversation is written in. What is passed here is decided
         * offline from the message's own text - see [ConversationLanguage.read] - and is only ever
         * something other than the app language.
         */
        fun recordConversationLanguage(conversationUuid: String?, detectedLanguage: String?)

        /**
         * The conversation's detected language as it is stored right now, or `null` when nothing is
         * known. Read so a weak reading cannot silently replace what is already there.
         */
        fun conversationLanguage(conversationUuid: String?): String?
    }

    /** What one pass did, and whether it stopped early. */
    class Outcome {
        @JvmField var translated = 0
        @JvmField var fromCache = 0
        @JvmField var sameLanguage = 0
        @JvmField var skipped = 0
        @JvmField var failed = 0

        /** True when the pass stopped because the day's tokens are spent. Nothing was dropped. */
        @JvmField var capReached = false

        /** True when no key is configured, so there was nothing to call. Nothing was dropped. */
        @JvmField var noApiKey = false
        @JvmField var remainingTokens = 0
        @JvmField var pending = 0

        override fun toString(): String =
                "translated=" +
                        translated +
                        " cached=" +
                        fromCache +
                        " same=" +
                        sameLanguage +
                        " skipped=" +
                        skipped +
                        " failed=" +
                        failed +
                        " capReached=" +
                        capReached +
                        " noKey=" +
                        noApiKey +
                        " pending=" +
                        pending +
                        " tokensLeft=" +
                        remainingTokens
    }

    /**
     * Queues a live received message. The target language is the app language as it is right now; the
     * item keeps it, so changing the setting later does not rewrite work already queued. This is the
     * automatic path and its insert is `CONFLICT_IGNORE`: an existing row is left alone, failed or
     * waiting. The owner's explicit re-request ([enqueueExplicit]) is the one thing that repoints a
     * row, because that is a decision made now rather than work already queued.
     */
    fun enqueue(messageUuid: String?, conversationUuid: String?, body: String?, now: Long) {
        queue.enqueue(messageUuid, conversationUuid, body, settings.appLanguage(), false, now)
    }

    /**
     * Queues one message the owner asked for by tapping it.
     *
     * Same queue, same cache, same daily cap, same token counter as the automatic pass - the only
     * thing that is different is who asked. The automatic gate refuses history in bulk and keeps
     * doing so; one message a person pointed at is not a backfill. A message that had already given
     * up, or is waiting out a backoff, is made due again: asking for it is a new decision.
     *
     * And it is a decision made *now*, in the language in force now. The insert comes first so a
     * message that was never queued gets a row, and it stays `CONFLICT_IGNORE` so a row that is
     * already there is not disturbed by it; the revive that follows then repoints that row at
     * [TranslationSettings.appLanguage]. Without that repointing the row's captured target wins - it
     * was written when the message was first queued and may name a language the owner has since
     * stopped studying - and the tap would answer in it. A row that is done is touched by neither
     * half: a translation that already succeeded is not bought again.
     */
    fun enqueueExplicit(
            messageUuid: String?,
            conversationUuid: String?,
            body: String?,
            now: Long
    ) {
        enqueueExplicit(messageUuid, conversationUuid, body, false, now)
    }

    /**
     * The same tap, told whether it is a *re-ask*: the last attempt was refused by the local check,
     * so repeating it would reproduce the refusal and the request must ask differently (docs/MIGRATION.md
     * item 17, six).
     *
     * The flag reaches the row through its cache key and nowhere else
     * ([TranslationQueue.enqueue]), because a separate durable flag would be a column in `:data`. The
     * buy reads it back out of that key with [PromptBook.isReAsk].
     */
    fun enqueueExplicit(
            messageUuid: String?,
            conversationUuid: String?,
            body: String?,
            reAsk: Boolean,
            now: Long
    ) {
        val requested =
                queue.enqueue(messageUuid, conversationUuid, body, settings.appLanguage(), reAsk, now)
        queue.makeDue(requested, now)
    }

    /**
     * Handles everything that is due now. Safe to call from anywhere and as often as you like: it
     * does nothing when the queue is empty.
     *
     * Synchronized on the service, and the service is one per process: three differently named jobs
     * can be in flight at once (the immediate chain, the scheduled wake-up and the daily safety net),
     * and two passes that both read the same due item would both buy it - the cache write only
     * happens after the answer comes back, so it cannot stop the second request. The second pass
     * simply waits and then finds the queue already handled.
     *
     * **With the interpreter off this does nothing at all**, and that empty outcome is the method's
     * first statement: no key read, no counter read, no cap read, no client factory call, no
     * `queue.due`, no `writeTranslation`, no [Spend.record] - so no `translation_usage` row, no tokens
     * against the day and no failure remembered - and no `addTranslated`. A pass that runs while off
     * leaves the queued rows where they are, but they do not stay there: the carry-over is dropped
     * when the flip is observed, by [TranslationSettings.clearInterpreterState], because a pending row
     * holds the target language captured while the interpreter was on and this pass classifies against
     * that captured value. Turning the interpreter back on starts from an empty queue rather than from
     * a stale one.
     */
    @Synchronized
    fun pump(now: Long): Outcome {
        val outcome = Outcome()
        // Off, before the key, the counter and the cap are read: a plain client's pass does nothing at
        // all. This is where "no request to the API" and "no ledger row" are actually held for the
        // queue, and it is the pass's own statement rather than its callers': two of the three are
        // behind guards of their own, but the third is a WorkManager job, and a record left over from
        // the interpreter being on is cancelled the moment the flip is observed (TranslationWork.cancel,
        // through UiHost.cancelTranslation), so off there is no record left to fire. Off,
        // nothing is bought, nothing is written, nothing is counted against the cap or the day and no
        // failure is remembered. The rows this leaves alone are not forgotten either: the transition to
        // off clears the pending ones (TranslationSettings.clearInterpreterState), which is the moment
        // that owns them - a pass owns no such moment and must not delete work it may still be asked to
        // do if the interpreter is switched straight back on.
        if (!settings.interpreter().enabled()) {
            return outcome
        }
        val day = DailyTokenCounter.dayOf(now, zone)
        val cap = settings.dailyTokenCap()
        val counter = settings.tokenCounter()
        val apiKey = settings.apiKey()

        // Item 17's self-retry, decided where the cause is observable rather than by a new hook per
        // event. A pass is what every trigger ends in - the key write kicks one, the cap write is picked
        // up by the next one, a successful call happens inside this one - so the causes that have
        // cleared are read off the world here: a key is present (no key is over), it is a different key
        // from the last pass (the rejected one is over), the cap has headroom (the cap is over), and a
        // call that succeeded proves credit is present. `check_refused` is in none of them: the same
        // request reproduces the same refusal (docs/MIGRATION.md item 17, two and six).
        if (apiKey.isEmpty()) {
            // Nothing can be sent yet. Leave every queued message exactly where it is: entering a key is
            // what makes them fail or succeed, so failing them now would be a lie. The one thing written
            // is the cause, so the row says *why* it is waiting - and so the key arriving is the event
            // that clears it.
            outcome.noApiKey = true
            tagStopped(now, FailureCause.NO_KEY)
            outcome.pending = queue.pendingCount()
            outcome.remainingTokens =
                    DailyTokenCounter.remaining(
                            counter.used(day), cap, DailyTokenCounter.Purpose.RECEIVED)
            return outcome
        }

        val cleared = HashSet<FailureCause>()
        cleared.add(FailureCause.NO_KEY)
        // A rejected key is cleared by a key being *present*, not by observing a change. A change
        // cannot be observed across a restart: any in-memory generation is re-seeded from the settings
        // when this service is built, so a service first built after the key was written would wait for
        // ever - and that is the state that has to be reachable after one. A still-bad key answers 401
        // again, which costs a request and no tokens, and the row is marked again.
        cleared.add(FailureCause.REJECTED_KEY)
        if (!DailyTokenCounter.exhausted(
                        counter.used(day), cap, DailyTokenCounter.Purpose.RECEIVED)) {
            cleared.add(FailureCause.CAP_REACHED)
        }
        reviveStopped(cleared, now)

        // Built on first use, not up front: a pass that finds only free work must not open an HTTP
        // client for nothing.
        var client: Client? = null
        var capReached = false
        var creditCleared = false
        var attempted = false
        while (true) {
            // What this request will carry: the items due *at this moment* that share a target language,
            // up to the bound. The snapshot is what makes that one moment real - a pass never re-asks the
            // queue while it collects, so it cannot meet the same item twice - and nothing here waits for
            // another message to arrive: an ordinary message is due alone and is bought alone.
            val due = queue.due(now, MAX_BATCH)
            if (due.isEmpty()) {
                if (!attempted && probeEmptyAccount(now, counter.used(day), cap)) {
                    // A pass whose only work is rows the empty account stopped can still learn that the
                    // account is funded again, because the observation needs a request and there was
                    // nothing else due to make one. One such row is revived and the next turn round this
                    // loop buys it. `attempted` is what makes it once: a pass that has already asked has
                    // its answer, so a still-empty account is not probed again in the same pass - the
                    // next pass is where a fresh observation is worth buying.
                    continue
                }
                break
            }
            val batch = ArrayList<Purchase>(due.size)
            var batchLanguage: String? = null
            var capReachedHere = false

            for (item in due) {
                // Free: a message that is confidently already in the app language never leaves the
                // phone. The name is not asked for here: the queue row carries a conversation uuid and no
                // name, and a body that is the conversation's own name never reaches the queue - the
                // receive path's candidate, which does carry it, refuses it at the door.
                val verdict =
                        TranslationDecision.classify(
                                item.body, item.targetLanguage, null, settings.interpreter())
                if (verdict == TranslationDecision.Verdict.SAME_LANGUAGE) {
                    writer.writeTranslation(
                            item.conversationUuid,
                            item.messageUuid,
                            null,
                            null,
                            Message.TRANSLATION_SAME_LANGUAGE)
                    queue.succeeded(item, now)
                    outcome.sameLanguage++
                    continue
                }
                if (verdict == TranslationDecision.Verdict.NO_LANGUAGE) {
                    // A link, a code, emoji: nothing to translate. Mark the item handled so it is not
                    // reconsidered, and leave the message as it is.
                    queue.succeeded(item, now)
                    outcome.skipped++
                    continue
                }

                // Also free: the same text into the same language was already bought once. The identity
                // is worked out here rather than read off the queued row, because the row was written
                // when the message arrived and the owner may have rewritten the instruction since: the
                // key has to name the prompt that is about to be sent, not the one that was in force
                // back then.
                //
                // Which of the two questions this row is comes out of the stored key, because the
                // re-ask's clause is folded into the identity rather than carried as a durable flag (a
                // flag would be a column in `:data`). The refusal path writes that key; this reads it
                // back and asks under the wording in force now.
                val reAsk = PromptBook.isReAsk(item.cacheKey, item.body, item.targetLanguage)
                val cacheKey = PromptBook.translationKey(item.body, item.targetLanguage, reAsk)
                val cached = cache.get(cacheKey)
                if (cached != null) {
                    if (apply(item, cached.detectedLanguage, cached.translatedBody, now)) {
                        outcome.fromCache++
                    } else {
                        outcome.failed++
                    }
                    continue
                }

                if (reAsk && batch.isNotEmpty()) {
                    // A re-ask carries its own instruction, so it is never folded into a request built
                    // for the owner's template alone. It stays due, and the next turn round this loop
                    // takes it by itself.
                    break
                }

                if (batchLanguage == null) {
                    batchLanguage = item.targetLanguage
                } else if (batchLanguage != item.targetLanguage) {
                    // A different target language belongs to the next request; this batch must not wait
                    // for it, and the pass will come back to it straight away.
                    break
                }

                val used = counter.used(day)
                if (capReached
                        || DailyTokenCounter.exhausted(
                                used, cap, DailyTokenCounter.Purpose.RECEIVED)) {
                    // This pass is received translation, so it may spend the cap's last tenth: that
                    // reserve exists so a spent cap cannot leave the owner unable to read what was said
                    // to them (docs/MIGRATION.md item 17, three). It still stops at the number. Stop calling
                    // the API, and stop here rather than dropping anything: whatever is left stays
                    // queued, and the caller wakes the pass up again when the cap resets.
                    capReached = true
                    capReachedHere = true
                    // Item 17's cause for what this pass is leaving: the cap is why these rows are still
                    // here, and the pass that next finds headroom clears it. Tagged here, not only at the
                    // loop's end, because this break is what skips the batch.
                    tagStopped(now, FailureCause.CAP_REACHED)
                    break
                }

                if (containsCacheKey(batch, cacheKey)) {
                    // The same text twice in this snapshot. It is sent once, and this second message is
                    // served from the cache the request leaves behind on its next turn round the loop -
                    // so this batch stops here rather than asking the model to translate the same words
                    // twice.
                    break
                }

                // The id is this purchase's place in the request, 1..n: the shortest thing the model can
                // echo without a typo, and all the answer needs to name one message.
                batch.add(Purchase(item, cacheKey, batch.size + 1, reAsk))
                if (reAsk) {
                    // Alone: only the single-message path sends [DeepSeekClient.RE_ASK_CLAUSE].
                    break
                }
            }

            if (batch.isEmpty()) {
                // Everything in the snapshot was free work, and that work is done - so ask the queue
                // again for the next snapshot. At the cap there is nothing more to do at all.
                if (capReachedHere) {
                    break
                }
                continue
            }

            if (batch.size == 1) {
                // A batch of one stays exactly the request it always was: no envelope to explain, so no
                // batch clause and no extra instruction on an ordinary message.
                val purchase = batch[0]
                try {
                    val active = client ?: clients.create(apiKey).also { client = it }
                    attempted = true
                    // One line when the request goes out and one when it comes back: the pair is the
                    // measurement ("translations are slower" needs a number, not an argument), and a
                    // start with no end is a request that never returned. The item count and the batch
                    // flag are here because this is the only place that knows them.
                    val startedAt = System.currentTimeMillis()
                    log.debug("translation request started: 1 item, single")
                    val result =
                            active.translate(
                                    purchase.item.body,
                                    purchase.item.targetLanguage,
                                    purchase.reAsk)
                    log.debug(
                            "translation request finished in " +
                                    (System.currentTimeMillis() - startedAt) +
                                    " ms: 1 item, single")
                    // The response's own total to the cap and its split to the ledger, filed together.
                    // `now` is the pass's instant and, in production, the moment the request was built -
                    // not the moment the answer arrived, which would let a slow call near a window edge
                    // be priced by when it finished.
                    //
                    // The ledger's grouping key is the message's conversation, which is the uuid and
                    // nothing else; the name is resolved from `conversations` when the ledger is read.
                    Spend.record(settings, result.usage, now, zone, purchase.item.conversationUuid)
                    applyBought(
                            purchase,
                            result.detectedLanguage,
                            result.text,
                            result.totalTokens,
                            outcome,
                            now)
                } catch (e: DeepSeekClient.TranslationException) {
                    failWith(purchase, e.retryable, e.message, outcome, activity, now)
                } catch (e: RuntimeException) {
                    // A message that breaks our own handling must not wedge the queue, and must not be
                    // bought again on the retry - which is safe, because the cache write comes first.
                    failWith(purchase, true, "unexpected: " + e, outcome, activity, now)
                }
            } else {
                val requests = ArrayList<DeepSeekClient.BatchRequest>(batch.size)
                for (purchase in batch) {
                    requests.add(DeepSeekClient.BatchRequest(purchase.id, purchase.item.body))
                }
                try {
                    val active = client ?: clients.create(apiKey).also { client = it }
                    attempted = true
                    val startedAt = System.currentTimeMillis()
                    log.debug("translation request started: " + batch.size + " items, batch")
                    val result = active.translateBatch(batchLanguage, requests)
                    log.debug(
                            "translation request finished in " +
                                    (System.currentTimeMillis() - startedAt) +
                                    " ms: " +
                                    batch.size +
                                    " items, batch")
                    // The request's own total, charged once: the day pays for what the account was
                    // actually billed, not for a guess at each message's share.
                    //
                    // And it is filed under the reserved several-rooms marker, not under no origin: a
                    // batch is up to eight messages that share a target language and may come from eight
                    // conversations, so splitting its one figure across them would be an invented
                    // attribution - but it is room traffic, and folding it into the not-tied bucket would
                    // show the owner small per-room figures beside one large "not tied to a room" number
                    // that is in fact mostly rooms.
                    Spend.record(settings, result.usage, now, zone, UsageOrigin.MULTI_ROOM_ORIGIN)
                    applyBatch(batch, result, outcome, activity, now)
                } catch (e: DeepSeekClient.TranslationException) {
                    // One transport or protocol failure, one decision for the whole batch. Every item
                    // takes exactly the failure path a single message would have taken - the existing
                    // backoff, the existing reason - and the queue's own retry is what tries again.
                    // Nothing is re-sent one at a time here: that would spend a second request on an API
                    // that has just refused the first.
                    for (purchase in batch) {
                        failWith(purchase, e.retryable, e.message, outcome, activity, now)
                    }
                } catch (e: RuntimeException) {
                    val error = "unexpected: " + e
                    for (purchase in batch) {
                        failWith(purchase, true, error, outcome, activity, now)
                    }
                }
            }

            if (!creditCleared && outcome.translated > 0) {
                // A request that came back with a translation is the proof the account has credit - the
                // trigger is inferred from an observed success, never polled, because nothing in the app
                // asks DeepSeek for a balance (docs/MIGRATION.md item 17, two). The revived rows are pending
                // and due, so the loop's next snapshot buys them in this same pass.
                creditCleared = true
                val credit = HashSet<FailureCause>()
                credit.add(FailureCause.NO_CREDIT)
                reviveStopped(credit, now)
            }

            if (capReachedHere) {
                // Item 17's cause for what this pass leaves behind: the cap is why these rows are still
                // here, and the pass that next finds headroom - a raised cap, or the day rolled over - is
                // what clears it.
                tagStopped(now, FailureCause.CAP_REACHED)
                break
            }
        }

        // What the usage screen's "translated today" means: messages that came out of today's work,
        // whether they were bought or already in the cache. "Already in the app language" and "no
        // language at all" are not translations, so they are not counted.
        activity.addTranslated(day, outcome.translated + outcome.fromCache)

        outcome.capReached = capReached
        outcome.pending = queue.pendingCount()
        outcome.remainingTokens =
                DailyTokenCounter.remaining(
                        counter.used(day), cap, DailyTokenCounter.Purpose.RECEIVED)
        return outcome
    }

    /**
     * Tulkki: item 17's self-retry. Every row a cause stopped, whose cause is one of [cleared], is
     * revived in place - the same target language and the same cache identity, so a row is answered in
     * the language it was queued for and a re-ask's clause survives - and the cause goes with the rest
     * of the retry state ([TranslationQueue.makeDue]).
     *
     * Both halves are read: a cause can stop a row the queue has given up on (a rejected key, an empty
     * account, the check's refusal) and one it is merely waiting with (no key, the cap), which is
     * exactly why the column lives on the row rather than in a pass.
     *
     * The refusal is never in [cleared]: [FailureCause.clearable] is false for it, so no caller can
     * revive it by adding it to a set - repeating the request reproduces the refusal (docs/MIGRATION.md
     * item 17, two).
     */
    private fun reviveStopped(cleared: Set<FailureCause>, now: Long) {
        var revived = 0
        // Only these two can stop a row the queue has given up on; no key and the cap stop a row the
        // queue is still waiting with, which the pending read below reaches. Reading the failed rows for
        // the other two would walk every failure the phone has ever listed on every pass.
        if (cleared.contains(FailureCause.REJECTED_KEY) ||
                cleared.contains(FailureCause.NO_CREDIT)) {
            for (item in queue.failedDue(now, RETRY_SCAN)) {
                if (clearedCause(item, cleared)) {
                    queue.makeDue(item, now)
                    revived++
                }
            }
        }
        for (item in queue.due(now, RETRY_SCAN)) {
            if (clearedCause(item, cleared)) {
                queue.makeDue(item, now)
                revived++
            }
        }
        if (revived > 0) {
            log.debug(
                    "revived " +
                            revived +
                            " received " +
                            (if (revived == 1) "row" else "rows") +
                            " whose blocking cause cleared")
        }
    }

    /** Whether this row is stopped by one of the causes this trigger cleared - and never the refusal. */
    private fun clearedCause(item: TranslationQueue.Item, cleared: Set<FailureCause>): Boolean {
        val cause = item.failureCause
        return cause != null && cause.clearable() && cleared.contains(cause)
    }

    /**
     * Tulkki: the one row the empty-account trigger may try when nothing else is due, or `false` when
     * there is nothing to try.
     *
     * The trigger's observation is "a request came back", so a queue whose only rows are
     * [FailureCause.NO_CREDIT] could never make one: nothing is due, so nothing is asked, so no success
     * ever proves the account is funded again, and the state waits for a new arrival or a tap that may
     * never come. That is the hole this closes - the row itself is the request. It costs one call per
     * pass while the account is empty (a 402 bills no tokens), and the caller spends the probe once so
     * a still-empty account is probed once, not in a loop.
     *
     * The cap is respected first: a probe is a purchase like any other, and an exhausted day is not the
     * moment to spend one.
     */
    private fun probeEmptyAccount(now: Long, used: Int, cap: Int): Boolean {
        if (DailyTokenCounter.exhausted(used, cap, DailyTokenCounter.Purpose.RECEIVED)) {
            return false
        }
        for (item in queue.failedDue(now, RETRY_SCAN)) {
            if (item.failureCause === FailureCause.NO_CREDIT) {
                queue.makeDue(item, now)
                return true
            }
        }
        return false
    }

    /** Tulkki: stamp the rows this pass is leaving behind with the cause that stopped the pass. */
    private fun tagStopped(now: Long, cause: FailureCause) {
        for (item in queue.due(now, MAX_BATCH)) {
            queue.stopped(item, cause)
        }
    }

    /** One message in the batch, with the cache identity the buy is made under and its request id. */
    private class Purchase(
            val item: TranslationQueue.Item,
            val cacheKey: String?,
            val id: Int,
            /**
             * Whether this is the owner's re-ask of a message the check refused. A re-ask is always
             * alone in its batch, because only the single-message path sends
             * [DeepSeekClient.RE_ASK_CLAUSE].
             */
            val reAsk: Boolean,
    )

    /** Whether a batch already holds this text into this language. At most [MAX_BATCH] entries. */
    private fun containsCacheKey(batch: List<Purchase>, cacheKey: String?): Boolean {
        for (purchase in batch) {
            if (purchase.cacheKey == cacheKey) {
                return true
            }
        }
        return false
    }

    /**
     * Stores one bought answer on its message, and in the cache when it passed the check. Shared by
     * the batch-of-one and the batch, because by the time either gets here it holds exactly one answer
     * for this message.
     *
     * [totalTokens] is the request's own total, which for a batch is the whole request rather than this
     * message's share: the account was billed that, and inventing a division would be a number nobody
     * can check. The counter is charged once, by the caller.
     */
    private fun applyBought(
            purchase: Purchase,
            detectedLanguage: String?,
            translatedBody: String?,
            totalTokens: Int,
            outcome: Outcome,
            now: Long
    ) {
        if (apply(purchase.item, detectedLanguage, translatedBody, now)) {
            // The answer is paid for and usable, so it must not be lost - and an answer that failed the
            // check above is deliberately not kept, because the owner's tap on the covered message would
            // only be handed the same unusable answer back.
            cache.store(purchase.cacheKey, detectedLanguage, translatedBody, totalTokens, now)
            outcome.translated++
        } else {
            outcome.failed++
        }
    }

    /**
     * Applies a batch answer item by item, and this is the half that keeps one bad item from poisoning
     * the batch.
     *
     * An answer that mentions an id twice keeps the first and ignores the rest; an id the request never
     * sent is never looked up; an id the answer does not mention is a failure of *that item alone*,
     * while every other item still gets the answer the owner paid for. The per-item check itself is
     * [apply], so [LanguageCheck] still judges each message's own answer against its own target language
     * - one wrong-language answer covers one message, not the batch.
     */
    private fun applyBatch(
            batch: List<Purchase>,
            result: DeepSeekClient.BatchResult,
            outcome: Outcome,
            activity: TranslationActivityPort,
            now: Long
    ) {
        val answers = HashMap<Int, DeepSeekClient.BatchItem>()
        for (answer in result.items) {
            answers.putIfAbsent(answer.id, answer)
        }
        for (purchase in batch) {
            val answer = answers[purchase.id]
            if (answer == null) {
                failMissing(purchase, outcome, activity, now)
                continue
            }
            applyBought(
                    purchase,
                    answer.detectedLanguage,
                    answer.text,
                    result.totalTokens,
                    outcome,
                    now)
        }
    }

    /**
     * The answer did not mention this message. That is a failure for this item only - the others in the
     * batch keep their answers - and it is not retryable: the request was answered, so buying it again
     * would ask the same question a second time rather than give the message another chance.
     *
     * The reason names the outcome and never a word of the message or of the answer, which is the rule
     * every stored reason keeps.
     */
    private fun failMissing(
            purchase: Purchase,
            outcome: Outcome,
            activity: TranslationActivityPort,
            now: Long
    ) {
        val reason = "the batch answer did not carry an item for this message"
        queue.failed(purchase.item, false, reason, now)
        activity.recordFailure(HeldSend.HoldReason.FAILED, reason, purchase.item.messageUuid, now)
        markFailedIfFinal(purchase.item)
        outcome.failed++
        log.debug(
                "conversation " +
                        purchase.item.conversationUuid +
                        ": the batch answer omitted the item with id " +
                        purchase.id +
                        "; that message alone is covered")
    }

    /**
     * The failure path a single message has always taken, for one member of a batch. A whole-batch
     * failure runs this for every item so the outcome is exactly today's: the same retryable flag, the
     * same backoff, the same reason on the failures screen.
     *
     * The reason is recorded before the message is written, so the write-back that re-binds the bubble
     * already finds the reason it has to show.
     */
    private fun failWith(
            purchase: Purchase,
            retryable: Boolean,
            error: String?,
            outcome: Outcome,
            activity: TranslationActivityPort,
            now: Long
    ) {
        val reason = HeldSend.failureReason(retryable, error)
        // Item 17's cause, and only where one exists: a rejected key and an empty account are the two
        // failures a later event can fix, and a rejected key is exactly the one the app itself marks
        // non-retryable (HeldSend.failureReason). An ordinary retryable failure answers null, and the
        // queue's own attempts/next_attempt_at axis carries it.
        queue.failed(purchase.item, retryable, error, FailureCause.fromReason(reason), now)
        activity.recordFailure(reason, error, purchase.item.messageUuid, now)
        markFailedIfFinal(purchase.item)
        outcome.failed++
    }

    /**
     * Stores one answer on the message and finishes the queue item, or refuses it. A cached answer is
     * the same evidence as a fresh one, so this path is shared by both.
     *
     * What the message was already in is asked of the offline detector on the original text, not of the
     * answer: [ConversationLanguage.read] is the one place that decides, and the model's `lang` only
     * stands in when the detector has no opinion. That matters because the answer to this question is
     * also "may the original be shown as it is": a model that calls a German message Finnish must not
     * put the German on the screen untranslated.
     *
     * An answer that is not a translation at all is refused here rather than drawn: [LanguageCheck] is
     * asked, locally and for nothing, whether what came back is in the language that was asked for, and
     * an empty answer, the input echoed back or a language nobody asked for leaves the message covered
     * with the reason named - the same outcome a translation that could not happen already takes. The
     * answer is not cached in that case, so the owner's tap on the covered message buys a fresh one
     * instead of being handed the same refusal back.
     *
     * @return true when the answer became the message, false when it was refused
     */
    private fun apply(
            item: TranslationQueue.Item,
            detectedLanguage: String?,
            translatedBody: String?,
            now: Long
    ): Boolean {
        val reading =
                ConversationLanguage.read(
                        item.body,
                        detectedLanguage,
                        item.targetLanguage,
                        writer.conversationLanguage(item.conversationUuid),
                        // Same reason as the queue's own classify: a row carries no name, and a body that
                        // is the conversation's own name is refused before it is ever queued.
                        null)

        if (reading.wasAlreadyIn(item.targetLanguage)) {
            writer.writeTranslation(
                    item.conversationUuid,
                    item.messageUuid,
                    null,
                    null,
                    Message.TRANSLATION_SAME_LANGUAGE)
        } else {
            // Tulkki: the answer has to be a translation. A model that hands back an empty body, the text
            // it was given, or a language nobody asked for has not translated anything, and drawing that
            // as the message would be exactly the failure the owner reported. The check is local and
            // free; on a failure the message takes the same outcome a translation that could not happen
            // already takes - covered, with the reason named.
            val check =
                    LanguageCheck.of(
                            item.body,
                            translatedBody,
                            item.targetLanguage,
                            settings.appLanguage(),
                            detectedLanguage)
            if (check.failed()) {
                // Item 17's one terminal cause a retry may not touch: the refusal is recorded as its own
                // cause, so the self-retry can exclude it and the owner's tap can find it.
                queue.failed(item, false, check.because, FailureCause.CHECK_REFUSED, now)
                activity.recordFailure(
                        HeldSend.HoldReason.FAILED, check.because, item.messageUuid, now)
                markFailedIfFinal(item)
                logUnusable(item, check)
                return false
            }
            writer.writeTranslation(
                    item.conversationUuid,
                    item.messageUuid,
                    translatedBody,
                    // The row records the language the message was in, and the offline reading is a better
                    // answer than the model's claim whenever there is one.
                    if (reading.messageLanguage == null) detectedLanguage
                    else reading.messageLanguage,
                    Message.TRANSLATION_DONE)
            if (reading.language != null) {
                writer.recordConversationLanguage(item.conversationUuid, reading.language)
            }
        }
        logDisagreement(item, reading)
        queue.succeeded(item, now)
        return true
    }

    /**
     * The permanent debug line for the case where the answer was not a translation. It names the
     * outcome, the language that was read instead and the language that was asked for - and never the
     * message, which is the rule every reason in this app keeps.
     */
    private fun logUnusable(item: TranslationQueue.Item, check: LanguageCheck.Report) {
        log.debug(
                "conversation " +
                        item.conversationUuid +
                        ": the answer for a message into " +
                        item.targetLanguage +
                        " is not a translation (" +
                        check.outcome +
                        (if (check.language == null) "" else ", read as " + check.language) +
                        ")")
    }

    /**
     * The permanent debug line for the case where the offline detector and the model name different
     * languages: one of the two is wrong, and [ConversationLanguage.read] is where the choice between
     * them is made.
     */
    private fun logDisagreement(
            item: TranslationQueue.Item,
            reading: ConversationLanguage.Reading
    ) {
        if (!reading.disagrees()) {
            return
        }
        log.debug(
                "conversation " +
                        item.conversationUuid +
                        ": the offline detector read " +
                        reading.local +
                        " but the model said " +
                        reading.modelLanguage +
                        "; " +
                        (if (reading.language == null) "leaving the conversation's language as it was"
                        else "storing " + reading.language))
    }

    /**
     * When a failure is final, the message says so. A message that is still waiting for its next
     * attempt is left as it was: it has not failed, it is pending, and the two must not look the same in
     * the conversation - an untranslated message is indistinguishable from one that needed no
     * translation otherwise.
     */
    private fun markFailedIfFinal(item: TranslationQueue.Item) {
        if (item.state == TranslationQueue.Item.STATE_FAILED) {
            writer.writeTranslation(
                    item.conversationUuid,
                    item.messageUuid,
                    null,
                    null,
                    Message.TRANSLATION_FAILED)
        }
    }

    /**
     * When the caller should wake the queue up again, in milliseconds, or `-1` when only a new event (a
     * message arriving, the app starting, a key being entered) can move it along.
     */
    fun nextWakeUpMillis(now: Long, outcome: Outcome): Long {
        if (outcome.capReached) {
            // The cap is a calendar day, so the queue resumes when the day does.
            return maxOf(0L, DailyTokenCounter.startOfNextDay(now, zone) - now)
        }
        if (outcome.noApiKey) {
            return -1L
        }
        val due = queue.nextDueAt()
        return if (due == null) -1L else maxOf(0L, due - now)
    }

    fun pendingCount(): Int = queue.pendingCount()

    companion object {
        /**
         * How many messages one request may carry.
         *
         * Eight, and that is a judgement rather than a measurement. The shipped translate instruction is
         * roughly 80 tokens against maybe 20 for a chat message, and a batch pays it once plus the app's
         * own envelope clause once ([DeepSeekClient.BATCH_CLAUSE], a little over 100 tokens): the saving
         * grows with the burst, so a batch of two is roughly a wash while eight turns a burst of ten into
         * two requests instead of ten, and a restart draining a roomful of messages likewise. It is
         * bounded rather than unlimited for two reasons: one answer has to carry every translation, so the
         * response and its output tokens stay a chat message's size times a small number; and a batch can
         * fail as a whole, so a smaller bound keeps one transport failure to a handful of messages sharing
         * one queue decision. Nothing is lost beyond the bound - the pass collects the next eight on its
         * next turn round the loop.
         */
        private const val MAX_BATCH = 8

        /**
         * How many rows one cause-clearing read takes.
         *
         * The whole queue, deliberately: the trigger is rare (a key changed, a call that succeeded), the
         * queue is one phone's own backlog, and a cause-filtered statement does not exist in `:data` - the
         * queue reads by state, not by cause, and `:data` is not mine to add one to. Filtering the one
         * snapshot here is what keeps every row a cause stopped reachable; a bounded read would leave the
         * rows past the bound unreachable until something else moved them.
         */
        private const val RETRY_SCAN = Int.MAX_VALUE
    }
}
