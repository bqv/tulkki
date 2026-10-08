package uk.xa0.tulkki.ui.projection

/**
 * One row of the conversation list, "Design: the Compose UI" §2.2's declaration, field for field.
 *
 * <p>§2.2's contract is the field list and §2.2.1 "The missing definitions" #9 confirms its sources:
 * the scalars are `ConversationSnapshot`'s. The rules that are not visible in the fields are §2.3
 * invariant 4's, and they are why there is no text here beyond [name] and [preview]: [lastMessageId]
 * is a **pointer** to the row, never a copy of its body - "Denormalise scalars freely; never
 * denormalise text" - so concealing the row conceals the preview by construction. [lastMessageAt] is
 * the one denormalised scalar, and it exists for sorting.
 *
 * <p>[preview] is produced by the projector from the same `MessageSnapshot` the bubble uses, which is
 * why it is a [UiPreview] and not a `String`, and [language] carries the pair every surface that
 * names a language must name. [translation] is `LanguageUnknown` for a conversation whose language
 * nobody has read yet - a state, never a second screen.
 *
 * <p>**Three fields go beyond §2.2's list, and each is recorded rather than silent.** [kind] answers
 * §3.5's `Groups` filter (`mode == MODE_MULTI`) and the long-press menu's wording, which asks
 * `MucOptions.isPrivateAndNonAnonymous()` for a room's details and archive titles - one mode-derived
 * value, so the filter and the menu cannot disagree about what a conversation is, with [group] a pure
 * derivation of it and no field of its own. [pinned] is the `pinned_on_top` attribute the tree sorts on
 * and the menu's pin entry reads. [withSelf] is `Conversation.withSelf`, the note-to-self row the menu
 * hides the contact's details on - a property of the **contact**, so it arrives through the projector's
 * `PerProcess` seam exactly as [unread] does. [ongoingCall] is that same seam's live answer: the menu
 * must not offer a call that is not there, and a screen drawing one row cannot ask a service.
 */
data class UiConversation(
    val id: ConversationId,
    val name: String,
    val jid: String,
    /** A POINTER to the row. Never the text. */
    val lastMessageId: MessageId?,
    /** The only denormalised scalar, for sorting. */
    val lastMessageAt: Long,
    val preview: UiPreview,
    val unread: Int,
    val muted: Boolean,
    val archived: Boolean,
    /** The `pinned_on_top` attribute: the tree sorts on it and the menu's pin entry reads it. */
    val pinned: Boolean,
    /** What kind of conversation this is: the snapshot's `mode` and its two room attributes. */
    val kind: ConversationKind,
    /** Whether this is the owner's own note-to-self: a contact fact, from the seam. */
    val withSelf: Boolean,
    /** Whether a call with this conversation's contact is going on: the call manager's own state. */
    val ongoingCall: Boolean,
    /**
     * The row's own clock, already worded: the newest message's instant, or the draft's when the owner is
     * still writing one, run through [ConversationRowTime]. Empty on a caller that draws no time.
     */
    val time: String = "",
    /** The presence dot, or [UiPresence.UNKNOWN] - which the row draws as nothing at all. */
    val presence: UiPresence = UiPresence.UNKNOWN,
    /**
     * Who sent the row's newest message, when the row says so: a group's sender name, or "me" for what the
     * owner sent. `null` draws no name, which is the tree's answer for a one-to-one and for a status row.
     */
    val sender: String? = null,
    /** The delivery tick's `@DrawableRes`, or `null` for the statuses the tree marked with nothing. */
    val tick: Int? = null,
    /** The notification mark: [UiNotification.NONE] is the state the tree drew nothing for. */
    val notification: UiNotification = UiNotification.NONE,
    /**
     * The account's own address, or `null` when there is nothing to tell apart - which is
     * `UiAccountLine`'s rule and not a preference.
     */
    val account: String? = null,
    val language: UiLanguagePair,
    val translation: UiTranslationMode,
) {

    /** §3.5's `Groups` filter: a derivation of [kind], so the two can never disagree. */
    val group: Boolean
        get() = kind != ConversationKind.ONE_TO_ONE
}
