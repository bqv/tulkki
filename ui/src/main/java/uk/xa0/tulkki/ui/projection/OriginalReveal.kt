package uk.xa0.tulkki.ui.projection

import uk.xa0.tulkki.translation.HeldSend

/**
 * Item 17's decision five, as the projector's gate: **when may a received message offer its own
 * original, blurred and tap-revealed?**
 *
 * <p>It is the second of the two owner-approved exceptions to "the original is stored, never
 * displayed" (the first is the English row), and the design states its deciding fact exactly:
 * "**failure** - translation was needed and did not happen - never the language it is in; never a
 * pending message, never a setting, never on open, no timer".
 *
 * <p>**The failure must be terminal**, which is the reading the coordinator confirmed: translation was
 * needed, did not happen, and is not about to happen on its own. That is why the four causes the app
 * itself calls *clearable* are excluded - a key entered, credit present, the cap rolled over or raised
 * each re-enqueues the row by itself (`FailureCause.clearable`), so the owner's tap on the original
 * would be answering a question the app is already going to answer. The one reason left is
 * [HeldSend.HoldReason.FAILED] - "DeepSeek answered, and the answer was not usable" - whose remedy the
 * app's own taxonomy names as "the owner's tap rather than any of the clearing events".
 *
 * <p>**No setting is consulted, and this class cannot consult one.** It takes the row's direction, the
 * fact that the translation is needed and not drawn, and the reason the store recorded for this very
 * row - all row facts. That is the shape [uk.xa0.tulkki.translation.SecondHalf] and
 * [uk.xa0.tulkki.translation.EnglishRow] already have, and it is what makes the guarantee rewritable:
 * the only path that turns another person's original into readable text is this gate, and no flag
 * combination, setting, timer or on-open can reach it.
 *
 * <p>**Two holes, named rather than guessed at.** A row whose answer the local check *refused* is the
 * clearest dead end of all, and it is not reachable here: the queue stores that as
 * `FailureCause.CHECK_REFUSED` in a `:data` column the snapshot does not carry, and a refusal leaves no
 * [HeldSend.HoldReason], so the projector cannot tell it from a row never attempted. And `asking` - the
 * tap in flight - is not carried by the projector today either (the same hole `top`'s own comment
 * names), so a row being re-asked is excluded by its *reason* rather than by the request's state: a
 * fresh attempt clears the recorded reason, which is why the gate reads it at all.
 */
object OriginalReveal {

    /**
     * Whether this row's concealed original may be offered.
     *
     * @param direction which way the row travelled: only somebody else's original is the exception,
     *     and the owner's own half is the readable side of `SecondHalf`'s ordinary rule
     * @param needsTranslation the row is covered - translation was needed and is not drawn
     * @param reason the reason the store recorded for **this** row, or `null` when there is none
     */
    @JvmStatic
    fun eligible(direction: Direction, needsTranslation: Boolean, reason: HeldSend.HoldReason?): Boolean =
        direction == Direction.INCOMING &&
            needsTranslation &&
            reason == HeldSend.HoldReason.FAILED
}
