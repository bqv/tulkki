package uk.xa0.tulkki.ui.projection

import uk.xa0.tulkki.translation.HeldSend

/**
 * Whether a row of the owner's own is on its way, "Design: the Compose UI" §2.2's cases, decided by
 * `Message.STATUS_*` as §2.2.1 "The missing definitions" #2 tabulates.
 *
 * <p>**[Failed] is not here and is the row's one hole.** §2.2 names `Failed(reason)`, and §2.2.1 #2
 * marks its reason **deferred**: the row stores only the free-form `errorMsg` String, with one
 * cancelled sentinel and one `file-too-large` convention, and there is no failure-name vocabulary -
 * `HoldReason` is not it. A type with a hole that is named is better than one filled with a
 * plausible guess, so the case arrives with the vocabulary that decides it.
 *
 * <p>**Held is not Failed** (§2.2.1 #2): held means nothing left the device, blocked pre-wire by the
 * translation policy; failed means the stanza went out and an error came back. The status alone
 * cannot tell Tulkki's held row from upstream's in-flight one, so the projector must ask
 * `OutgoingTranslation.isHeldForTranslation` rather than read `STATUS_WAITING` - which is why this
 * type carries both cases and neither is derived from the other.
 */
sealed interface UiDeliveryState {

    /** `STATUS_UNSEND`: written, not yet handed to the wire. */
    data object Sending : UiDeliveryState

    /** `STATUS_SEND`: handed to the wire. */
    data object Sent : UiDeliveryState

    /** `STATUS_SEND_RECEIVED`: the peer's receipt. */
    data object Delivered : UiDeliveryState

    /** `STATUS_SEND_DISPLAYED`: the peer's display marker. */
    data object Displayed : UiDeliveryState

    /** `STATUS_WAITING` **and** `isHeldForTranslation`: nothing left the device, and [reason] says why. */
    data class Held(val reason: HeldSend.HoldReason) : UiDeliveryState

    /**
     * A peer that does not tell whether it received the row - not established, and §2.2.1 #2 marks it
     * deferred because nothing records a peer's receipt capability and every generated stanza
     * requests one. The case exists; the surface that waits is the bubble's status line.
     */
    data object UnknownCapability : UiDeliveryState
}
