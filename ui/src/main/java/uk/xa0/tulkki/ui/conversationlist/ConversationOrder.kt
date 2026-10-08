package uk.xa0.tulkki.ui.conversationlist

import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.ui.projection.UiConversation

/**
 * The list's order and the suggestion a caller takes from it, docs/MIGRATION.md "Design: the Compose UI"
 * §3.5's list, derived over snapshots.
 *
 * <p>**Today both are the service's, over entities.** `ConversationListFragment.refresh()` calls
 * `activity.populateWithOrderedConversationList(...)`, which is `XmppActivity`'s forward to
 * `XmppConnectionService.populateWithOrderedConversationList(List<ConversationRef>)`
 * (`ui/src/main/java/uk/xa0/tulkki/ui/XmppActivity.java:859`) - the island sorts its own entity list - and
 * `ConversationListFragment.getSuggestion` answers the activity out of that same list
 * (`ConversationListActivity:520`, `:1778`). A list built from `ConversationSnapshot`s has neither, so
 * the three keys of `Conversation.compareTo`
 * (`data/src/main/java/uk/xa0/tulkki/data/model/Conversation.java:1040-1049`) are re-derived here, once,
 * with cells.
 *
 * <p>**`sortableTime` is the last message's `timeReceived`, not the pointer's instant.** The tree's
 * `getSortableTime` (`:1051-1066`) reads `messages.get(size - 1).getTimeReceived()`, and the draft's own
 * timestamp beats it when there is a draft; with no messages at all it is `max(created,
 * lastClearHistory)`. `ConversationSnapshot.lastMessageAt` is the **pointer's** instant from the list
 * query and is not that number - the cell that holds the two apart is the point of this file.
 *
 * <p>**What is not in the read model, and therefore comes in as an argument.** The draft's instant is a
 * message row the snapshot read does not name, and `last_clear_history` is an attribute whose reader is
 * `MamReference` - an island type `:ui` may not import without growing `ui-reaches-island` - so the host
 * answers both. Neither is guessed here.
 */
object ConversationOrder {

    /**
     * One conversation's sortable instant: the tree's `getSortableTime`, key for key.
     *
     * @param lastClearHistoryAt the `last_clear_history` instant, or 0: the host reads it, because its
     *     reader is an island type.
     * @param draftAt the draft's instant, or `null` when the conversation holds none.
     */
    @JvmStatic
    fun sortableTime(
        conversation: ConversationSnapshot,
        lastMessage: MessageSnapshot?,
        lastClearHistoryAt: Long,
        draftAt: Long?,
    ): Long {
        val messageTime =
            if (lastMessage == null) {
                maxOf(conversation.created ?: 0L, lastClearHistoryAt)
            } else {
                // The tree's own number: the last row's *received* time, falling back to when it was
                // sent - never the row's own instant, which the entity does not consult either.
                lastMessage.timeReceived ?: lastMessage.timeSent ?: 0L
            }
        return if (draftAt == null) messageTime else maxOf(messageTime, draftAt)
    }

    /**
     * The rows in `Conversation.compareTo`'s order: the **pinned note-to-self** first, then every pinned
     * conversation, then the rest by sortable time, newest first. A row whose time the caller did not
     * supply sorts as zero rather than crashing the list.
     */
    @JvmStatic
    fun sort(rows: List<UiConversation>, sortableTimes: Map<String, Long>): List<UiConversation> =
        rows.sortedWith(
            compareByDescending<UiConversation> { it.pinned && it.withSelf }
                .thenByDescending { it.pinned }
                .thenByDescending { sortableTimes[it.id.uuid] ?: 0L }
        )

    /**
     * The rows' own local uuids, in the order they are drawn - what [suggestion] is asked about, and the
     * one thing a Java host cannot read off [UiConversation] itself.
     *
     * <p>**Why this is a function and not a property read at the call site.** `UiConversation.id` is a
     * `@JvmInline value class`, so its JVM getter is name-mangled (`getId-ZsSADWg`) and no Java caller can
     * spell it. The host that draws the list *is* that Java caller, and it is the one the activity asks
     * for a suggestion: without this it would hold the rows in one order and hand [suggestion] another -
     * the snapshots' raw read order - which is the sort silently not applying. The string here is the
     * same one [ConversationListEvents] already names a row by, so the screen's key, the host's
     * vocabulary and this list are one identity rather than three.
     */
    @JvmStatic
    fun uuids(rows: List<UiConversation>): List<String> = rows.map { it.id.uuid }

    /**
     * The conversation a caller should offer next, by its local uuid - the fragment's own answer without
     * handing an entity out. The tree's two overloads are one rule: the first row that is not [excluded],
     * where the first call site excludes nothing and the second excludes the conversation the caller
     * already has; the swipe's own call excludes the row being swiped away.
     */
    @JvmStatic
    fun suggestion(uuids: List<String>, excluded: String?): String? =
        uuids.firstOrNull { uuid -> uuid != excluded }
}
