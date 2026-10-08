package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.Comparator

/**
 * The translation failures the phone is still carrying, and why each one failed.
 *
 * <p>A failure lives on the message it happened to - the bubble is covered and says why, a held send
 * says why in the composer - so it can only be read one message at a time, and only while that
 * message is on screen. This class is the list behind a screen that answers "what has failed, and
 * why" without opening the conversations, and it carries both halves: the received messages the
 * translation queue still owes an answer for, and the sends whose translation failed and which
 * therefore never left the phone.
 *
 * <p><strong>It shows the message's own text, and that is a deliberate reversal (the owner's
 * decision, not a drift of this file).</strong> The two projections - `:data`'s
 * `uk.xa0.tulkki.data.translation.TranslationFailureQueries` since S5-6 - select the
 * `translation_queue` row's `body` and the failed send's `messages.body` as well as the facts a row
 * is identified by, because the screen lists a failure with the words it is about: a failure the
 * owner cannot recognise is a failure they cannot act on. Design §3.2 listed the message text under
 * "Must never show"; that line is superseded here and recorded on `master`, not in this file. What is
 * <em>not</em> relaxed: the original is still never drawn in a bubble, and the cover is still decided
 * elsewhere - this is the failures screen, the one place whose whole purpose is to tell the owner
 * what failed. There is no empty-text case to handle: a queue row cascades with its message and the
 * send read filters `deleted = 0`, so a failure whose message is gone is not in either result set at
 * all.
 *
 * <p>The reason is [HeldSend.HoldReason], not a second vocabulary: that is what the queue's own
 * failure classifies, what the send path classifies, what the cover renders, and what the usage
 * screen's last-failure line says. When the record does not reach a message - an older failed send,
 * because the app keeps the last failure and not a list of them - the row says so rather than
 * inventing one.
 *
 * <p>The time on a row is labelled, because there are two different things it can be: a failure's own
 * time, or the time of the message the failure is about. [When] is that distinction, and no row
 * claims a failure time it does not have.
 *
 * <p>Pure Kotlin against a list of rows, so the deciding - which rows still stand, in what order, how
 * many, and what each one's reason and time are - is exercised by JVM unit tests. Reading the rows is
 * `:data`'s job since S5-6: the two projections moved to
 * `uk.xa0.tulkki.data.translation.TranslationFailureQueries` (with `TranslationFailureStore` walking
 * their cursors) and this class is handed the rows, because `SQLite` is `:data`'s and the deciding is
 * this one's. The pins that the projections name the columns they need - including the message's own
 * text, the reversal recorded above - moved with them.
 *
 * <p><strong>Two spellings here are Kotlin's, not Java's, and both are forced.</strong> The class is
 * not instantiated at all - every member is static - so the constructor is `private` and the members
 * live in a companion object, which is what keeps the factories and [RECENT_LIMIT] *static* to a Java
 * caller rather than reachable only through an instance. And [Failure]'s field for [When] is written
 * `` `when` ``: the name is a Kotlin keyword, so the property can only be spelled in backticks, but
 * the field it compiles to is `when` and the Java readers (`TranslationFailuresTest`, `:ui`'s screen)
 * are untouched.
 */
class TranslationFailures private constructor() {

    companion object {

        /** How many rows the screen shows. More than this is a conversation, not a summary. */
        const val RECENT_LIMIT = 50

        /**
         * A received message's failure, from its queue item.
         *
         * <p>What still stands is the queue's own answer: a message whose item is done was translated
         * afterwards, and a list that kept it would claim a bubble is covered when it is not; one that
         * has never failed has nothing to report. `recorded` is the app's one kept failure, and it
         * is used only when it belongs to this very message - the same rule a covered bubble follows,
         * so the row and the bubble cannot disagree about the same message.
         *
         * <p>The time is the failure's own when the row has one, and the message's arrival when it
         * does not - a row written before schema 73, or one from a database where that migration did
         * not land. Which of the two it is travels with the row as [When], so the screen can say it.
         */
        @JvmStatic
        fun received(
                messageUuid: String?,
                conversationUuid: String?,
                conversationJid: String?,
                body: String?,
                state: Int,
                attempts: Int,
                createdAt: Long,
                failedAt: Long,
                lastError: String?,
                /**
                 * Item 17's cause, as the column stores it, or `null` for "none recorded". Defaulted
                 * so a caller that has no cause - a screen's own row image, a send - does not have to
                 * say so: `:ui` names its arguments and this keeps the factory's shape compatible
                 * with it, which matters because `:ui` is not this lane's to change.
                 */
                cause: String? = null,
                recorded: TranslationActivityPort.RecordedFailure?
        ): Failure {
            val kept = if (belongsTo(recorded, messageUuid)) recorded else null
            val failureTimeKnown = failedAt > 0
            return Failure(
                    messageUuid,
                    conversationUuid,
                    conversationJid,
                    body,
                    if (failureTimeKnown) When.FAILED else When.ARRIVED,
                    if (failureTimeKnown) failedAt else createdAt,
                    attempts > 0 && state != TranslationQueue.Item.STATE_DONE,
                    if (state == TranslationQueue.Item.STATE_PENDING) Next.RETRY else Next.GAVE_UP,
                    if (kept != null) kept.reason() else reasonFromQueue(state, attempts, lastError),
                    if (kept != null) kept.detail() else lastError,
                    FailureCause.fromStored(cause))
        }

        /**
         * A send's failure, from the message row the hold wrote, and from the failure record if it
         * reaches that message.
         *
         * <p>The next attempt is the owner's, not the app's: a send that could not be translated stays
         * held in the composer, and the row says that it has not gone out instead of promising an
         * attempt nothing here made. Held is also all that can honestly be said - a held message is
         * upstream's own unsent row, and it is still sitting in the conversation.
         */
        @JvmStatic
        fun send(
                messageUuid: String?,
                conversationUuid: String?,
                conversationJid: String?,
                body: String?,
                timeSent: Long,
                recorded: TranslationActivityPort.RecordedFailure?
        ): Failure {
            val kept = if (belongsTo(recorded, messageUuid)) recorded else null
            return Failure(
                    messageUuid,
                    conversationUuid,
                    conversationJid,
                    body,
                    if (kept != null) When.SEND_FAILED else When.WRITTEN,
                    if (kept != null) kept.at() else timeSent,
                    true,
                    Next.HELD,
                    if (kept != null) kept.reason() else null,
                    if (kept != null) kept.detail() else null,
                    // The cause column is the queue's own; a held send has no such row.
                    null)
        }

        /**
         * The rows the screen shows: everything that still stands, newest first, at most
         * [RECENT_LIMIT] of them.
         *
         * <p>The order is on the time each row shows, and ties are broken by the message's uuid, so
         * the list is stable: two failures in the same millisecond must not swap places between two
         * glances at the same screen. Received and sent failures share the one order because the time
         * is the only thing a reader can sort by.
         */
        @JvmStatic
        fun recent(candidates: List<Failure?>?): List<Failure> {
            val standing = ArrayList<Failure>()
            if (candidates != null) {
                for (candidate in candidates) {
                    if (candidate != null && candidate.outstanding) {
                        standing.add(candidate)
                    }
                }
            }
            standing.sortWith(NEWEST_FIRST)
            return if (standing.size <= RECENT_LIMIT) {
                standing
            } else {
                ArrayList<Failure>(standing.subList(0, RECENT_LIMIT))
            }
        }

        /** Newest first, and never ambiguous: the uuid breaks a tie in time. */
        private val NEWEST_FIRST: Comparator<Failure> =
                Comparator { left, right ->
                    val byTime = right.at.compareTo(left.at)
                    if (byTime != 0) byTime else key(right).compareTo(key(left))
                }

        private fun key(failure: Failure): String = failure.messageUuid ?: ""

        /**
         * Whether the one kept failure is this message's. A failure belongs to the message it happened
         * to; a reason that was some other message's is not this one's to show.
         */
        private fun belongsTo(
                recorded: TranslationActivityPort.RecordedFailure?,
                messageUuid: String?
        ): Boolean =
                recorded != null &&
                        recorded.reason() != null &&
                        messageUuid != null &&
                        messageUuid == recorded.messageUuid()

        /**
         * Why a queue item failed, from the queue's own rules rather than a second guess.
         *
         * <p>A failed item that could still have been retried was refused outright - the client said
         * retrying cannot help - so the error text names the reason, which is exactly what
         * [HeldSend.failureReason] decides, with the same words the usage screen uses. A failed item
         * the schedule ran out on, and a pending item waiting for its next attempt, are both the
         * failure that waiting is supposed to fix: unreachable.
         */
        private fun reasonFromQueue(
                state: Int,
                attempts: Int,
                lastError: String?
        ): HeldSend.HoldReason =
                if (state == TranslationQueue.Item.STATE_FAILED &&
                                TranslationBackoff.retryable(attempts)) {
                    HeldSend.failureReason(false, lastError)
                } else {
                    HeldSend.failureReason(true, lastError)
                }
    }

    /**
     * What the timestamp on a row is.
     *
     * <p>Which of the two a row is showing is a fact about the row and not a detail to smooth over:
     * a failure's own time is one thing, and the message's own time is a different thing that has to
     * say itself rather than pass for the first. The label also has to say whether the message was
     * received or sent, because when and which conversation are all a row has to identify itself
     * with.
     */
    enum class When {
        /** A received message, and the time is the failure's own. */
        FAILED,

        /** A received message whose failure time was not recorded: the time is when it arrived. */
        ARRIVED,

        /** A send, and the time is the failure's own. */
        SEND_FAILED,

        /** A send whose failure time was not recorded: the time is when it was written. */
        WRITTEN
    }

    /**
     * What happens next to this failure, which is two different questions depending on what it is.
     *
     * <p>A received message is the queue's: either another attempt is scheduled, or the retry schedule
     * ran out and the message is failed for good. A send is the owner's: it stays held in the composer
     * and nothing re-attempts it on their behalf, so the row says what is true of it - that it has not
     * gone out - rather than promising an attempt nobody has decided on.
     */
    enum class Next {
        /** A received message the queue will try again. */
        RETRY,

        /** A received message the queue has given up on, until somebody asks for it again. */
        GAVE_UP,

        /** A send, still held: it has not gone out. */
        HELD
    }

    /**
     * One failed translation, as the screen may show it.
     *
     * <p>Every field is a fact about the attempt or about the message it happened to: when, which
     * conversation, why, what happens next, and the words the failure is about - the reversal recorded
     * above. There is no cache key here and no second way to read a covered bubble.
     *
     * <p>`internal` rather than Java's `private`: Kotlin's `private` does not reach the enclosing
     * class, so the two factories in the companion - the only two places a row is ever built - could
     * not call it. It is the same choice [LanguageCheck.Report] and [TextLanguage.Guess] made.
     *
     * <p>The `@JvmField`s are what the Java readers use (`TranslationFailuresTest`'s assertions,
     * `:ui`'s list rows); the field for [When] is spelled in backticks because the name is a Kotlin
     * keyword, which is the one spelling Java and Kotlin cannot share.
     */
    class Failure internal constructor(
            @JvmField val messageUuid: String?,
            @JvmField val conversationUuid: String?,

            /** The conversation's own address as the database holds it, or `null` if it is gone. */
            @JvmField val conversationJid: String?,

            /**
             * The message's own text, which the screen shows so the owner can recognise what failed.
             *
             * <p>The owner reversed the "never show the original" rule for this one surface (see the
             * class comment); it is not a second way to read a covered bubble, and it is never absent
             * in practice - a queue row cascades with its message and the send read filters deleted
             * rows - so `null` here means a genuinely absent body rather than a hidden one.
             */
            @JvmField val body: String?,

            /** What [at] is. */
            @JvmField val `when`: When,

            /** The time the row shows, in the phone's own clock. */
            @JvmField val at: Long,

            /** True while the phone is still carrying this one: what the screen lists. */
            @JvmField val outstanding: Boolean,

            /** What happens next. */
            @JvmField val next: Next,

            /** Why it failed, or `null` when the reason was not kept. */
            @JvmField val reason: HeldSend.HoldReason?,

            /** DeepSeek's or the network's own words for that reason, or `null`. */
            @JvmField val detail: String?,

            /**
             * Item 17's own cause for this row, or `null` when the column records none.
             *
             * <p>It is a different fact from [reason]: the reason is the app's one vocabulary for why
             * an attempt failed, and it flattens several causes onto one word (`HeldSend.HoldReason.
             * FAILED` is both the check's refusal and an unusable answer). The cause is the durable
             * per-row answer, and the refusal is the one the tap and the retry rule turn on. A send's
             * failure has no cause at all - the column is the queue's.
             */
            @JvmField val cause: FailureCause?
    )
}
