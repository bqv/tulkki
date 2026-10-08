package uk.xa0.tulkki.ui.conversation

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * One row of a conversation popup: the id the fragment's own dispatcher answers, and the words the
 * owner reads. The words are already resolved by the host, which is where the menu's `@string/...`
 * ids live, so this file names no string of its own and a row cannot draw a sentence the fragment did
 * not choose.
 */
data class UiMenuItem(
    /** The id the host's own `onConversationMenuSelected` answers. */
    val id: Int,
    /** The row's words. */
    val label: String,
)

/**
 * A popup the conversation asked for - the room occupant's moderation rows, a one-to-one contact's
 * details and QR code, the owner's account menu, or the block submenu - as one value: the visible
 * rows, in the order the deleted menus declared them, where they hang, and what a row does.
 *
 * <p>[offset] is the anchor view's own corner, in dp from the host view's top-left, which is the
 * placement the deleted `PopupMenu(hostActivity, view)` had. It is carried as a value rather than
 * computed here because only the fragment holds the view that was asked for: a Compose row has no
 * view to hand across (the recorded gap the avatar long-press names).
 *
 * <p>[onSelected] is the popup's own dispatcher and not one shared table, because two of the four
 * menus answer with facts only their caller has: the contact-details row names the *message* it was
 * opened on, which is a local of the long-press that opened the menu. The row's id is the deleted
 * `MenuItem.getItemId()`, and the body behind it is the same one the deleted
 * `setOnMenuItemClickListener` ran.
 */
class UiRowMenu(
    val items: List<UiMenuItem>,
    val offset: DpOffset = DpOffset(0.dp, 0.dp),
    val onSelected: (Int) -> Unit = {},
)

/**
 * The conversation's popups, in Compose: one `DropdownMenu` for whatever [UiRowMenu] is open, drawn
 * where its anchor was.
 *
 * <p>`null` is no popup at all, which is the ordinary state, and a menu with no visible rows composes
 * nothing - the deleted `PopupMenu.show()` on an empty menu drew an empty box, so nothing is lost by
 * not drawing at all.
 *
 * <p>It decides nothing: the rows were assembled by the fragment (the same visibility conditions the
 * deleted `findItem(...).setVisible(...)` calls applied), and a tap reports the row's id to the
 * popup's own dispatcher after dismissing the menu, exactly as the framework's popup closed before it
 * ran the listener. The concealment rules are untouched - no row here draws a message body, a quote or
 * an original; the menus are verbs about a conversation, and the row they are opened from is only
 * their anchor.
 */
@Composable
fun ConversationRowMenu(
    menu: UiRowMenu?,
    onDismiss: () -> Unit,
) {
    if (menu == null || menu.items.isEmpty()) {
        return
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = menu.offset,
    ) {
        for (item in menu.items) {
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = {
                    onDismiss()
                    menu.onSelected(item.id)
                },
            )
        }
    }
}
