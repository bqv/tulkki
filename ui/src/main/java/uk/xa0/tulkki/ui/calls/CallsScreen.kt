package uk.xa0.tulkki.ui.calls

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.stories.NavBadges
import uk.xa0.tulkki.ui.stories.NavIcons
import uk.xa0.tulkki.ui.stories.NavTab
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.widget.AvatarView

/**
 * The calls log, where `fragment_calls.xml` and `item_call.xml` were.
 *
 * <p>**The layouts and the adapter are gone.** `fragment_calls.xml` held the `RecyclerView` and the
 * `empty_view` "No calls yet" text, `item_call.xml` was the one row `adapter/CallsAdapter.kt`
 * inflated, and the adapter held the row's whole bind: the contact's avatar and display name, the
 * call-status icon and its colour, the preview line, the relative date, the optional account line and
 * the per-row "call again" popup fed by `menu/call_again_context.xml`. All four files are deleted.
 * The list is a [LazyColumn], the row is [CallRowItem], the empty state is one [Text], and the popup
 * is the row's own [DropdownMenu]. The tab bar, where `BottomNavigationView` plus
 * `menu/bottom_navigation_menu_calls.xml` were, is [CallNavBar] with the same four destinations, the
 * same `calls` tab selected and the same badges.
 *
 * <p>**What this file decides and what the host decides.** Nothing here reads a service, a database
 * or the clock: the host ([uk.xa0.tulkki.ui.CallsActivity]) resolves each row's avatar, name,
 * preview, date, status icon and account line and hands them over as a [CallRow], and this file draws
 * them and emits taps. The tab bar's eight icons are [NavIcons] handed in for the same reason - a
 * theme attribute belongs to the host's window, and a screenshot cell has no `Theme.Tulkki` under it.
 * [NavTab], [NavBadges] and [NavIcons] are the four-destination model the converted stories screen
 * already carries, and the calls screen is the same four destinations, so it reuses them rather than
 * defining a second set.
 *
 * <p>The row's own [Contact] doubles as its [uk.xa0.tulkki.libs.Avatarable] and as the destination of
 * a tap on the avatar or the name; it is `null` only in a screenshot cell, which cannot build a live
 * contact and draws the same `ic_person_24dp` plate the XML drew when no avatar was resolved.
 *
 * @param state the rows, the tab badges and whether the tab bar is shown
 * @param icons the tab bar's eight theme attributes, resolved by the host
 * @param onTab a tap on another tab; the host performs the navigation
 * @param onContact a tap on a row's avatar or name, which opens that contact's details
 * @param onCallAgain the row's "call again" choice: `true` for video, `false` for audio
 */
@Composable
fun CallsScreen(
    state: CallsScreenState,
    icons: NavIcons,
    onTab: (NavTab) -> Unit,
    onContact: (CallRow) -> Unit,
    onCallAgain: (CallRow, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.rows.isEmpty()) {
                // The deleted `updateViewStates`: no rows means the empty view instead of the list.
                Text(
                    text = stringResource(R.string.no_calls_yet),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows) { row -> CallRowItem(row, onContact, onCallAgain) }
                }
            }
        }
        if (state.showNavBar) {
            CallNavBar(selected = NavTab.CALLS, badges = state.badges, icons = icons, onSelect = onTab)
        }
    }
}

/**
 * Everything the screen draws, assembled by the host after a read.
 *
 * @param rows the calls, newest first, in display order
 * @param badges the four tab badges, read off the service by the host
 * @param showNavBar whether the tab bar is drawn at all (`show_nav_bar` and the intent's own extra)
 */
data class CallsScreenState(
    val rows: List<CallRow> = emptyList(),
    val badges: NavBadges = NavBadges(),
    val showNavBar: Boolean = false,
)

/**
 * One call, with every fact the deleted `CallsAdapter.CallViewHolder.bind` resolved before it was
 * drawn.
 *
 * @param message the persisted row this one was built from, handed back on a "call again" tap; the
 *     screen never reads it, and it is `null` in a screenshot cell
 * @param contact the row's contact, which is also its avatar and the target of a name/avatar tap; the
 *     screen draws the placeholder plate and no tap surface when it is `null`
 * @param name the contact's display name
 * @param info the preview line `UIHelper.getMessagePreview` answered, empty when there is no service
 * @param date the relative date `UIHelper.readableTimeDifference` answered
 * @param missed a received call that did not succeed, which is what paints the line red
 * @param icon the `UIHelper.rtpSessionStatusIcon` drawable for the call's direction and outcome
 * @param account the `show_own_accounts` line, or `null` when the preference is off
 * @param contactClickable whether a tap on the avatar or name opens the contact's details
 */
data class CallRow(
    val message: Message?,
    val contact: Contact?,
    val name: String,
    val info: String,
    val date: String,
    val missed: Boolean,
    @DrawableRes val icon: Int,
    val account: String?,
    val contactClickable: Boolean,
)

/**
 * One row, where `item_call.xml` was: the avatar at the start, the bold name over the status line
 * (icon, preview, dot, date) and the optional account line, and the "call again" button at the end.
 *
 * <p>The deleted `RelativeLayout`'s 12 dp padding and 16 dp gaps are kept. The status line's colour is
 * the adapter's own rule: `red_700` for a missed call, the theme's text colour otherwise, carried on
 * both the icon and the preview, with the 18 dp icon and its 6 dp gap where the XML's compound
 * drawable and its `setCompoundDrawablePadding` were.
 */
@Composable
private fun CallRowItem(
    row: CallRow,
    onContact: (CallRow) -> Unit,
    onCallAgain: (CallRow, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallAvatar(row, onContact)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lineColor =
                    if (row.missed) colorResource(R.color.red_700) else LocalContentColor.current
                Icon(
                    painter = painterResource(row.icon),
                    contentDescription = null,
                    tint = lineColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = row.info,
                    color = lineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier =
                        Modifier.size(2.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = row.date,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (row.account != null) {
                Text(
                    text = row.account,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        CallAgainButton(row, onCallAgain)
    }
}

/**
 * The row's avatar: an [AvatarView] in an [AndroidView], loaded the way the adapter loaded it, or the
 * placeholder the XML drew without a hosted view - which is also what keeps a screenshot cell free of
 * an `AndroidView`. The tap surface sits over it only when the contact has details to open, which is
 * the adapter's own `!contact.isSelf()` branch.
 */
@Composable
private fun CallAvatar(row: CallRow, onContact: (CallRow) -> Unit) {
    val size = dimensionResource(R.dimen.bubble_avatar_size)
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        val contact = row.contact
        if (contact == null) {
            Icon(
                painter = painterResource(R.drawable.ic_person_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AndroidView(
                factory = { context ->
                    AvatarView(context).also {
                        AvatarWorkerTask.loadAvatar(contact, it, R.dimen.bubble_avatar_size)
                    }
                },
                update = { view ->
                    AvatarWorkerTask.loadAvatar(contact, view, R.dimen.bubble_avatar_size)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (row.contactClickable) {
            Box(modifier = Modifier.fillMaxSize().clickable { onContact(row) })
        }
    }
}

/**
 * The end-of-row "call again" button, where the adapter's `ImageButton` + `PopupMenu` +
 * `menu/call_again_context.xml` were. The two items keep the deleted menu's labels, order and icons
 * (`setForceShowIcon(true)` was a Q-and-up platform detail; a Compose item draws its icon always).
 */
@Composable
private fun CallAgainButton(row: CallRow, onCallAgain: (CallRow, Boolean) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(
            onClick = { open = true },
            modifier = Modifier.padding(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_call_24dp),
                contentDescription = stringResource(R.string.make_call),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_call)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    open = false
                    onCallAgain(row, false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_call)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_videocam_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    open = false
                    onCallAgain(row, true)
                },
            )
        }
    }
}

/**
 * The four-tab bar, where `BottomNavigationView` + `bottom_navigation_menu_calls.xml` were: the same
 * destinations in the same order, the `calls` tab selected, and the same badges the deleted
 * `refreshUiReal` put on the view.
 *
 * <p>The height is the deleted `@dimen/nav_bar_height` rather than the component's own 80 dp, so the
 * bar's footprint is unchanged, and the component's navigation-bar insets are zeroed because the
 * chrome's `Scaffold` has already handed the content the space the system bars leave. The deleted
 * `onCreate`'s `setBackgroundColor(Color.TRANSPARENT)` is not repeated: the bar's own
 * `?colorSurfaceContainerLowest` is what remains, exactly as the converted stories screen kept it.
 */
@Composable
private fun CallNavBar(
    selected: NavTab,
    badges: NavBadges,
    icons: NavIcons,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.height(dimensionResource(R.dimen.nav_bar_height)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        CallTabItem(
            tab = NavTab.CHATS,
            selected = selected,
            label = stringResource(R.string.chats),
            hasBadge = badges.chats > 0,
            badgeCount = badges.chats,
            unselected = icons.chatsUnselected,
            selectedIcon = icons.chatsSelected,
            onSelect = onSelect,
        )
        CallTabItem(
            tab = NavTab.CALLS,
            selected = selected,
            label = stringResource(R.string.calls),
            hasBadge = badges.calls,
            badgeCount = 0,
            unselected = icons.callsUnselected,
            selectedIcon = icons.callsSelected,
            onSelect = onSelect,
        )
        CallTabItem(
            tab = NavTab.STORIES,
            selected = selected,
            label = stringResource(R.string.stories),
            hasBadge = badges.stories,
            badgeCount = 0,
            unselected = icons.storiesUnselected,
            selectedIcon = icons.storiesSelected,
            onSelect = onSelect,
        )
        CallTabItem(
            tab = NavTab.FEEDS,
            selected = selected,
            label = stringResource(R.string.feeds),
            hasBadge = badges.feeds,
            badgeCount = 0,
            unselected = icons.feedsUnselected,
            selectedIcon = icons.feedsSelected,
            onSelect = onSelect,
        )
    }
}

/**
 * One tab: the selected/unselected icon pair, the badge and the label.
 *
 * <p>It is a [RowScope] extension because M3's `NavigationBarItem` is one, so this helper has to
 * carry the receiver to call it at all.
 */
@Composable
private fun RowScope.CallTabItem(
    tab: NavTab,
    selected: NavTab,
    label: String,
    hasBadge: Boolean,
    badgeCount: Int,
    @DrawableRes unselected: Int,
    @DrawableRes selectedIcon: Int,
    onSelect: (NavTab) -> Unit,
) {
    val isSelected = tab == selected
    NavigationBarItem(
        selected = isSelected,
        onClick = { onSelect(tab) },
        icon = {
            BadgedBox(
                badge = {
                    if (hasBadge) {
                        // The chats tab drew the count; the other three drew a dot.
                        Badge { if (badgeCount > 0) Text(badgeCount.toString()) }
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (isSelected) selectedIcon else unselected),
                    contentDescription = null,
                )
            }
        },
        label = { Text(label) },
    )
}
