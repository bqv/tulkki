package uk.xa0.tulkki.ui.stories

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.conversationlist.AvatarShape

/**
 * The stories list, and the tab bar the deleted `activity_stories.xml` carried.
 *
 * <p>**The layout is gone.** `activity_stories.xml` held a toolbar, a `stories_list`, a `placeholder`,
 * a `fab_add_story` and a `BottomNavigationView` bound to `bottom_navigation_menu_stories.xml`; both
 * XML files are deleted. The bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, the list is [LazyColumn], the row is [StoryRowItem]
 * where `list_item_story.xml` was, the empty state is one [Text] where `placeholder` was, and the four
 * tabs are [StoryNavBar] in Compose. `list_item_story.xml` and the bottom-navigation menu went with
 * their files.
 *
 * <p>**What this file decides and what the host decides.** The screen draws [StoriesScreenState] and
 * emits taps: no service, no preference and no clock is read here, so the whole screen is reachable
 * from a JVM cell with literal rows. Every visibility rule the deleted `refresh()` had is here and
 * nowhere else - no online account and no row means the explainer instead of the list, an online
 * account means the add button, a row list means the list - which is why the state carries
 * [StoriesScreenState.hasOnlineAccounts] rather than the screen reading the account registry.
 *
 * <p>The avatar and the preview are [Drawable]/[ImageBitmap] by the time they reach a row, because
 * the host resolves them off the main thread - the same split
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListScreen] makes through
 * [uk.xa0.tulkki.ui.conversationlist.ConversationAvatar]. A row whose image has not landed draws the
 * plate the tree's `AvatarView` drew, never a blank hole.
 */

/** The four tabs the tab bar carries, in the order the deleted bottom-navigation menu listed them. */
enum class NavTab {
    CHATS,
    CALLS,
    STORIES,
    FEEDS,
}

/**
 * The four badges the tab bar draws: the unread count on `chats`, and a dot on each of the other
 * three - exactly the four `getOrCreateBadge` calls the deleted `refreshUiReal` made, with
 * `number = unreadCount` on the chat tab and `isVisible` alone everywhere else.
 */
data class NavBadges(
    val chats: Int = 0,
    val stories: Boolean = false,
    val feeds: Boolean = false,
    val calls: Boolean = false,
)

/**
 * One story row: whose feed it is, the account the row was resolved through (for the viewer's own
 * account extra), the resolved avatar, the name, the published time and the preview.
 *
 * <p>[jid] is the bare address of the feed's owner, which is the identity the deleted adapter keyed
 * its map on and the identity the viewer intent is built from; [accountUuid] is what the old
 * `StoryAdapter` handed the viewer as `EXTRA_ACCOUNT`.
 */
data class StoryRow(
    val jid: String,
    val accountUuid: String?,
    val avatar: Drawable?,
    val title: String,
    val time: String,
    val preview: ImageBitmap?,
)

/** The whole screen, read by [StoriesScreen] and written by the host. */
data class StoriesScreenState(
    val rows: List<StoryRow> = emptyList(),
    val hasOnlineAccounts: Boolean = false,
    val badges: NavBadges = NavBadges(),
    val showNavBar: Boolean = false,
)

/**
 * The eight tab icons: each tab's unselected and selected drawable, already resolved by the host
 * from the theme attributes the deleted `bottom_navigation_menu_stories.xml` named
 * (`?attr/ic_chat_unselected` and its seven siblings, which `values-night/themes.xml` swaps for the
 * white set). The screen must not resolve them itself: a theme attribute belongs to the host's
 * `Context.theme`, and a screenshot cell - which has no `Theme.Tulkki` under it - then passes the
 * pair the cell's own theme would have resolved.
 */
data class NavIcons(
    @DrawableRes val chatsUnselected: Int,
    @DrawableRes val chatsSelected: Int,
    @DrawableRes val callsUnselected: Int,
    @DrawableRes val callsSelected: Int,
    @DrawableRes val storiesUnselected: Int,
    @DrawableRes val storiesSelected: Int,
    @DrawableRes val feedsUnselected: Int,
    @DrawableRes val feedsSelected: Int,
)

/**
 * The list screen: the rows or the explainer, the add button, and the tab bar.
 *
 * @param avatarShape the owner's `avatar_shape`, which the tree's `AvatarView` clipped every avatar
 *     with; the host reads it from the same port the conversation list uses rather than guessing a
 *     circle.
 * @param icons the tab bar's own theme attributes, resolved by the host.
 */
@Composable
fun StoriesScreen(
    state: StoriesScreenState,
    avatarShape: AvatarShape,
    icons: NavIcons,
    onTab: (NavTab) -> Unit,
    onStory: (StoryRow) -> Unit,
    onAddStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.rows.isEmpty() && !state.hasOnlineAccounts) {
                // The deleted `refresh()`'s first branch: no account and nothing published, so the
                // explainer *instead of* the list, which is also why the add button is absent here.
                Text(
                    text = stringResource(R.string.no_active_account_to_show_stories),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows) { row -> StoryRowItem(row, avatarShape, onStory) }
                }
                if (state.hasOnlineAccounts) {
                    // The deleted `fab_add_story`: `layout_alignParentEnd`/`Bottom` at 16 dp, the
                    // same 16 dp margin and the same title-bar corner.
                    FloatingActionButton(
                        onClick = onAddStory,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.outline_add_a_photo_24),
                            contentDescription = stringResource(R.string.add_story),
                        )
                    }
                }
            }
        }
        if (state.showNavBar) {
            StoryNavBar(selected = NavTab.STORIES, badges = state.badges, icons = icons, onSelect = onTab)
        }
    }
}

/**
 * One row, where `list_item_story.xml` was: the avatar at the start, the name over the published
 * time, and the story's own still at the end.
 *
 * <p>The three attributes the deleted `RelativeLayout` carried are kept: the 12 dp padding, the name
 * at `Subtitle1` (the theme's `titleMedium` here) over the time at `Body2` (`bodyMedium`), and the
 * preview's 56 dp `MaterialCardView` with its 8 dp corners and no elevation. The deleted layout's
 * `minHeight="?listPreferredItemHeight"` is dropped: the 56 dp avatar plus the 12 dp padding is 80 dp,
 * so the floor was never reached.
 */
@Composable
private fun StoryRowItem(
    row: StoryRow,
    avatarShape: AvatarShape,
    onStory: (StoryRow) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onStory(row) }
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoryAvatar(row.avatar, avatarShape)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = row.time,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(16.dp))
        Card(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            val preview = row.preview
            if (preview == null) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The row's avatar, clipped with the owner's `avatar_shape` - the same drawing
 * `ConversationListScreen`'s `RowAvatar` does, because the tree's `AvatarView` drew both.
 */
@Composable
private fun StoryAvatar(avatar: Drawable?, shape: AvatarShape) {
    val clip = avatarClip(shape)
    Box(modifier = Modifier.size(dimensionResource(R.dimen.avatar_on_conversation_overview))) {
        if (avatar == null) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clip(clip)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().clip(clip)) {
                drawIntoCanvas { canvas ->
                    avatar.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    avatar.draw(canvas.nativeCanvas)
                }
            }
        }
    }
}

/** The owner's `avatar_shape`, as the clip a row draws with. */
@Composable
private fun avatarClip(shape: AvatarShape): Shape =
    when (shape) {
        AvatarShape.OVAL -> CircleShape
        AvatarShape.ROUNDED_SQUARE ->
            RoundedCornerShape(dimensionResource(R.dimen.avatar_corners_radius))
        AvatarShape.SQUARE -> RectangleShape
    }

/**
 * The four-tab bar, where `BottomNavigationView` + `bottom_navigation_menu_stories.xml` were.
 *
 * <p>`setBackgroundColor(Color.TRANSPARENT)` from the deleted `onCreate` is not repeated: the bar's
 * own `?colorSurfaceContainerLowest` is what remains, and M3's `NavigationBar` paints exactly that.
 * The height is the deleted `@dimen/nav_bar_height` (81 dp) rather than the component's own 80 dp, so
 * the bar's footprint is unchanged. The component's own navigation-bar insets are zeroed, because the
 * chrome's `Scaffold` has already handed the content the space the system bars leave - the one place
 * this bar and the deleted one differ in how they are placed.
 */
@Composable
private fun StoryNavBar(
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
        TabItem(
            tab = NavTab.CHATS,
            selected = selected,
            label = stringResource(R.string.chats),
            hasBadge = badges.chats > 0,
            badgeCount = badges.chats,
            unselected = icons.chatsUnselected,
            selectedIcon = icons.chatsSelected,
            onSelect = onSelect,
        )
        TabItem(
            tab = NavTab.CALLS,
            selected = selected,
            label = stringResource(R.string.calls),
            hasBadge = badges.calls,
            badgeCount = 0,
            unselected = icons.callsUnselected,
            selectedIcon = icons.callsSelected,
            onSelect = onSelect,
        )
        TabItem(
            tab = NavTab.STORIES,
            selected = selected,
            label = stringResource(R.string.stories),
            hasBadge = badges.stories,
            badgeCount = 0,
            unselected = icons.storiesUnselected,
            selectedIcon = icons.storiesSelected,
            onSelect = onSelect,
        )
        TabItem(
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
 * One tab: the selected/unselected icon pair, the badge, and the label.
 *
 * <p>It is a [RowScope] extension because M3's `NavigationBarItem` is one - `NavigationBar`'s content
 * lambda is the row scope - so this helper has to carry the receiver to call it at all; a plain
 * function does not resolve the name.
 */
@Composable
private fun RowScope.TabItem(
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
                        // The chat tab drew the count; the other three drew a dot.
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
