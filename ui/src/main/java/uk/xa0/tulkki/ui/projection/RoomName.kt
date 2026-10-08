package uk.xa0.tulkki.ui.projection

/**
 * A room's four name candidates, in the order `Conversation.getName` tries them
 * (`data/src/main/java/uk/xa0/tulkki/data/model/Conversation.java:1180`).
 *
 * <p>**Why the seam carries four strings and not the composed name.** The tree's chain is
 * `mucOptions.getName()`, then the room's subject, then `bookmark.getBookmarkName()`, then
 * `mucOptions.createNameFromParticipants()`, and only then the JID - and every one of the four lives on
 * the running room or its bookmark, not on a column, which is why a room whose name nobody ever wrote into
 * `conversations.name` arrives blank and the row fell back to an address. `Conversation.getName` does not
 * read that column for a room at all. The **facts** come from the host, where the room is reachable; the
 * **order** is [ConversationProjection]'s, because that is a rule, and rules get cells.
 *
 * <p>The tree's `printableValue` guards each candidate: blank is not a name, and the bookmark's own test
 * additionally refuses one that looks like an address. Only the blank half is here, because telling an
 * address from a name needs the island's `Jid` parser, which a projection may not name.
 */
class RoomName(
    val live: String?,
    val subject: String?,
    val bookmark: String?,
    val participants: String?,
) {

    /** The first candidate that is a name at all, or `null` when the room offers none. */
    fun firstPrintable(): String? =
        listOf(live, subject, bookmark, participants).firstOrNull { !it.isNullOrBlank() }
}
