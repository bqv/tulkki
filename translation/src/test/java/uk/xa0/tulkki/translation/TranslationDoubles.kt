package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.LinkedHashMap

/** In-memory stand-ins for the storage the pure classes talk to. No Android, no device. */
object TranslationDoubles {

    /** The queue, in a map: same semantics as the table, without SQLite. */
    class MemoryQueueStore : TranslationQueue.Store {
        @JvmField val items: MutableMap<String?, TranslationQueue.Item> = LinkedHashMap()

        override fun insert(item: TranslationQueue.Item) {
            if (!items.containsKey(item.messageUuid)) {
                items.put(item.messageUuid, item)
            }
        }

        override fun nextDue(now: Long): TranslationQueue.Item? {
            var best: TranslationQueue.Item? = null
            for (item in items.values) {
                if (!item.due(now)) {
                    continue
                }
                if (best == null
                        || item.nextAttemptAt < best.nextAttemptAt
                        || (item.nextAttemptAt == best.nextAttemptAt
                                && item.createdAt < best.createdAt)) {
                    best = item
                }
            }
            return best
        }

        override fun due(now: Long, limit: Int): List<TranslationQueue.Item> {
            val due = ArrayList<TranslationQueue.Item>()
            for (item in items.values) {
                if (item.due(now)) {
                    due.add(item)
                }
            }
            due.sortWith(compareBy({ it.nextAttemptAt }, { it.createdAt }))
            return if (due.size <= limit) due else ArrayList(due.subList(0, limit))
        }

        override fun update(item: TranslationQueue.Item) {
            items.put(item.messageUuid, item)
        }

        /**
         * Tulkki: the rows the queue has given up on, the state half of item 17's self-retry read.
         * A failed row's due time is the attempt that failed it, so any later instant reads it.
         */
        override fun failedDue(now: Long, limit: Int): List<TranslationQueue.Item> {
            val failed = ArrayList<TranslationQueue.Item>()
            for (item in items.values) {
                if (item.state == TranslationQueue.Item.STATE_FAILED
                        && item.nextAttemptAt <= now) {
                    failed.add(item)
                }
            }
            failed.sortWith(compareBy({ it.nextAttemptAt }, { it.createdAt }))
            return if (failed.size <= limit) failed else ArrayList(failed.subList(0, limit))
        }

        override fun clearPending() {
            items.values.removeAll { it.isPending() }
        }

        override fun pendingCount(): Int {
            var pending = 0
            for (item in items.values) {
                if (item.isPending()) {
                    pending++
                }
            }
            return pending
        }

        override fun nextDueAt(): Long? {
            var earliest: Long? = null
            for (item in items.values) {
                if (!item.isPending()) {
                    continue
                }
                if (earliest == null || item.nextAttemptAt < earliest) {
                    earliest = item.nextAttemptAt
                }
            }
            return earliest
        }

        override fun makeDue(requested: TranslationQueue.Item, now: Long) {
            val item = items.get(requested.messageUuid) ?: return
            if (item.state == TranslationQueue.Item.STATE_DONE) {
                return
            }
            item.state = TranslationQueue.Item.STATE_PENDING
            item.attempts = 0
            item.nextAttemptAt = now
            item.lastError = null
            // Item 17: the revival clears the cause with the rest of the retry state, exactly as
            // `MAKE_DUE` does in the real store.
            item.failureCause = null
            // The same two columns the real store rewrites: the owner asking again is a decision in
            // the language in force now, not the one the row captured when it was queued.
            item.targetLanguage = requested.targetLanguage
            item.cacheKey = requested.cacheKey
        }
    }

    class MemoryCacheStore : TranslationCache.Store {
        @JvmField val entries: MutableMap<String?, TranslationCache.Entry> = LinkedHashMap()

        override fun get(cacheKey: String): TranslationCache.Entry? {
            return entries.get(cacheKey)
        }

        override fun put(entry: TranslationCache.Entry) {
            entries.put(entry.cacheKey, entry)
        }
    }

    class MemoryCounterStore : DailyTokenCounter.Store {
        @JvmField var day: String? = null
        @JvmField var tokens: Int = 0

        override fun day(): String? {
            return day
        }

        override fun tokens(): Int {
            return tokens
        }

        override fun save(day: String?, tokens: Int) {
            this.day = day
            this.tokens = tokens
        }
    }

    /** One recorded failure, as the port hands it to the engine. */
    class RecordedFailure(
            private val reason: HeldSend.HoldReason?,
            private val detail: String?,
            private val messageUuid: String?,
            private val at: Long
    ) : TranslationActivityPort.RecordedFailure {

        override fun reason(): HeldSend.HoldReason? {
            return reason
        }

        override fun detail(): String? {
            return detail
        }

        override fun messageUuid(): String? {
            return messageUuid
        }

        override fun at(): Long {
            return at
        }
    }

    /**
     * What the engine writes to the day's counters and the last failure: a recording stand-in, so the
     * service's tests assert on what they injected rather than on a second copy of the arithmetic
     * (which `uk.xa0.tulkki.data.TranslationActivityTest` owns).
     */
    class MemoryActivityPort : TranslationActivityPort {
        @JvmField var day: String? = null
        @JvmField var translated: Int = 0
        @JvmField var failure: RecordedFailure? = null

        override fun addTranslated(day: String?, messages: Int) {
            if (day == null || messages <= 0) {
                return
            }
            this.day = day
            this.translated += messages
        }

        override fun recordFailure(
                reason: HeldSend.HoldReason?, detail: String?, messageUuid: String?, at: Long) {
            if (reason == null) {
                return
            }
            // Qualified: inside `MemoryActivityPort` the simple name is the inherited
            // `TranslationActivityPort.RecordedFailure` interface, not this file's class.
            this.failure = TranslationDoubles.RecordedFailure(reason, detail, messageUuid, at)
        }

        /** Messages recorded on `day`: the stale-day rule is the real class's, not this one. */
        fun translatedToday(day: String?): Int {
            return if (day != null && day == this.day) translated else 0
        }

        /** The reason to put on one message's cover, or `null` when it was some other's. */
        fun reasonFor(messageUuid: String?): HeldSend.HoldReason? {
            return if (failure != null
                            && failure!!.reason() != null
                            && messageUuid != null
                            && messageUuid == failure!!.messageUuid())
                    failure!!.reason()
                    else null
        }

        fun lastFailure(): RecordedFailure? {
            return failure
        }
    }

    /**
     * A client that must never be built or called. For the paths that have to cost nothing - a cache
     * hit, a message already in the app language - the assertion is the test's evidence that no
     * request was made, not merely that no request was counted.
     */
    class NoCallClient : TranslationService.Client {
        override fun translate(text: String?, targetLanguage: String?): DeepSeekClient.Result {
            throw AssertionError("the API must not be called for a cached answer")
        }

        override fun translate(
                text: String?, targetLanguage: String?, reAsk: Boolean): DeepSeekClient.Result {
            throw AssertionError("the API must not be called for a cached answer")
        }

        override fun translateBatch(
                targetLanguage: String?, items: List<DeepSeekClient.BatchRequest>): DeepSeekClient.BatchResult {
            throw AssertionError("the API must not be called for a cached answer")
        }
    }

    /** Everything the service wrote back, so a test can assert on the outcome of a message. */
    class RecordingWriter : TranslationService.Writer {
        class Write(
                @JvmField val conversationUuid: String?,
                @JvmField val messageUuid: String?,
                @JvmField val translatedBody: String?,
                @JvmField val language: String?,
                @JvmField val state: Int
        )

        @JvmField val writes = ArrayList<Write>()
        @JvmField val conversationLanguages: MutableMap<String?, String?> = LinkedHashMap()

        override fun writeTranslation(
                conversationUuid: String?,
                messageUuid: String?,
                translatedBody: String?,
                language: String?,
                state: Int) {
            writes.add(
                    Write(conversationUuid, messageUuid, translatedBody, language, state))
        }

        override fun recordConversationLanguage(conversationUuid: String?, detectedLanguage: String?) {
            conversationLanguages.put(conversationUuid, detectedLanguage)
        }

        override fun conversationLanguage(conversationUuid: String?): String? {
            return conversationLanguages.get(conversationUuid)
        }

        fun writeFor(messageUuid: String?): Write? {
            for (write in writes) {
                if (write.messageUuid == messageUuid) {
                    return write
                }
            }
            return null
        }
    }

    /** The service's debug log, kept so a test can assert on the line it writes. */
    class RecordingLog : TranslationService.Log {
        @JvmField val lines = ArrayList<String>()

        override fun debug(message: String) {
            lines.add(message)
        }
    }
}
