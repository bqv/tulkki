package uk.xa0.tulkki.translation

import android.content.Context
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.translation.MessageLookupStore
import uk.xa0.tulkki.data.translation.MessageTranslationStore
import uk.xa0.tulkki.data.translation.QueueFailureRow
import uk.xa0.tulkki.data.translation.SendFailureRow
import uk.xa0.tulkki.data.translation.TranslationCacheRow
import uk.xa0.tulkki.data.translation.TranslationCacheStore
import uk.xa0.tulkki.data.translation.TranslationFailureStore
import uk.xa0.tulkki.data.translation.TranslationQueueRow
import uk.xa0.tulkki.data.translation.TranslationQueueStore
import uk.xa0.tulkki.data.translation.TranslationUsageStore
import uk.xa0.tulkki.data.translation.UsageCounts
import uk.xa0.tulkki.data.translation.UsageDay

/**
 * The queue, the cache, the usage ledger, the write-back and the reads, on top of the app's own
 * encrypted database.
 *
 * **The SQL has left this class (S5-6), and so has the handle.** Every statement and every
 * `ContentValues` now lives in `:data` - `TranslationQueueQueries`/`TranslationQueueStore`,
 * `TranslationCacheQueries`/`TranslationCacheStore`, `TranslationUsageQueries`/
 * `TranslationUsageStore`, `MessageTranslationQueries`/`MessageTranslationStore`,
 * `TranslationFailureQueries`/`TranslationFailureStore` and `MessageLookupQueries`/
 * `MessageLookupStore` - and this class is the mapping between those rows and `:translation`'s own
 * types, plus the half that needs the host. It holds no writable handle and runs no raw query of its
 * own: nothing in `:translation` opens the database any more, and the pin in
 * `MessageLookupQueriesTest` says so by name.
 *
 * It is deliberately thin otherwise: the state machine is in [TranslationQueue], the arithmetic in
 * [DailyTokenCounter], and this only moves rows. Where it writes back into the loaded interface it
 * asks [EngineHost.orNull]: the row is correct either way, so a process with no host skips the
 * redraw rather than failing.
 *
 * Writing a translation updates exactly the three translation columns of one message, by uuid, so
 * nothing else in the row can be clobbered. When the message is loaded in the interface it is
 * updated in memory too, so the bubble changes without reloading the conversation.
 */
class TranslationStore(appContext: Context) :
        TranslationQueue.Store,
        TranslationCache.Store,
        TranslationService.Writer,
        UsageLedger.Store {

    private val context: Context = appContext.applicationContext ?: appContext

    /**
     * The by-uuid message read's own store (S5-6), the last slice: `TranslationStore` builds no
     * `IN (...)` statement and walks no cursor any more, and `:data`'s `MessageLookupStore` is the
     * one door to the row. This was the last thing in `:translation` that held the writable handle.
     */
    private fun lookup(): MessageLookupStore = MessageLookupStore.get(context)

    /**
     * The queue's own store (S5-6), the ported queue slice: `TranslationStore` composes no queue
     * statement and no queue `ContentValues` any more, and `:data`'s [TranslationQueueStore] is the
     * one door to the table. Every half has now followed it - the cache, the usage ledger, the
     * write-back, the failures read and the by-uuid read - so nothing in `:translation` opens the
     * database at all.
     */
    private fun queue(): TranslationQueueStore = TranslationQueueStore.get(context)

    // -- the queue -------------------------------------------------------------------------------

    override fun insert(item: TranslationQueue.Item) {
        // IGNORE, not REPLACE: a message that is already queued must not lose its attempt count and
        // its due time to a duplicate insert. The rule is `:data`'s now.
        queue().insert(queueRow(item))
    }

    override fun nextDue(now: Long): TranslationQueue.Item? {
        val row = queue().nextDue(now, TranslationQueue.Item.STATE_PENDING)
        return row?.let { queueItem(it) }
    }

    /**
     * The due snapshot a batched pass works from: at most [limit] pending items, oldest first.
     * The order is spelled here and nowhere else, so it cannot drift from [nextDue].
     */
    override fun due(now: Long, limit: Int): List<TranslationQueue.Item> {
        val items = ArrayList<TranslationQueue.Item>()
        for (row in queue().due(now, limit, TranslationQueue.Item.STATE_PENDING)) {
            items.add(queueItem(row))
        }
        return items
    }

    /**
     * Tulkki: the rows the queue has given up on, for item 17's self-retry. The same statement behind
     * [due], with the failed state instead of the pending one: a failed row's due time is the
     * attempt that failed it, so a later instant reads every one of them.
     */
    override fun failedDue(now: Long, limit: Int): List<TranslationQueue.Item> {
        val items = ArrayList<TranslationQueue.Item>()
        for (row in queue().due(now, limit, TranslationQueue.Item.STATE_FAILED)) {
            items.add(queueItem(row))
        }
        return items
    }

    override fun update(item: TranslationQueue.Item) {
        queue().update(queueRow(item))
    }

    override fun pendingCount(): Int = queue().count(TranslationQueue.Item.STATE_PENDING)

    override fun nextDueAt(): Long? = queue().earliest(TranslationQueue.Item.STATE_PENDING)

    /**
     * Tulkki: drops every pending row - the work the queue owed when the interpreter was switched
     * off. The pending state is the only one that carries a target language the pump still classifies
     * against ([TranslationQueue.Store.clearPending]), so this is exactly the state whose
     * lifetime ended with the interpreter.
     *
     * A done or failed row is left where it is: a done row has already stopped carrying the
     * message, and a failed row is the failures screen's own list, which the owner can read.
     */
    override fun clearPending() {
        queue().deleteInState(TranslationQueue.Item.STATE_PENDING)
    }

    /**
     * Tulkki: the translation failures the phone is still carrying, newest first, for the diagnostics
     * screen - the received messages the queue still owes an answer for, and the sends that never
     * left because their translation failed.
     *
     * Nothing here is a second record. A failed queue item keeps its attempt count and DeepSeek's
     * own message and is not deleted when it fails; a failed send is an outgoing message row the hold
     * already wrote before the attempt; and the reason each one failed is the app's one kept failure,
     * [TranslationActivity.lastFailure]. Which of those it is, and whether it belongs to the
     * message being asked about, is decided in [TranslationFailures], not here.
     *
     * **The rows carry the message's own text, and that is the owner's decision.**
     * The screen lists a failure with the words it is about, so both projections select the body
     * column and this read passes it through; design §3.2 listed the text under "must never show",
     * and that line is superseded for this one surface (the reversal and its reasoning are recorded
     * in [TranslationFailures]' own comment). Nothing else about the cover changes - the bubble
     * still never shows an original - and there is no missing-text case: a queue row cascades with
     * its message and the send read filters deleted rows.
     */
    fun recentTranslationFailures(
            activity: TranslationActivity?
    ): List<TranslationFailures.Failure> {
        val recorded = activity?.lastFailure()
        val rows = ArrayList<TranslationFailures.Failure>()
        readQueueFailures(rows, recorded)
        readSendFailures(rows, recorded)
        return TranslationFailures.recent(rows)
    }

    /**
     * Tulkki: the cause recorded for one message's row, or `null` when none is.
     *
     * Item 17's tap asks this and nothing else: whether the translation this message last had was
     * *refused* ([FailureCause.CHECK_REFUSED]) is what decides if the tap must ask again
     * differently. The failures read is the one door to a row's cause - it is the projection that
     * carries `failure_cause` - and this walks it rather than a screen's filtered list, so a row the
     * screen would not show still answers here.
     */
    fun causeFor(messageUuid: String?): FailureCause? {
        if (messageUuid == null) {
            return null
        }
        for (row in failures().queueFailures()) {
            if (messageUuid == row.messageUuid) {
                return FailureCause.fromStored(row.failureCause)
            }
        }
        return null
    }

    /**
     * The failures screen's own store (S5-6), the read slice: `TranslationStore` composes no failures
     * statement and walks no cursor any more, and `:data`'s `TranslationFailureStore` is the one door
     * to the two projections. What stays here is the mapping into the deciding type - and the deciding
     * itself, which is `TranslationFailures`'.
     */
    private fun failures(): TranslationFailureStore = TranslationFailureStore.get(context)

    /** The received messages that failed: the queue's own rows, minus what the queue already fixed. */
    private fun readQueueFailures(
            rows: MutableList<TranslationFailures.Failure>,
            recorded: TranslationActivity.Failure?
    ) {
        for (row in failures().queueFailures()) {
            rows.add(
                    TranslationFailures.received(
                            row.messageUuid,
                            row.conversationUuid,
                            row.conversationJid,
                            row.body,
                            row.state,
                            row.attempts,
                            row.createdAt,
                            row.failedAt,
                            row.lastError,
                            row.failureCause,
                            recorded))
        }
    }

    /**
     * The sends whose translation failed: the outgoing message rows the hold wrote and never handed
     * over. The row is its own evidence - `translation_state = FAILED` was set by the send path
     * when the attempt failed - and the reason, where the app still has it, comes from the one kept
     * failure.
     */
    private fun readSendFailures(
            rows: MutableList<TranslationFailures.Failure>,
            recorded: TranslationActivity.Failure?
    ) {
        for (row in
                failures().sendFailures(Message.TRANSLATION_FAILED, Message.STATUS_RECEIVED)) {
            rows.add(
                    TranslationFailures.send(
                            row.messageUuid,
                            row.conversationUuid,
                            row.conversationJid,
                            row.body,
                            row.timeSent,
                            recorded))
        }
    }

    /**
     * Tulkki: the rows the gap sweep named, as the messages they are.
     *
     * This is the read the retired watermark pass did with a `timeSent > ?` window, narrowed to
     * what the ledger actually opened: the uuids come from `SyncGapDao.gapSweepCandidates`, which is
     * `delivery = 1 AND translation_state = 0` scoped to the account and its open regions. Nothing
     * here decides anything - the rows are mapped and [StartupBacklog.eligible] judges them -
     * and there is no `LIMIT`, because a gap is bounded by construction.
     *
     * **The read itself is `:data`'s now (S5-6).** `MessageLookupStore` holds the handle, chunks the
     * uuids and walks the cursor into `Message`s, skipping a row whose conversation cannot be
     * resolved and one that cannot be parsed; what is left here is the mapping into
     * [StartupBacklog.Row], which needs the host and `:translation`'s own candidate rules. This
     * was the last `db()` in this class, and the class no longer holds the handle at all.
     */
    fun historyCandidates(messageUuids: List<String>?): List<StartupBacklog.Row> {
        val rows = ArrayList<StartupBacklog.Row>()
        val host = EngineHost.orNull() ?: return rows
        if (messageUuids.isNullOrEmpty()) {
            return rows
        }
        for (message in lookup().messagesByUuid(messageUuids, host::conversation)) {
            rows.add(historyRow(message, host))
        }
        return rows
    }

    /**
     * One stored message as the selection needs it.
     *
     * The live path's own mapping, with the two stanza-level facts a stored row cannot have: it is
     * not a query result this process saw, and it is not a correction arriving now - it is the message
     * itself, read back.
     */
    private fun historyRow(message: Message, host: EngineHost): StartupBacklog.Row {
        val conversationUuid = message.getConversationUuid()
        val candidate = candidate(message, message.getBody(true), false, false, false)
        // The one thing the live mapping answers for a different question here. There,
        // `hasReactions` means "the stanza that just arrived is a reaction"; on a stored row
        // the same column holds reactions other people have left *on* this message since. A
        // heart on a message is not a reason to leave it covered for ever, and a false
        // positive costs one local language check, so the pass does not refuse it.
        candidate.hasReactions = false
        return StartupBacklog.Row(
                message.getUuid(),
                conversationUuid,
                message.getTimeSent(),
                message.getTranslationState(),
                candidate)
    }

    /**
     * Tulkki: drop everything the translation layer holds for one message - its queue item and the
     * stored pair - because the text those describe is no longer the message.
     *
     * Called when an edit replaces a body. Both halves have to go together: the stored translation
     * was made from the old text, so keeping it shows the owner a rendering of words that are no
     * longer there, and a queue item keyed by a uuid the row no longer has can never be written back.
     * The row's own text is untouched - this is about the translation, not the message.
     */
    fun forget(messageUuid: String?) {
        if (messageUuid == null) {
            return
        }
        queue().deleteByMessage(messageUuid)
        writeBack().clearTranslation(messageUuid)
    }

    /**
     * Tulkki: whether a translation is queued for this message. The receive path asks before it
     * rotates a corrected row's uuid: a live item is keyed by that uuid, and moving the row out from
     * under it would leave the item with nothing to write its answer to.
     */
    fun hasQueued(messageUuid: String?): Boolean = queue().hasQueued(messageUuid)

    override fun makeDue(item: TranslationQueue.Item, now: Long) {
        // One statement, and only for a message that is not already done. A retryable failure that is
        // waiting out its backoff is included on purpose: the owner asked for this one now.
        //
        // The target language and the cache key are rewritten with the revival, which is what makes
        // an explicit re-request use the language in force now rather than the one the row captured
        // when it was first queued. `insert`'s CONFLICT_IGNORE is deliberately untouched: this is the
        // owner's tap, and the automatic pass must still leave an existing row alone.
        queue()
                .makeDue(
                        item.messageUuid ?: throw NullPointerException(),
                        item.targetLanguage,
                        item.cacheKey ?: throw NullPointerException(),
                        now,
                        TranslationQueue.Item.STATE_PENDING,
                        TranslationQueue.Item.STATE_DONE)
    }

    /** One queue row as `:data` reads it. The two types are the same fields and no logic. */
    private fun queueRow(item: TranslationQueue.Item): TranslationQueueRow {
        // The row's own Kotlin type makes these three non-null, and the Java that built this row
        // called the same Kotlin constructor: a null here was already a NullPointerException, so
        // the port keeps the violation where it was rather than inventing a value.
        return TranslationQueueRow(
                item.messageUuid ?: throw NullPointerException(),
                item.conversationUuid,
                item.body ?: throw NullPointerException(),
                item.targetLanguage,
                item.cacheKey ?: throw NullPointerException(),
                item.state,
                item.attempts,
                item.nextAttemptAt,
                item.lastError,
                item.createdAt,
                item.failedAt,
                // Item 17's cause, as the column's own string: `:data` holds the word and no rule, so
                // the enum's spelling is what crosses the boundary and `FailureCause.fromStored` is
                // what reads it back.
                item.failureCause?.stored())
    }

    /** One queue row as `:data` reads it, the other way: the row's fields are Kotlin `val`s. */
    private fun queueItem(row: TranslationQueueRow): TranslationQueue.Item =
            queueItemOf(
                    row.messageUuid,
                    row.conversationUuid,
                    row.body,
                    row.targetLanguage,
                    row.cacheKey,
                    row.state,
                    row.attempts,
                    row.nextAttemptAt,
                    row.lastError,
                    row.createdAt,
                    row.failedAt,
                    row.failureCause)

    /** And back. The row's own states and attempts are the queue's, written as read. */
    private fun queueItemOf(
            messageUuid: String?,
            conversationUuid: String?,
            body: String?,
            targetLanguage: String?,
            cacheKey: String?,
            state: Int,
            attempts: Int,
            nextAttemptAt: Long,
            lastError: String?,
            createdAt: Long,
            failedAt: Long,
            failureCause: String?
    ): TranslationQueue.Item {
        val item =
                TranslationQueue.Item(
                        messageUuid, conversationUuid, body, targetLanguage, cacheKey, createdAt)
        item.state = state
        item.attempts = attempts
        item.nextAttemptAt = nextAttemptAt
        item.lastError = lastError
        item.failedAt = failedAt
        item.failureCause = FailureCause.fromStored(failureCause)
        return item
    }

    // -- the cache -------------------------------------------------------------------------------

    /**
     * The cache's own store (S5-6), the cache slice: `TranslationStore` composes no cache statement
     * and no cache `ContentValues` any more, and `:data`'s `TranslationCacheStore` is the
     * one door to the table. What stays here is the mapping - the row and the entry are the same five
     * fields and no logic.
     */
    private fun cache(): TranslationCacheStore = TranslationCacheStore.get(context)

    override fun get(cacheKey: String): TranslationCache.Entry? {
        val row = cache().find(cacheKey) ?: return null
        return TranslationCache.Entry(
                row.cacheKey,
                row.detectedLanguage,
                row.translatedBody,
                row.totalTokens,
                row.createdAt)
    }

    override fun put(entry: TranslationCache.Entry) {
        // The row's own columns are non-null and the entry's fields are not - the Java the entry
        // comes from was a platform type and this class handed them straight to the row, which threw
        // inside its own null check. The names are therefore required, not guessed.
        val cacheKey =
                entry.cacheKey
                        ?: throw NullPointerException("TranslationCache.Entry.cacheKey is null")
        val translatedBody =
                entry.translatedBody
                        ?: throw NullPointerException("TranslationCache.Entry.translatedBody is null")
        cache()
                .put(
                        TranslationCacheRow(
                                cacheKey,
                                entry.detectedLanguage,
                                translatedBody,
                                entry.totalTokens,
                                entry.createdAt))
    }

    // -- the write-back --------------------------------------------------------------------------

    /**
     * The write-back's own store (S5-6), the last slice: `TranslationStore` composes no message or
     * conversation statement any more, and `:data`'s [MessageTranslationStore] is the one door to those
     * two columns' worth of rows. What stays here is the half that needs the host - updating the loaded
     * `Message` or `Conversation` and asking for a redraw - which is not a row operation at
     * all.
     */
    private fun writeBack(): MessageTranslationStore = MessageTranslationStore.get(context)

    override fun writeTranslation(
            conversationUuid: String?,
            messageUuid: String?,
            translatedBody: String?,
            language: String?,
            state: Int
    ) {
        val uuid = messageUuid ?: throw NullPointerException()
        writeBack().writeTranslation(uuid, translatedBody, language, state)

        val host = EngineHost.orNull()
        if (host == null) {
            // No host in this process; the row is still correct for the next start.
            return
        }
        // `conversationUuid` is nullable - it comes from the message row - and
        // `EngineHost.conversation` takes a non-null uuid: the Java this replaces passed the platform
        // type straight in, so the name is required here rather than guessed.
        val redrawUuid =
                conversationUuid
                        ?: throw NullPointerException(
                                "TranslationStore: no conversation uuid to redraw")
        val conversation = host.conversation(redrawUuid)
        val message = conversation?.findMessageWithUuid(uuid)
        if (message != null) {
            message.setTranslatedBody(translatedBody)
            message.setTranslationLang(language)
            message.setTranslationState(state)
        }
        host.updateConversationUi()
        // Tulkki: the notification was drawn while the body was still covered - a message arrives, its
        // translation is bought a moment later, and the shade keeps whatever it was told at the moment
        // it was posted. Redrawing it here is what stops "Not translated yet" outliving the translation
        // it was describing, which is the whole of why that sentence ever appears in the shade: the
        // notification is the one surface that cannot cover a body itself.
        host.updateNotifications()
    }

    // -- the usage ledger ------------------------------------------------------------------------

    /**
     * The ledger's own store (S5-6), the usage slice: `TranslationStore` composes no ledger statement
     * and no `a=a+?` clause any more, and `:data`'s [TranslationUsageStore] is the one door to the two
     * tables. It is also where the day's **per-origin** rows live, which is the drill-down the ledger
     * screen opens on a day: `TranslationUsageStore.byOrigin(day)` is that read, and it is the read
     * model `:ui` consumes rather than anything on this class.
     */
    private fun usage(): TranslationUsageStore = TranslationUsageStore.get(context)

    /**
     * Tulkki: one local day's tokens, or zero when the day has no row.
     *
     * Read back through `:data`'s own row mapping, so the screen and the cap cannot disagree about
     * which column is which.
     */
    override fun read(day: String): TokenUsage = tokenUsage(usage().read(day))

    /**
     * Tulkki: adds one call's split to a day's row - and to that day's row for `origin` - creating
     * whichever is missing.
     *
     * `:data` owns the shape of that write: the day's figure and the day's breakdown are written by
     * one call, and both halves can only ever add. `origin` is the conversation the call belonged to
     * (its uuid) or null for a call that belongs to none.
     */
    override fun add(day: String, origin: String?, delta: TokenUsage) {
        if (delta.isEmpty()) {
            return
        }
        usage().add(day, origin, usageCounts(delta))
    }

    /**
     * Tulkki: the most recent days that have a row, newest first.
     *
     * Newest first because that is the order the screen reads in, and the row set is bounded by the
     * caller rather than by the query - the table keeps every day, and how many of them a screen shows
     * is a display decision (`UsageLedger.SHOWN_DAYS`).
     */
    override fun recent(limit: Int): List<UsageLedger.Day> {
        val days = ArrayList<UsageLedger.Day>()
        if (limit <= 0) {
            return days
        }
        for (row in usage().recent(limit)) {
            days.add(UsageLedger.Day(row.day, tokenUsage(row.usage)))
        }
        return days
    }

    /** One `:data` row as the ledger's value. The two are the same six counts and no logic. */
    private fun tokenUsage(counts: UsageCounts): TokenUsage =
            TokenUsage(
                    counts.peakCacheHit,
                    counts.peakCacheMiss,
                    counts.peakOutput,
                    counts.offPeakCacheHit,
                    counts.offPeakCacheMiss,
                    counts.offPeakOutput)

    /** And back, the other way. */
    private fun usageCounts(usage: TokenUsage): UsageCounts =
            UsageCounts(
                    usage.peakCacheHit,
                    usage.peakCacheMiss,
                    usage.peakOutput,
                    usage.offPeakCacheHit,
                    usage.offPeakCacheMiss,
                    usage.offPeakOutput)

    override fun conversationLanguage(conversationUuid: String?): String? {
        if (conversationUuid == null) {
            return null
        }
        val host = EngineHost.orNull()
        val conversation = host?.conversation(conversationUuid)
        if (conversation != null) {
            return conversation.getDetectedLanguage()
        }
        // No host in this process (a worker outliving the service), so ask the row.
        // Read rather than assumed: a weak reading must not replace a language already established,
        // and that comparison needs the stored value even when nothing is loaded.
        return writeBack().conversationLanguage(conversationUuid)
    }

    override fun recordConversationLanguage(conversationUuid: String?, detectedLanguage: String?) {
        if (conversationUuid == null || detectedLanguage == null) {
            return
        }
        writeBack().recordConversationLanguage(conversationUuid, detectedLanguage)

        val host = EngineHost.orNull() ?: return
        val conversation = host.conversation(conversationUuid)
        if (conversation != null) {
            conversation.setDetectedLanguage(detectedLanguage)
        }
        host.updateConversationUi()
    }

    companion object {

        /**
         * Whether Tulkki may spend on an arriving message, asked of a stored row.
         *
         * 3.7 pair 2: this mapping used to be `uk.xa0.tulkki.app.TranslationHooks.candidate`, which
         * `:data` named - the last live `:data` -> `:app` import in the tree, left
         * behind by pair 10. The mapping is pure: the model types it reads are all `:data`', the
         * `Candidate` it fills is `:translation`'s, and its only other readers are the live
         * receive path and the JVM test, both of which go through `TranslationHooks.candidate` -
         * which is now a one-line forward to this method. It lives in this class because
         * `TranslationStore` is the one `:data` file that already imports
         * `TranslationDecision`, so moving it here added no second name from that module.
         *
         * The body is a parameter rather than a read of `message.getBody(true)`, and it is the
         * only thing about the message that is: upstream's `getBody` goes through
         * `android.util.Pair`, which the unit-test android.jar stubs out to return nothing at all, and a
         * mapping the test cannot call is a mapping the test cannot pin.
         */
        @JvmStatic
        fun candidate(
                message: Message,
                body: String?,
                fromArchive: Boolean,
                delayed: Boolean,
                replacement: Boolean
        ): TranslationDecision.Candidate {
            val candidate = TranslationDecision.Candidate()
            candidate.received = message.getStatus() == Message.STATUS_RECEIVED
            candidate.fromArchive = fromArchive
            candidate.delayed = delayed
            candidate.deleted = message.isDeleted()
            // NOT `getFileParams() != null`. That getter never returns null: for a message with nothing
            // attached it manufactures an empty FileParams, so the null check was true for *every*
            // message and refused all of them - the automatic pass and the tap alike, which is why
            // nothing was ever translated. `isEmpty()` is the question that is actually being asked, and
            // it is upstream's own test for the same thing in Message.getContentValues().
            candidate.hasFileParams = !message.getFileParams().isEmpty()
            candidate.hasReplacement = replacement
            candidate.hasReactions = !message.isReactionsEmpty()
            candidate.encryption = message.getEncryption()
            candidate.body = body
            // The name the interface shows, read the one way it is read anywhere: a body that is exactly
            // its conversation's own name is a bare name, not prose, and must not be bought.
            candidate.conversationName = ConversationName.of(message.getConversation())
            return candidate
        }
    }
}
