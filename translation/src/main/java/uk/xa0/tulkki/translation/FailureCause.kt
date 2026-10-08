package uk.xa0.tulkki.translation

import java.util.Locale

/**
 * Item 17's per-row failure cause: why a received row is not being translated, when the reason is one
 * the app can do something about.
 *
 * <p>The column (`translation_queue.failure_cause`, schema 80) is `:data`'s and holds a string and no
 * rule; this enum is the rule's home. It answers two questions and nothing else:
 *
 * <ul>
 *   <li><strong>which cause a reason is</strong> - [fromReason] maps the reasons the app
 *       already names ([HeldSend.HoldReason]) onto the column's vocabulary, and answers
 *       `null` for everything else, because the column may not claim what the queue's own
 *       `attempts`/`next_attempt_at` axis already carries: an ordinary retryable API
 *       failure is the backoff's business, not a cause;
 *   <li><strong>whether clearing it is what a retry waits for</strong> - [clearable]. Four
 *       causes are cleared by an event outside the row (a key entered or changed, credit present, the
 *       cap rolled over or raised); one is not, and that one is the whole reason the distinction
 *       exists: repeating the local check's refusal reproduces the refusal, so only the owner's tap
 *       and its literal re-ask differ (docs/MIGRATION.md item 17, two and six).
 * </ul>
 *
 * <p><strong>One vocabulary, not two.</strong> The four clearable spellings are
 * [HeldSend.HoldReason]'s own names, lowercased - the stored word is derived from the reason's
 * name rather than typed again, so a reason and its cause cannot drift into two spellings of one
 * fact. `check_refused` is the one cause with no [HeldSend.HoldReason], because the
 * refusal is not a reason the send path holds a message for; naming it `CHECK_REFUSED` is what
 * keeps even that one derived. `FailureCauseTest` pins all five stored strings literally, so a
 * rename fails a test rather than orphaning every row written under the old word.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests. `stored()` and
 * `clearable()` stay methods, not properties, because the Java callers and tests call them by their
 * Java names.
 */
enum class FailureCause(private val isClearable: Boolean) {

    /** No DeepSeek key is configured. Cleared by a key being present. */
    NO_KEY(true),

    /** DeepSeek refused the key itself. Cleared by the key being changed to a different one. */
    REJECTED_KEY(true),

    /** The account has no credit left. Cleared by a call that succeeded. */
    NO_CREDIT(true),

    /** The day's token cap is reached. Cleared by the cap rolling over or being raised. */
    CAP_REACHED(true),

    /**
     * The local check refused the answer. Never cleared by a retry: the same request asks the same
     * question and gets the same refusal, so its way out is the owner's tap, which asks differently.
     */
    CHECK_REFUSED(false);

    /** The string the row stores, derived from the name so there is one spelling of each fact. */
    fun stored(): String = name.lowercase(Locale.ROOT)

    /** Whether an event outside the row is what a retry of this cause waits for. */
    fun clearable(): Boolean = isClearable

    companion object {

        /**
         * The cause a reason is, or `null` when the reason is not a cause at all.
         *
         * <p>[HeldSend.HoldReason.UNREACHABLE] and [HeldSend.HoldReason.FAILED] answer
         * `null` on purpose: the first is an ordinary retryable failure, which the queue's own
         * retry axis carries and a cause column must not claim; the second is an answer that was not
         * usable, whose remedy is the owner's tap rather than any of the clearing events (docs/MIGRATION.md
         * item 17, two and six). [HeldSend.HoldReason.DOUBT] joins them: a send held on doubt is
         * not a failed translation at all, and no retry may clear it either.
         * [HeldSend.HoldReason.UNKNOWN_LANGUAGE] is a *send* hold and never a received row's cause.
         */
        @JvmStatic
        fun fromReason(reason: HeldSend.HoldReason?): FailureCause? =
                when (reason) {
                    null -> null
                    HeldSend.HoldReason.NO_KEY -> NO_KEY
                    HeldSend.HoldReason.REJECTED_KEY -> REJECTED_KEY
                    HeldSend.HoldReason.NO_CREDIT -> NO_CREDIT
                    HeldSend.HoldReason.CAP_REACHED -> CAP_REACHED
                    else -> null
                }

        /**
         * The cause a stored string names, or `null` for the column's own "no cause recorded" - and
         * for a word this build does not know, which is the same silence as a null: a cause a later build
         * stopped writing must not make the row look like a refusal or like a clearable block.
         */
        @JvmStatic
        fun fromStored(stored: String?): FailureCause? {
            if (stored == null) {
                return null
            }
            for (cause in FailureCause.values()) {
                if (cause.stored() == stored) {
                    return cause
                }
            }
            return null
        }
    }
}
