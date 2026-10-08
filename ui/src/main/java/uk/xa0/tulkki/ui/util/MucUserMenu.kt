package uk.xa0.tulkki.ui.util

import androidx.annotation.StringRes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * One live entry of a participant's long-press menu: the verb, its label and whether it can be
 * chosen.
 *
 * <p>The deleted `muc_details_context.xml` declared every one of its items `android:visible="false"`
 * and `MucDetailsContextMenuHelper.configureMucDetailsContextMenu` turned some of them on. This is
 * the turned-on set, in the menu's own order, read without a `Menu` - the Compose twin
 * [MucDetailsContextMenuHelper.visibleEntries] builds.
 */
data class MucDetailsEntry(
    val action: MucDetailsAction,
    @StringRes val title: Int,
    val enabled: Boolean = true,
)

/**
 * The verbs `muc_details_context.xml`'s items named, as values rather than the menu's `@+id`
 * symbols. The ids live in the menu file and go with it; the dispatch
 * ([MucDetailsContextMenuHelper.onMucDetailsAction]) keys off these instead.
 */
enum class MucDetailsAction {
    START_CONVERSATION,
    SHOW_AVATAR,
    CONTACT_DETAILS,
    BLOCK_AVATAR,
    MUTE_PARTICIPANT,
    UNMUTE_PARTICIPANT,
    INVITE,
    SEND_PRIVATE_MESSAGE,
    SHARE_CONTACT_DETAILS,
    MANAGE_PERMISSIONS,
    REMOVE_FROM_ROOM,
}

/**
 * The open menu of one participant: which row opened it (the row's own key) and the entries the host
 * resolved for that participant.
 */
data class MucUserMenu(
    val key: String,
    val entries: List<MucDetailsEntry>,
)

/**
 * The participant menu, drawn where its row is: a `DropdownMenu` carrying the host's entries, in the
 * host's order, each item disabled exactly when the entry says so.
 *
 * <p>It decides nothing. Which entries exist and what a verb does are the host's
 * ([MucDetailsContextMenuHelper]); this only draws them and reports the one chosen.
 */
@Composable
fun MucUserDropdownMenu(
    menu: MucUserMenu,
    onDismiss: () -> Unit,
    onSelected: (MucDetailsAction) -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        for (entry in menu.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(entry.title)) },
                enabled = entry.enabled,
                onClick = {
                    onDismiss()
                    onSelected(entry.action)
                },
            )
        }
    }
}
