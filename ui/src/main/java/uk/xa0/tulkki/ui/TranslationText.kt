package uk.xa0.tulkki.ui

import androidx.annotation.StringRes

import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.TranslationFailures

/**
 * Tulkki's reasons, in words.
 *
 * <p>The same state is rendered in more than one place - the cover on a message, and the usage
 * screen's summary - and they must not drift apart: "no key" has to read like "no key" wherever it is
 * said, and a new reason must not be added to one of them only. One table, so there is one answer. It
 * is the only place that knows which string belongs to which state.
 */
object TranslationText {

    /** What a covered message says when it is not the plain "tap to translate" cover. */
    @JvmStatic
    @StringRes
    fun coverCaption(cover: DisplayedBody.Cover?): Int {
        if (cover == null) {
            return R.string.tulkki_untranslated_tap
        }
        return when (cover) {
            DisplayedBody.Cover.TRANSLATING -> R.string.tulkki_cover_translating
            DisplayedBody.Cover.CANNOT_TRANSLATE -> R.string.tulkki_cover_cannot
            DisplayedBody.Cover.NO_KEY -> R.string.tulkki_cover_no_key
            DisplayedBody.Cover.CAP_REACHED -> R.string.tulkki_cover_cap_reached
            DisplayedBody.Cover.NO_CREDIT -> R.string.tulkki_cover_no_credit
            DisplayedBody.Cover.UNREACHABLE -> R.string.tulkki_cover_unreachable
            DisplayedBody.Cover.REJECTED_KEY -> R.string.tulkki_cover_rejected_key
            DisplayedBody.Cover.FAILED -> R.string.tulkki_cover_failed
            // TAP and anything added later take the plain cover, exactly as the Java switch's
            // default did.
            else -> R.string.tulkki_untranslated_tap
        }
    }

    /**
     * Why a send is held, as the composer's bar says it: a sentence, not a phrase.
     *
     * <p>It is `ConversationFragment.holdMessage`'s own table
     * (`ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.java:4650-4668`), which is the only
     * place that decided these words until the Compose composer needed the same ones. It is
     * deliberately **not** [failureReason]: that one is the usage screen's phrase, "put after 'Last
     * failure:'", and a bar that drew it would say "no DeepSeek key is set" where the owner is owed
     * "Not sent: ... Add one in Tulkki's settings."
     */
    @JvmStatic
    @StringRes
    fun held(reason: HeldSend.HoldReason?): Int {
        if (reason == null) {
            return R.string.tulkki_hold_failed
        }
        return when (reason) {
            HeldSend.HoldReason.UNKNOWN_LANGUAGE -> R.string.tulkki_hold_unknown_language
            HeldSend.HoldReason.NO_KEY -> R.string.tulkki_hold_no_key
            HeldSend.HoldReason.CAP_REACHED -> R.string.tulkki_hold_cap_reached
            HeldSend.HoldReason.NO_CREDIT -> R.string.tulkki_hold_no_credit
            HeldSend.HoldReason.UNREACHABLE -> R.string.tulkki_hold_unreachable
            // Not "DeepSeek answered something we did not like": the key itself is the problem, and
            // that is something the owner can act on.
            HeldSend.HoldReason.REJECTED_KEY -> R.string.tulkki_hold_rejected_key
            // Nothing failed: the check accepted the answer on doubt and the send waits for the
            // owner's tap, so the bar may not say DeepSeek could not translate it.
            HeldSend.HoldReason.DOUBT -> R.string.tulkki_hold_doubt
            else -> R.string.tulkki_hold_failed
        }
    }

    /**
     * Why the last translation could not happen, for the usage screen. A phrase, not a sentence: it is
     * put after "Last failure:" and may be followed by DeepSeek's own words.
     */
    @JvmStatic
    @StringRes
    fun failureReason(reason: HeldSend.HoldReason?): Int {
        if (reason == null) {
            return R.string.tulkki_failure_failed
        }
        return when (reason) {
            HeldSend.HoldReason.NO_KEY -> R.string.tulkki_failure_no_key
            HeldSend.HoldReason.CAP_REACHED -> R.string.tulkki_failure_cap_reached
            HeldSend.HoldReason.NO_CREDIT -> R.string.tulkki_failure_no_credit
            HeldSend.HoldReason.UNREACHABLE -> R.string.tulkki_failure_unreachable
            HeldSend.HoldReason.REJECTED_KEY -> R.string.tulkki_failure_rejected_key
            HeldSend.HoldReason.DOUBT -> R.string.tulkki_failure_doubt
            // An unknown conversation language is a reason a *send* is held, never a received
            // message's failure; it still has to render as something rather than crash.
            else -> R.string.tulkki_failure_failed
        }
    }

    /**
     * The reason a failures row gives, which is a different question from [failureReason]'s `null`:
     * there, a missing reason is the newest failure not being this message's, and the bubble simply
     * says nothing; here the row exists because <em>this</em> message failed, so a reason the record
     * does not reach has to say so rather than borrow the last failure's words.
     */
    @JvmStatic
    @StringRes
    fun failureOrNotKept(reason: HeldSend.HoldReason?): Int {
        return if (reason == null) R.string.tulkki_failures_reason_not_kept else failureReason(reason)
    }

    /**
     * What the timestamp on a failures row is, in words. Every row has to say which of the two it is
     * showing, so a message's own time can never pass for a failure's.
     */
    @JvmStatic
    @StringRes
    fun failuresWhen(whenValue: TranslationFailures.When?): Int {
        if (whenValue == null) {
            return R.string.tulkki_failures_when_failed
        }
        return when (whenValue) {
            TranslationFailures.When.ARRIVED -> R.string.tulkki_failures_when_arrived
            TranslationFailures.When.SEND_FAILED -> R.string.tulkki_failures_when_send_failed
            TranslationFailures.When.WRITTEN -> R.string.tulkki_failures_when_written
            else -> R.string.tulkki_failures_when_failed
        }
    }

    /**
     * What happens next to a failures row, in words. Three states and not two, because a send is
     * neither retried by the app nor given up on: it is held until the owner sends it again, and the
     * row may not promise an attempt nobody made.
     */
    @JvmStatic
    @StringRes
    fun failuresNext(next: TranslationFailures.Next?): Int {
        if (next == null) {
            return R.string.tulkki_failures_gave_up
        }
        return when (next) {
            TranslationFailures.Next.RETRY -> R.string.tulkki_failures_will_retry
            TranslationFailures.Next.HELD -> R.string.tulkki_failures_held
            else -> R.string.tulkki_failures_gave_up
        }
    }
}
