package uk.xa0.tulkki.data.messages

/**
 * One conversation row as `:ui` may see it (S5-6): immutable, public, and carrying the last-message
 * **pointer** rather than its text.
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §2.7 names the type and `Compose UI` §2.3
 * invariant 4 states the rule it obeys: "The conversation-list preview is a pointer to the row,
 * never a copy... `UiConversation` carries `lastMessageId` + `lastMessageAt`, and `preview` is
 * produced by the projector from the same `MessageSnapshot` the bubble uses." So the list read is
 * one query per emission and [lastMessageId] is an id, never a body: concealing the row conceals the
 * preview by construction, because there is no second copy to forget.
 *
 * <p>The fields are the stored ids and scalars at the width and the nullability the file has, for
 * the reason [MessageSnapshot] gives: the flags (unread, muted, archived) are the model's reading of
 * [status] and [attributes], and the projector is where that reading happens.
 *
 * <p>**`toString()` prints ids only**, for the reason [MessageSnapshot.toString] gives: a generated
 * `toString()` would print [name] and [attributes] into every log line.
 */
data class ConversationSnapshot(
    /** The local uuid: the conversation's own identity. */
    val id: String,
    /** The account that owns it; the foreign key that cascades when the account is deleted. */
    val accountId: String?,
    /** The display name, when the row has one. */
    val name: String?,
    /** The peer's JID, or null on a row the store has not resolved one for. */
    val jid: String?,
    /** The contact row this conversation is with, when there is one. */
    val contactUuid: String?,
    /** `Conversation.MODE_SINGLE` or `Conversation.MODE_MULTI`, as the row holds it. */
    val mode: Long?,
    /** The stored `status` scalar, as the row holds it. */
    val status: Long?,
    /** When the conversation was created, milliseconds since the epoch. */
    val created: Long?,
    /** The stored attributes JSON, as the row holds it; the projector decodes it. */
    val attributes: String?,
    /** The language detected from what the others write, before translation. */
    val detectedLanguage: String?,
    /** The language the owner set for this conversation, which wins over the detection. */
    val languageOverride: String?,
    /**
     * The doubt-hold **as the row holds it**: `null` is "this conversation never chose", `0` is off
     * and `1` is on. The shipped default - which is *on* - is not resolved here, and `null` must not
     * be folded into `0`: the rule that consults the value owns the meaning of "never chose".
     *
     * <p>It defaults to `null` for a construction outside this module: `:ui`'s
     * `ConversationProjectionTest` builds a snapshot with named arguments and predates the column, so
     * the absent answer - which is the honest one for a caller that never asked - keeps that call
     * site compiling. `asSnapshot` is the production construction and always passes it.
     */
    val doubtHold: Int? = null,
    /** The pointer: the newest row's id, or null on an empty conversation. */
    val lastMessageId: String?,
    /** The pointer's instant, the list's only denormalised scalar. */
    val lastMessageAt: Long?,
) {

    /** Ids only; see the class comment. */
    override fun toString(): String =
        "ConversationSnapshot(id=$id, accountId=$accountId, mode=$mode, " +
            "lastMessageId=$lastMessageId)"
}
