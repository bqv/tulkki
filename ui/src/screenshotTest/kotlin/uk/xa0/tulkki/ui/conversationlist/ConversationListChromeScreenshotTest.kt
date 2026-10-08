package uk.xa0.tulkki.ui.conversationlist

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The conversation-list chrome's cells: the bar with its overflow over the four-destination bar, and
 * the drawer's sheet - each in both themes, which is §7.2's "both themes for every screen" and the
 * same pair the other converted families carry.
 *
 * <p>**What these pin.** The bar's title block (a title over its subtitle), the drawer handle it
 * leads with, the `ic_more_horiz_24dp` overflow and its items; the four destinations in the deleted
 * `bottom_navigation_menu_chat.xml`'s order with the same selected tab and the same four badges (a
 * count on `chats`, a dot on the other three); and, in the sheet, the account block with its active
 * profile and its `Manage accounts`/`Add account` rows, the four filters with their counts, one tag
 * and the three sticky rows - which is the deleted MaterialDrawer's content, row for row.
 *
 * <p>**What these cannot pin, and why.** The two-pane arrangement: its two panes are the `FrameLayout`
 * containers the fragment manager adds `main_fragment` and `secondary_fragment` into, created by the
 * composition, and a preview has no `FragmentActivity` to add anything to - so a reference of it would
 * be two empty boxes and would pin nothing. The avatars: the account rows' images are a live
 * `AvatarService` `Drawable`, and a cell has no account to resolve one from, so the plate the row
 * draws without one is what a reference shows. And the list's own rows: the screen has its own six
 * cells (`ConversationListScreenScreenshotTest`), and the box under the bar here is a plain
 * placeholder so this file pins the furniture and not a second copy of the rows.
 */
@PreviewTest
@Preview(name = "conversation-list-chrome-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 720)
@Composable
fun ConversationListChromeDarkScreenshot() = Chrome(darkTheme = true)

@PreviewTest
@Preview(name = "conversation-list-chrome-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 720)
@Composable
fun ConversationListChromeLightScreenshot() = Chrome(darkTheme = false)

@PreviewTest
@Preview(name = "conversation-list-drawer-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 720)
@Composable
fun ConversationListDrawerDarkScreenshot() = Drawer(darkTheme = true)

@PreviewTest
@Preview(name = "conversation-list-drawer-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 720)
@Composable
fun ConversationListDrawerLightScreenshot() = Drawer(darkTheme = false)

/** The bar over the four-destination bar, with the bar's own overflow. */
@Composable
private fun Chrome(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        ConversationListChrome(
            state =
                ConversationListChromeState(
                    title = "Mikko",
                    subtitle = "typing…",
                    subtitleEmphasis = true,
                    leading = ConversationLeading.DRAWER,
                    drawerEnabled = true,
                    showNavBar = true,
                    tab = ConversationNavTab.CHATS,
                    badges = ConversationNavBadges(chats = 3, calls = true, stories = true, feeds = true),
                ),
            drawer = drawerState(),
            avatar = null,
            menu =
                listOf(
                    ChromeMenuItem("Note to self") {},
                    ChromeMenuItem("Feeds") {},
                    ChromeMenuItem("Media gallery") {},
                    ChromeMenuItem("Settings") {},
                ),
            onUp = {},
            onDrawerItem = {},
            onDrawerItemLongClick = {},
            onAccount = { _, _ -> },
            onAccountAvatar = {},
            onTab = {},
            onTitle = {},
        ) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

/** The drawer's sheet. A cell cannot open a `ModalNavigationDrawer`, so it renders the sheet itself. */
@Composable
private fun Drawer(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        ConversationDrawerSheet(
            drawer = drawerState(),
            onItem = {},
            onItemLongClick = {},
            onAccount = { _, _ -> },
            onAccountAvatar = {},
        )
    }
}

/**
 * The drawer's content as the activity assembles it: the active profile over the other profiles and
 * the two setting rows, then the filters with the same counts the deleted `refreshUiReal` computed,
 * one tag, and the three sticky rows in the order the deleted `addStickyDrawerItems` listed them.
 */
private fun drawerState(): ConversationDrawerState =
    ConversationDrawerState(
        profiles =
            listOf(
                DrawerProfile(
                    id = 100L,
                    name = "Mikko",
                    description = "mikko@example.org",
                    avatar = null,
                    badge = 2,
                    selected = true,
                ),
                DrawerProfile(
                    id = 101L,
                    name = "Sari",
                    description = "sari@example.org",
                    avatar = null,
                    badge = 0,
                    selected = false,
                ),
                DrawerProfile(
                    id = 4L,
                    name = "Manage accounts",
                    description = "",
                    avatar = null,
                    badge = 0,
                    selected = false,
                ),
                DrawerProfile(
                    id = 5L,
                    name = "Add account",
                    description = "",
                    avatar = null,
                    badge = 0,
                    selected = false,
                ),
            ),
        items =
            listOf(
                DrawerEntry(id = 1L, label = "All chats", icon = R.drawable.ic_chat_24dp, selected = false),
                DrawerEntry(id = 2L, label = "Unread chats", icon = R.drawable.chat_unread_24dp, badge = 7, selected = true),
                DrawerEntry(id = 3L, label = "Direct messages", icon = R.drawable.ic_person_24dp, badge = 4, selected = false),
                DrawerEntry(id = 7L, label = "Channels", icon = R.drawable.ic_group_24dp, badge = 3, selected = false),
            ),
        requests =
            listOf(
                DrawerEntry(id = 8L, label = "Chat requests", icon = R.drawable.ic_person_add_24dp, badge = 1, selected = false),
            ),
        tags = listOf(DrawerEntry(id = 1000L, label = "Work", badge = 2, selected = false)),
        sticky =
            listOf(
                DrawerEntry(
                    id = 17L,
                    label = "Tulkki",
                    description = "Translation",
                    icon = R.drawable.ic_tulkki_translate_24dp,
                ),
                DrawerEntry(id = 16L, label = "Media gallery", icon = R.drawable.ic_image_24dp),
                DrawerEntry(id = 9L, label = "Settings", icon = R.drawable.ic_settings_24dp),
            ),
    )
