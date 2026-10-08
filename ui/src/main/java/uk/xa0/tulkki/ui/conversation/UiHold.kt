package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.LanguageCheck

/**
 * Why the composer's bar says a send has not gone anywhere: the two shapes the send path produces.
 *
 * <p>**Two shapes, because the two sentences come from two different places and one of them is not a
 * `HoldReason`.** [Reason] is the ordinary held send - no key, no credit, the cap reached, the API
 * unreachable, the language unknown - and its sentence is `TranslationText.held`'s own table. [Doubt]
 * is item 16's third kind: the check accepted the answer *on doubt* and the send path held it for the
 * owner's tap, and its sentence is the kind's own `getBecause()`, which is the contract `:translation`
 * measured and this surface is forbidden to re-derive (`UiComposer`'s KDoc carries it).
 *
 * <p>A sealed type rather than two optional fields, so "held on a reason and on a doubt at once" is
 * not representable. It carries the **kind** rather than a worded sentence because the two doubt kinds
 * want opposite taps - a doubtful language may be sent as it stands, an echo may not, and sending it
 * would send the original - and the bar shares the screen with the bubble whose tap decides which.
 */
sealed interface UiHold {

    /** The send path's own reason, in [HeldSend.HoldReason]'s vocabulary. */
    data class Reason(val reason: HeldSend.HoldReason) : UiHold

    /** Item 16's accepted-on-doubt answer, whose bar sentence is the kind's own. */
    data class Doubt(val kind: LanguageCheck.Doubt) : UiHold
}
