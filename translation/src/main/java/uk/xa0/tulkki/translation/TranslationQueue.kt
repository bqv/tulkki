package uk.xa0.tulkki.translation

/**
 * The messages waiting to be translated.
 *
 * It is a table, not a list in memory, because the queue has to survive the process being killed:
 * a message that arrived while the app was alive and has not been translated yet must still be
 * translated when it comes back. The platform's job scheduler is what wakes the work up; this class
 * only owns the state machine and never waits for anything itself.
 *
 * A non-retryable failure is final: the item is failed, and the next run of the queue will not see
 * it again. A retryable one stays pending with a later due time.
 *
 * Pure Kotlin, no Android types, so it is exercised by JVM unit tests against an in-memory store.
 */
class TranslationQueue(private val store: Store) {

    /** Where the queue lives between runs. */
    interface Store {
        /** Inserts unless an item for this message is already there. */
        fun insert(item: Item)

        /** The item that is pending and due at [now], oldest first, or `null`. */
        fun nextDue(now: Long): Item?

        /**
         * The pending items due at [now], oldest first, at most [limit] of them. The one query a
         * batched pass needs: one snapshot of what is due at that moment, in the order the queue has
         * always handed items over, so a batch never has to re-ask and risk the same item twice.
         */
        fun due(now: Long, limit: Int): List<Item>

        /**
         * Tulkki: the items the queue has *given up on* - state [Item.STATE_FAILED], oldest first,
         * at most [limit] of them.
         *
         * It exists for item 17's self-retry: a row a cause stopped is not work the queue is waiting
         * on, so the pass never reads it, and a cause that has observably cleared has to be able to
         * reach it. A failed row's due time is the attempt that failed it, so every one of them is
         * due at any later instant.
         */
        fun failedDue(now: Long, limit: Int): List<Item>

        fun update(item: Item)

        /**
         * Tulkki: drops every *pending* item - the interpreter's carried work.
         *
         * It exists for the moment the interpreter is observed off. A pending row is work owed, and
         * it holds the target language captured when it was queued, which the pump classifies
         * against: with the interpreter off that value is no longer the app language, so a row left
         * behind would translate into a language nobody asked for once the interpreter came back.
         *
         * Pending only, deliberately. A done row has already stopped carrying the message, and a
         * failed row is what the failures screen lists - it is the owner's own history, not work the
         * queue owes, and deleting it would be a data loss rather than a mode change.
         */
        fun clearPending()

        fun pendingCount(): Int

        /** When the next pending item becomes due, or `null` when there is none. */
        fun nextDueAt(): Long?

        /**
         * Makes the item for this message due right now with a fresh set of attempts, whether it was
         * waiting for a retry or had already given up, and repoints it at [item.targetLanguage] with
         * [item.cacheKey] - the language and prompt identity in force when the owner asked for it
         * again. Does nothing when the message has no item, or its item is done.
         *
         * Taking an item rather than a uuid is the point: a row that failed keeps the target
         * language it captured when it was first queued, and reviving it without rewriting that
         * column would answer the owner's re-request in a language they are no longer studying.
         */
        fun makeDue(item: Item, now: Long)
    }

    /** One message awaiting translation. */
    class Item(
            @JvmField val messageUuid: String?,
            @JvmField val conversationUuid: String?,
            /**
             * Tulkki: the message's own text while the work is owed, and empty once it is done - see
             * [TranslationQueue.succeeded]. Not `val` for exactly that reason: a row that has been
             * handled must stop being a second copy of the concealed original.
             */
            @JvmField var body: String?,
            /**
             * Tulkki: the language this message is to be translated into. Captured from the app
             * language when the message is queued, and deliberately kept for the automatic pass -
             * work already queued is not rewritten by a later setting change - but rewritten when
             * the owner explicitly asks for the message again ([TranslationQueue.makeDue]), because
             * that request is a decision made now. Not `val` for exactly that reason.
             */
            @JvmField var targetLanguage: String?,
            /**
             * The cache identity as of the moment this message was queued
             * ([PromptBook.translationKey]). It is *not* what the buy uses: the owner can rewrite
             * the instruction while a message waits here, and a buy has to look under the prompt it
             * is actually sending, so `TranslationService` works the key out again at that moment
             * rather than trusting this row. It moves with [targetLanguage] when the owner asks for
             * the message again, so the row's two columns never disagree about what it is for.
             */
            @JvmField var cacheKey: String?,
            @JvmField val createdAt: Long,
    ) {
        @JvmField var state: Int = STATE_PENDING
        @JvmField var attempts: Int = 0
        @JvmField var nextAttemptAt: Long = 0
        @JvmField var lastError: String? = null

        /**
         * Tulkki: when the most recent attempt failed, or `0` when none has. It is the failure's own
         * moment, which is what a screen listing failures should time a row by - [createdAt] is when
         * the message arrived, which is a different fact and has to say so.
         */
        @JvmField var failedAt: Long = 0

        /**
         * Tulkki: item 17's cause - why this row is not being translated, when that is a reason the
         * app can act on ([FailureCause]). `null` is the column's own "no cause recorded", which is
         * deliberately two situations at once: a row that was never blocked, and one stopped by an
         * ordinary retryable failure, which [attempts]/[nextAttemptAt] already carry. A retry clears
         * it with the rest of the retry state, and the refusal ([FailureCause.CHECK_REFUSED]) is the
         * one cause no retry reaches.
         */
        @JvmField var failureCause: FailureCause? = null

        init {
            nextAttemptAt = createdAt
        }

        fun isPending(): Boolean = state == STATE_PENDING

        fun due(now: Long): Boolean = isPending() && nextAttemptAt <= now

        companion object {
            const val STATE_PENDING = 0
            const val STATE_DONE = 1
            const val STATE_FAILED = 2
        }
    }

    fun enqueue(
            messageUuid: String?,
            conversationUuid: String?,
            body: String?,
            targetLanguage: String?,
            now: Long
    ): Item = enqueue(messageUuid, conversationUuid, body, targetLanguage, false, now)

    /**
     * The same insert, with the one fact that changes what the row is a question about: [reAsk] is
     * the owner's tap on a message the local check refused, and it puts the app's re-ask clause into
     * what this row will be bought under ([PromptBook.translationKey]).
     *
     * **This is the enqueue-side setter, and the key is the whole of the durable half.** There is no
     * re-ask flag: the clause changes what is asked, so it belongs in the cache identity, and the
     * only reason it is not simply derived at the buy is that the buy must ask about the wording in
     * force now rather than the one this row captured. The key is written here, carried on the row,
     * rewritten by [makeDue] when the owner's tap revives it, and read back - [PromptBook.isReAsk] -
     * when the request is actually built.
     */
    fun enqueue(
            messageUuid: String?,
            conversationUuid: String?,
            body: String?,
            targetLanguage: String?,
            reAsk: Boolean,
            now: Long
    ): Item {
        val item =
                Item(
                        messageUuid,
                        conversationUuid,
                        body,
                        targetLanguage,
                        PromptBook.translationKey(body, targetLanguage, reAsk),
                        now)
        store.insert(item)
        return item
    }

    fun nextDue(now: Long): Item? = store.nextDue(now)

    /**
     * Up to [limit] items that are due at [now], oldest first. What a batched pass works from: one
     * snapshot, so the pass cannot be handed the same item twice while it collects.
     */
    fun due(now: Long, limit: Int): List<Item> = store.due(now, limit)

    /**
     * The items the queue has given up on, oldest first, at most [limit] of them. Item 17's
     * self-retry is the one reader: a row a cause stopped is invisible to [due], so a cause that has
     * observably cleared needs this read to reach it.
     */
    fun failedDue(now: Long, limit: Int): List<Item> = store.failedDue(now, limit)

    /**
     * Tulkki: stamp one row with the cause that stopped it, without touching anything else about it.
     *
     * It is the pending-row half of the cause column: a row left behind by a pass that could not
     * spend (no key, the cap) is still pending and due, and only its *cause* needs writing. Reviving
     * is [makeDue]'s and failing is [failed]'s; this writes the one column and leaves the row's
     * state, attempts and due time exactly as the pass left them.
     */
    fun stopped(item: Item?, cause: FailureCause?) {
        if (item == null || cause == null || item.failureCause === cause) {
            return
        }
        item.failureCause = cause
        store.update(item)
    }

    /**
     * The owner asked for this message, and [item] is that request: the row is revived with its
     * attempts back, stops waiting out a backoff, and is repointed at the target language and cache
     * identity [item] carries. The backoff exists so a broken account is not hammered, but a person
     * pointing at one message is a new decision, and both "now" and the language are what they asked
     * for. Failing is final only until somebody asks again.
     *
     * This is the explicit path only. [enqueue]'s insert stays `CONFLICT_IGNORE`, so the automatic
     * pass neither revives a row that has given up nor repoints one that is still waiting: carrying
     * the target a row captured when it was queued is the settled design for work already queued,
     * and this method is not that path.
     *
     * Tulkki: the revival clears the row's cause with the rest of its retry state - item 17's
     * self-retry calls this, so a row revived because its blocking cause cleared stops carrying that
     * cause the moment it is asked again, and a row revived by the owner's tap starts from the same
     * clean slate.
     */
    fun makeDue(item: Item?, now: Long) {
        if (item == null || item.messageUuid == null) {
            return
        }
        item.failureCause = null
        store.makeDue(item, now)
    }

    /**
     * The message was handled - translated, found already in the app language, or not language.
     *
     * Tulkki: and the row stops carrying the message with it. The queue exists to hold work that is
     * *owed*, and the body it holds is the message's own text, which for everything the automatic
     * pass touches is the concealed original. A done row is never read again ([nextDue] and [due]
     * ask for pending rows only - the `<> DONE` guard in the store keeps it out of a later insert
     * too), so keeping the text would be a second permanent copy of the original next to the message
     * row: IDEA-SCAN's "do not copy", one forgotten `WHERE` away from a search or an export. The
     * message row remains the record of what was translated, and the cache key stays on the row, so
     * nothing that was paid for is re-derived.
     */
    fun succeeded(item: Item, now: Long) {
        item.state = Item.STATE_DONE
        item.lastError = null
        item.failureCause = null
        item.nextAttemptAt = now
        item.body = ""
        store.update(item)
    }

    /**
     * The call failed. [retryable] comes from [DeepSeekClient.TranslationException]: a non-retryable
     * failure is failed immediately, and a retryable one is failed once the backoff schedule has
     * been used up.
     *
     * The moment is kept on the item as well as the reason, because the two are asked for together
     * afterwards: a screen listing failures says when each one happened. Only a failure writes it, so
     * it is the most recent failed attempt and never a time this item was merely due.
     */
    fun failed(item: Item, retryable: Boolean, error: String?, now: Long) {
        failed(item, retryable, error, null, now)
    }

    /**
     * Tulkki: the same failure, with the cause it is - or `null` for a reason the column must not
     * claim (item 17, two and six): an ordinary retryable failure, and the unusable answer whose
     * remedy is the owner's tap.
     *
     * A retryable failure therefore stores no cause even when a caller names one: the queue's own
     * [Item.attempts]/[Item.nextAttemptAt] axis carries what a retryable failure needs, and a cause
     * column that claimed it would be a second answer to the same question.
     */
    fun failed(item: Item, retryable: Boolean, error: String?, cause: FailureCause?, now: Long) {
        item.attempts++
        item.lastError = error
        item.failedAt = now
        item.failureCause = if (retryable) null else cause
        if (retryable && TranslationBackoff.retryable(item.attempts)) {
            item.nextAttemptAt = now + TranslationBackoff.delayMillis(item.attempts)
        } else {
            item.state = Item.STATE_FAILED
        }
        store.update(item)
    }

    fun pendingCount(): Int = store.pendingCount()

    fun nextDueAt(): Long? = store.nextDueAt()
}
