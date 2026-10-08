package uk.xa0.tulkki.ui.conversationlist

import androidx.annotation.StringRes
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.ConversationKind
import uk.xa0.tulkki.ui.projection.UiConversation

/**
 * What a long press on a conversation row offers, one value per entry of the tree's own context menu.
 *
 * <p>`menu/conversation_context.xml` is the source: `action_toggle_pinned`, `action_mute` /
 * `action_unmute`, `action_ongoing_call`, `action_contact_details` / `action_muc_details`,
 * `action_block_avatar` and `action_archive`; the Java `ConversationListFragment.onCreateContextMenu`
 * (`ui/src/main/java/uk/xa0/tulkki/ui/ConversationListFragment.java:386-433`) decided each entry's
 * visibility and wording, and that decision now lives in `ConversationMenu.entries`/`label` below,
 * performed by the Kotlin `ConversationListFragment.kt`. This enum is those decisions as values, so the
 * screen can draw the menu and the host can perform it without either of them re-reading the
 * conversation.
 *
 * <p>The pin and mute entries are the two the tree splits into a pair, and it shows exactly one of each:
 * the value is the one that will happen, not a toggle the host has to re-decide.
 */
enum class ConversationAction {
    /** `action_toggle_pinned`, `add_to_favorites`. */
    PIN,

    /** `action_toggle_pinned`, `remove_from_favorites`. */
    UNPIN,

    /** `action_mute`, `disable_notifications`. */
    MUTE,

    /** `action_unmute`, `enable_notifications`. */
    UNMUTE,

    /** `action_ongoing_call`: only while a call with that contact is running. */
    ONGOING_CALL,

    /** `action_contact_details`: a one-to-one that is not the owner's own note-to-self. */
    CONTACT_DETAILS,

    /** `action_muc_details`: a private, non-anonymous room. */
    MUC_DETAILS,

    /** `action_muc_details` titled `channel_details`: every other room. */
    CHANNEL_DETAILS,

    /** `action_block_avatar`: every row, as the fragment leaves it alone. */
    BLOCK_AVATAR,

    /** `action_archive` on a one-to-one, `action_archive_chat`. */
    ARCHIVE_CHAT,

    /** `action_archive` on a private, non-anonymous room, `leave_group`. */
    LEAVE_GROUP,

    /** `action_archive` on a channel, `action_end_conversation_channel`. */
    END_CHANNEL,
}

/**
 * The menu's two decisions, as one table: **which** entries a row offers, and what each says.
 *
 * <p>[entries] is the fragment's own order and rules - the menu's `orderInCategory` runs pin (10), mute
 * (20/21), ongoing call (30), details (40), block avatar (50), archive (60), and every visibility test is
 * the fragment's: the ongoing call only for a one-to-one call in progress, the contact's details on a
 * one-to-one that is not the note-to-self, a room's details either way, and the archive entry's wording
 * by the room's kind. Nothing here reads a conversation: the row and the one service fact are the
 * arguments.
 */
object ConversationMenu {

    /** The entries this row offers, in the order the tree's menu lists them. */
    fun entries(row: UiConversation, ongoingCall: Boolean): List<ConversationAction> {
        val entries = ArrayList<ConversationAction>()
        entries.add(if (row.pinned) ConversationAction.UNPIN else ConversationAction.PIN)
        entries.add(if (row.muted) ConversationAction.UNMUTE else ConversationAction.MUTE)
        when (row.kind) {
            ConversationKind.ONE_TO_ONE -> {
                if (ongoingCall) {
                    entries.add(ConversationAction.ONGOING_CALL)
                }
                if (!row.withSelf) {
                    entries.add(ConversationAction.CONTACT_DETAILS)
                }
            }
            ConversationKind.GROUP -> entries.add(ConversationAction.MUC_DETAILS)
            ConversationKind.CHANNEL -> entries.add(ConversationAction.CHANNEL_DETAILS)
        }
        entries.add(ConversationAction.BLOCK_AVATAR)
        entries.add(archive(row.kind))
        return entries
    }

    /**
     * The third of the archive entry the tree swaps by kind. It is also what a swipe means: the row
     * leaves the list either way, and only what the owner reads about it changes.
     */
    fun archive(kind: ConversationKind): ConversationAction =
        when (kind) {
            ConversationKind.ONE_TO_ONE -> ConversationAction.ARCHIVE_CHAT
            ConversationKind.GROUP -> ConversationAction.LEAVE_GROUP
            ConversationKind.CHANNEL -> ConversationAction.END_CHANNEL
        }

    /** One entry's own words, the strings the tree's menu already used. */
    @StringRes
    fun label(action: ConversationAction): Int =
        when (action) {
            ConversationAction.PIN -> R.string.add_to_favorites
            ConversationAction.UNPIN -> R.string.remove_from_favorites
            ConversationAction.MUTE -> R.string.disable_notifications
            ConversationAction.UNMUTE -> R.string.enable_notifications
            ConversationAction.ONGOING_CALL -> R.string.return_to_ongoing_call
            ConversationAction.CONTACT_DETAILS -> R.string.action_contact_details
            ConversationAction.MUC_DETAILS -> R.string.action_muc_details
            ConversationAction.CHANNEL_DETAILS -> R.string.channel_details
            ConversationAction.BLOCK_AVATAR -> R.string.block_avatar
            ConversationAction.ARCHIVE_CHAT -> R.string.action_archive_chat
            ConversationAction.LEAVE_GROUP -> R.string.leave_group
            ConversationAction.END_CHANNEL -> R.string.action_end_conversation_channel
        }
}
