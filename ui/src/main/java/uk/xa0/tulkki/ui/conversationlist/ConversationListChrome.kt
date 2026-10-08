package uk.xa0.tulkki.ui.conversationlist

import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem

/**
 * The conversation list's furniture, where `activity_conversation_list.xml` and its `layout-w945dp`
 * twin, `fragment_conversation_list.xml` and the four menus those screens inflated were.
 *
 * <p>**What it replaces, piece by piece.** The `DrawerLayout` + `MaterialDrawerSliderView` pair is
 * [ModalNavigationDrawer]; the `BottomNavigationView` + `bottom_navigation_menu_chat.xml` is
 * [ConversationListNavBar], the same four destinations in the same order with the same four badges
 * and the same one selected; the `RelativeLayout`/`LinearLayout` two-pane arrangement of the
 * `-w945dp` file is [ConversationListPanes], a `Row` whose weights are that file's `1000`/`1618` and
 * whose split is the same width threshold (945 dp of smallest screen width); and the `MaterialToolbar`
 * with its title/subtitle/avatar block is [ConversationListTopBar]. The activity's own menu and
 * `fragment_conversation_list.xml`'s ten items are the bar's actions and overflow, handed in as
 * [ChromeMenuItem]s.
 *
 * <p>**Why this file draws its own bar instead of `TulkkiChrome`.** The chrome's bar carries a title,
 * an up affordance, an actions slot and an overflow, and nothing else; this screen's XML toolbar also
 * carried a subtitle line (typing and last-seen) and an avatar, both of which the conversation it is
 * showing owns. `TulkkiChrome` has no slot for either, and its KDoc's own rule is that a conversion
 * hangs its screen from it rather than building a second one "like it" - this bar is not like it, it
 * is the same M3 `TopAppBar` with two more slots, and the same `stringResource`-resolved title, so
 * the difference is that one block and not a second chrome. Everything else the chrome owns is
 * duplicated from it deliberately: the `titleLarge` title, the leading `IconButton`, the
 * `ic_more_horiz_24dp` overflow.
 *
 * <p>**The drawer's content is the deleted MaterialDrawer's, row for row.** The account block is the
 * `AccountHeaderView`'s: the active account's avatar (a tap edits that account, which is the deleted
 * `onAccountHeaderProfileImageListener`), then every profile - the "All accounts" pseudo-profile when
 * there is more than one account, one row per account with its unread badge, and the three setting
 * profiles (`Manage accounts`/`Add account`/`Manage Phone Accounts`) which are actions and never
 * selections. Below them are the four filters (`All chats`, `Unread chats`, `Direct messages`,
 * `Channels`) with their badges, the `Chat requests` filter while the service says there are any,
 * one row per tag with its badge, and the three sticky rows (`Tulkki settings`, `Media gallery`,
 * `Settings`). A tap and a long press are handed back as ids, so every navigation decision - which
 * id filters, which id opens a screen, which id toggles a tag - stays where it was, in the activity.
 *
 * <p>**What is deliberately not reproduced.** MaterialDrawer's own pixel furniture: the header's
 * gradient, its divider alignment and its per-item ripple are the library's drawing, not this
 * screen's content. The rows keep the library's information and the library's selection, drawn with
 * M3's `secondaryContainer` and `labelLarge` instead.
 *
 * @param state the bar's own facts, assembled by the activity on every read.
 * @param drawer the drawer's content, assembled by the activity on every read.
 * @param avatar the `ImageView` the activity's `AvatarWorkerTask` loads the conversation's avatar
 *     into; `null` draws no avatar. It is a view rather than an image because the loader is the
 *     tree's own and it writes into a view.
 * @param menu the bar's overflow, already resolved to labels and actions by the host.
 * @param actions the bar's always-visible actions, the `RowScope` `TopAppBar` gives them.
 * @param onUp the leading back affordance, or the menu's own drawer if [state] says
 *     [ConversationLeading.DRAWER].
 * @param onDrawerItem a drawer row was tapped: its id, which is the deleted `onDrawerItemClickListener`'s.
 * @param onDrawerItemLongClick a drawer row was held: its id, which is the deleted
 *     `onDrawerItemLongClickListener`'s. The position MaterialDrawer also handed over is not needed:
 *     the id names the tag or the filter.
 * @param onAccount a profile row was tapped, and whether it is the active one.
 * @param onAccountAvatar the active account's avatar was tapped, which is the deleted
 *     `onAccountHeaderProfileImageListener`.
 * @param onTab one of the four destinations was chosen.
 * @param content the two panes, laid out by [ConversationListPanes].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListChrome(
    state: ConversationListChromeState,
    drawer: ConversationDrawerState,
    avatar: ImageView?,
    menu: List<ChromeMenuItem>,
    onUp: () -> Unit,
    onDrawerItem: (Long) -> Unit,
    onDrawerItemLongClick: (Long) -> Unit,
    onAccount: (Long, Boolean) -> Unit,
    onAccountAvatar: () -> Unit,
    onTab: (ConversationNavTab) -> Unit,
    onTitle: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        // The deleted `refreshUiReal` locked the drawer closed when the owner turns the nav drawer
        // off and unlocked it otherwise; `gesturesEnabled` is that lock for a Compose drawer, and
        // the bar's leading icon is absent in the same state, so there is no way in.
        gesturesEnabled = state.drawerEnabled,
        drawerContent = {
            ConversationDrawerSheet(
                drawer = drawer,
                onItem = { id ->
                    onDrawerItem(id)
                    scope.launch { drawerState.close() }
                },
                onItemLongClick = onDrawerItemLongClick,
                onAccount = { id, current ->
                    onAccount(id, current)
                    if (!current) {
                        scope.launch { drawerState.close() }
                    }
                },
                onAccountAvatar = onAccountAvatar,
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConversationListTopBar(
                state = state,
                avatar = avatar,
                menu = menu,
                actions = actions,
                onTitle = onTitle,
                onLeading = {
                    when (state.leading) {
                        ConversationLeading.DRAWER -> scope.launch { drawerState.open() }
                        ConversationLeading.BACK -> onUp()
                        ConversationLeading.NONE -> Unit
                    }
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            if (state.showNavBar) {
                ConversationListNavBar(selected = state.tab, badges = state.badges, onSelect = onTab)
            }
        }
    }
}

/**
 * The bar: the deleted `MaterialToolbar`'s own three slots - the leading icon, the avatar with the
 * title over its subtitle, and the trailing actions - in the tree's chrome shape (a `TopAppBar`
 * inside a `Column`, which is what `TulkkiChrome` puts in its `Scaffold`'s `topBar`).
 *
 * <p>The leading icon is the deleted `invalidateActionBarTitle`'s decision, one for one: the back
 * arrow while the main pane holds a conversation, the `menu_24dp` drawer handle while the owner's
 * `show_nav_drawer` is on, and nothing at all otherwise. The avatar is hosted through [AndroidView]
 * because the tree's `AvatarWorkerTask` loads into a view, and it is the same 38 dp `centerCrop`
 * `AvatarView` the XML declared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListTopBar(
    state: ConversationListChromeState,
    avatar: ImageView?,
    menu: List<ChromeMenuItem>,
    actions: @Composable RowScope.() -> Unit,
    onTitle: () -> Unit,
    onLeading: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) {
                    // The view is kept in the composition and sized to nothing while the bar hides
                    // it, rather than entering and leaving: the loader writes into this one instance,
                    // and a view that leaves the composition is detached from it.
                    AndroidView(
                        factory = { avatar },
                        modifier =
                            if (state.showAvatar) {
                                Modifier.padding(end = 8.dp).size(38.dp)
                            } else {
                                Modifier.size(0.dp)
                            },
                    )
                }
                Column(modifier = Modifier.clickable(enabled = state.titleClickable) { onTitle() }) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = state.subtitle
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily =
                                if (state.subtitleEmphasis) {
                                    // The deleted bar set `noto_sans_bold_italic` on the typing
                                    // line alone, and only from the secondary pane's branch.
                                    FontFamily(Font(R.font.noto_sans_bold_italic))
                                } else {
                                    null
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            when (state.leading) {
                ConversationLeading.DRAWER ->
                    IconButton(onClick = onLeading) {
                        Icon(
                            painter = painterResource(R.drawable.menu_24dp),
                            contentDescription = stringResource(R.string.conversation_list_open_drawer),
                        )
                    }
                ConversationLeading.BACK ->
                    IconButton(onClick = onLeading) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                ConversationLeading.NONE -> Unit
            }
        },
        actions = {
            actions()
            if (menu.isNotEmpty()) {
                TopBarOverflow(menu)
            }
        },
    )
}

/**
 * The trailing overflow, the same one `TulkkiChrome` draws: an icon that opens the items, and no
 * icon at all when there are none. It is a copy rather than a call because that one is private to
 * the chrome file and this bar is not the chrome.
 */
@Composable
private fun TopBarOverflow(menu: List<ChromeMenuItem>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_more_horiz_24dp),
            contentDescription = stringResource(R.string.more_options),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (item in menu) {
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = {
                    open = false
                    item.onSelected()
                },
            )
        }
    }
}

/**
 * The four destinations, where `BottomNavigationView` + `bottom_navigation_menu_chat.xml` were: the
 * same order, the same labels, the same selected tab (`chats`, which is the tab this activity is),
 * and the same four badges - the unread count on `chats`, a dot on the other three, which is exactly
 * the deleted `refreshUiReal`'s four `getOrCreateBadge` calls.
 *
 * <p>Each icon is the theme attribute the deleted menu named (`?attr/ic_chat_selected` and the three
 * `_unselected` siblings), resolved in the composition the way
 * [uk.xa0.tulkki.ui.posts.PostsScreen]'s own bar resolves its four.
 *
 * <p>The height is the deleted `@dimen/nav_bar_height` (81 dp) rather than M3's own 80 dp, and the
 * navigation-bar inset is padding outside that height rather than inside it, because nothing here is
 * a `Scaffold`: the `TopAppBar` pads itself for the status bar and this bar for the navigation bar,
 * which is what the deleted layout's `fitsSystemWindows` parent did for the pair.
 */
@Composable
private fun ConversationListNavBar(
    selected: ConversationNavTab,
    badges: ConversationNavBadges,
    onSelect: (ConversationNavTab) -> Unit,
) {
    NavigationBar(
        modifier =
            // The deleted layout put the 81 dp bar above the navigation-bar inset, because its
            // `fitsSystemWindows` parent carried the inset as padding. There is no `Scaffold` here,
            // so the padding is this bar's own and the component's own insets are zeroed rather
            // than applied inside the fixed height.
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                .height(dimensionResource(R.dimen.nav_bar_height)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        NavItem(
            tab = ConversationNavTab.CHATS,
            selected = selected,
            label = stringResource(R.string.chats),
            icon = themeDrawableResource(R.attr.ic_chat_selected) ?: R.drawable.chat_selected_black_24,
            count = badges.chats,
            dot = false,
            onSelect = onSelect,
        )
        NavItem(
            tab = ConversationNavTab.CALLS,
            selected = selected,
            label = stringResource(R.string.calls),
            icon = themeDrawableResource(R.attr.ic_calls_unselected) ?: R.drawable.calls_unselected_black_24dp,
            count = 0,
            dot = badges.calls,
            onSelect = onSelect,
        )
        NavItem(
            tab = ConversationNavTab.STORIES,
            selected = selected,
            label = stringResource(R.string.stories),
            icon = themeDrawableResource(R.attr.ic_stories_unselected) ?: R.drawable.stories_unselected_black_24,
            count = 0,
            dot = badges.stories,
            onSelect = onSelect,
        )
        NavItem(
            tab = ConversationNavTab.FEEDS,
            selected = selected,
            label = stringResource(R.string.feeds),
            icon = themeDrawableResource(R.attr.feed_unselected) ?: R.drawable.feed_unselected_black_24dp,
            count = 0,
            dot = badges.feeds,
            onSelect = onSelect,
        )
    }
}

/**
 * One destination. It is a [RowScope] extension because M3's `NavigationBarItem` is one.
 */
@Composable
private fun RowScope.NavItem(
    tab: ConversationNavTab,
    selected: ConversationNavTab,
    label: String,
    @DrawableRes icon: Int,
    count: Int,
    dot: Boolean,
    onSelect: (ConversationNavTab) -> Unit,
) {
    NavigationBarItem(
        selected = tab == selected,
        onClick = { onSelect(tab) },
        icon = {
            BadgedBox(
                badge = {
                    if (count > 0) {
                        Badge { Text(count.toString()) }
                    } else if (dot) {
                        Badge()
                    }
                }
            ) {
                Icon(painter = painterResource(icon), contentDescription = null)
            }
        },
        label = { Text(label) },
    )
}

/**
 * The drawer's sheet: the account block, the filters, the tags and the sticky rows.
 *
 * <p>It is its own entry point - rather than three lines inside [ConversationListChrome] - because the
 * screenshot harness can render a `ModalDrawerSheet` but cannot open a `ModalNavigationDrawer`, so
 * this is the only shape in which the drawer's content can be pinned by a reference.
 *
 * <p>Long press reaches the tags, which is the deleted `onDrawerItemLongClickListener`'s
 * multi-select, and it reaches the filters too, because the deleted listener answered those as well.
 * Every row is the same `combinedClickable` [DrawerRow], so a filter and a tag cannot drift apart in
 * how they answer a gesture - only in what the activity then does with the id.
 */
@Composable
fun ConversationDrawerSheet(
    drawer: ConversationDrawerState,
    onItem: (Long) -> Unit,
    onItemLongClick: (Long) -> Unit,
    onAccount: (Long, Boolean) -> Unit,
    onAccountAvatar: () -> Unit,
) {
    ModalDrawerSheet {
        ConversationDrawerContent(drawer, onItem, onItemLongClick, onAccount, onAccountAvatar)
    }
}

/** The sheet's content, without the sheet: what the screenshot cells render. */
@Composable
private fun ConversationDrawerContent(
    drawer: ConversationDrawerState,
    onItem: (Long) -> Unit,
    onItemLongClick: (Long) -> Unit,
    onAccount: (Long, Boolean) -> Unit,
    onAccountAvatar: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val active = drawer.profiles.firstOrNull { it.selected }
        if (active != null) {
            ActiveAccountHeader(active, onAccountAvatar)
        }
        for (profile in drawer.profiles) {
            DrawerRow(
                label = profile.name,
                description = profile.description,
                selected = profile.selected,
                badge = profile.badge,
                onClick = { onAccount(profile.id, profile.selected) },
                avatar = profile.avatar,
                showAvatar = true,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        for (item in drawer.items) {
            DrawerRow(
                label = item.label,
                selected = item.selected,
                badge = item.badge,
                onClick = { onItem(item.id) },
                onLongClick = { onItemLongClick(item.id) },
                iconRes = item.icon,
            )
        }
        if (drawer.requests.isNotEmpty() || drawer.tags.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        }
        for (request in drawer.requests) {
            DrawerRow(
                label = request.label,
                selected = request.selected,
                badge = request.badge,
                onClick = { onItem(request.id) },
                onLongClick = { onItemLongClick(request.id) },
                iconRes = request.icon,
            )
        }
        for (tag in drawer.tags) {
            DrawerRow(
                label = tag.label,
                selected = tag.selected,
                badge = tag.badge,
                onClick = { onItem(tag.id) },
                onLongClick = { onItemLongClick(tag.id) },
            )
        }
        if (drawer.sticky.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
            for (item in drawer.sticky) {
                DrawerRow(
                    label = item.label,
                    description = item.description,
                    selected = false,
                    badge = item.badge,
                    onClick = { onItem(item.id) },
                    iconRes = item.icon,
                )
            }
        }
    }
}

/**
 * The active account, where `AccountHeaderView`'s own header image was: the avatar (tapping it is
 * the deleted `onAccountHeaderProfileImageListener`, which edits the account) over the name and the
 * bare address.
 */
@Composable
private fun ActiveAccountHeader(profile: DrawerProfile, onAvatar: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onAvatar)) {
            ProfileAvatar(profile.avatar, size = 56)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (profile.badge > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Badge { Text(profile.badge.toString()) }
        }
    }
}

/** One account's avatar, drawn through a `Canvas` because the service hands a `Drawable` over. */
@Composable
private fun ProfileAvatar(avatar: Drawable?, size: Int = 40) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape)) {
        if (avatar != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bounds = this.size
                drawIntoCanvas { canvas ->
                    avatar.setBounds(0, 0, bounds.width.toInt(), bounds.height.toInt())
                    avatar.draw(canvas.nativeCanvas)
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

/** One drawer row's own leading icon, at the 24 dp the deleted `PrimaryDrawerItem` drew it. */
@Composable
private fun DrawerItemIcon(@DrawableRes icon: Int) {
    Icon(painter = painterResource(icon), contentDescription = null)
}

/**
 * One drawer row: the label, an optional description, an optional leading slot and an optional
 * count badge, with a tap and a long press. The selected background is the one visual the deleted
 * MaterialDrawer item carried that the content needs - which row is the current filter.
 *
 * <p>The leading slot is one of three plain values rather than a composable lambda, so a row cannot
 * carry a composition of its own: an entry's theme icon, a profile's avatar, or nothing.
 *
 * @param iconRes the entry's own icon, the deleted `PrimaryDrawerItem`'s `iconRes`.
 * @param avatar the profile's own image, the deleted `ProfileDrawerItem`'s `iconBitmap`; a `null`
 *     avatar with [showAvatar] draws the plate the tree's `AvatarView` drew.
 * @param showAvatar whether this row is a profile and therefore has an avatar slot at all.
 */
@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    description: String? = null,
    badge: Int = 0,
    @DrawableRes iconRes: Int? = null,
    avatar: Drawable? = null,
    showAvatar: Boolean = false,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    }
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                DrawerItemIcon(iconRes)
            }
            Spacer(modifier = Modifier.width(12.dp))
        } else if (showAvatar) {
            ProfileAvatar(avatar)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Badge { Text(badge.toString()) }
        }
    }
}

/**
 * The two panes, where the `-w945dp` layout's `LinearLayout` with `main_fragment` and
 * `secondary_fragment` was.
 *
 * <p>The split is the qualifier's own rule: a `-w945dp` resource is chosen when the device's
 * **smallest screen width** is at least 945 dp, so [twoPane] is that comparison, taken by the host
 * from `Configuration.smallestScreenWidthDp` so the activity and this row cannot disagree about it.
 * The weights are the deleted file's `1000` and `1618`.
 *
 * <p>Each pane is an [AndroidView] wrapping the `FrameLayout` the fragment manager adds a fragment
 * into, because `main_fragment` and `secondary_fragment` are containers the tree's fragments are
 * still keyed on - `ConversationFragment` reads `R.id.secondary_fragment` itself, and
 * `ConversationLookup` reads both. The view is created once by [remember] and reported back through
 * [onReady], which is what lets the host wait for a container before it commits a transaction into
 * it: a `ComposeView` composes when its window attaches, which is after `onCreate`.
 *
 * @param onReady a container exists; the host initialises its fragments on the first call.
 */
@Composable
fun ConversationListPanes(
    twoPane: Boolean,
    mainFragmentId: Int,
    secondaryFragmentId: Int,
    onReady: () -> Unit,
) {
    if (twoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            PaneSlot(mainFragmentId, 1000f, onReady)
            PaneSlot(secondaryFragmentId, 1618f, onReady)
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            PaneSlot(mainFragmentId, 1f, onReady)
        }
    }
}

@Composable
private fun RowScope.PaneSlot(id: Int, weight: Float, onReady: () -> Unit) {
    val context = LocalContext.current
    val view =
        remember(id) {
            FrameLayout(context).apply {
                this.id = id
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                post { onReady() }
            }
        }
    AndroidView(factory = { view }, modifier = Modifier.weight(weight).fillMaxHeight())
}

/** The drawable a theme attribute points at, or `null` when the theme does not set it. */
@Composable
private fun themeDrawableResource(@AttrRes attribute: Int): Int? {
    val context = LocalContext.current
    return remember(attribute, context) {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attribute, value, true)) {
            value.resourceId.takeIf { it != 0 }
        } else {
            null
        }
    }
}

/**
 * The deleted `action_search`, at the bar's own `showAsAction="always"` end of the toolbar.
 *
 * <p>Its two shapes are the two menus' own: `@bool/show_combined_search_options` gives the item the
 * submenu with `action_search_all_chats` and `action_search_this_conversation`, drawn here as a
 * dropdown; otherwise the item is one tap onto the list search, which is what
 * `@bool/show_individual_search_options` selected.
 */
@Composable
fun ChromeSearchAction(
    combined: Boolean,
    onSearchAll: () -> Unit,
    onSearchThis: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { if (combined) open = true else onSearchAll() }) {
        Icon(
            painter = painterResource(R.drawable.ic_search_24dp),
            contentDescription = stringResource(R.string.search_messages),
        )
    }
    if (combined) {
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_all_chats)) },
                onClick = {
                    open = false
                    onSearchAll()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_this_conversation)) },
                onClick = {
                    open = false
                    onSearchThis()
                },
            )
        }
    }
}

/** One always-visible bar action: the deleted menu items that carried `showAsAction="always"`. */
@Composable
fun ChromeIconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/** The four destinations, in the order the deleted `bottom_navigation_menu_chat.xml` listed them. */
enum class ConversationNavTab {
    CHATS,
    CALLS,
    STORIES,
    FEEDS,
}

/**
 * The four badges: the unread count on `chats` and a dot on each of the other three, which is
 * exactly the deleted `refreshUiReal`'s four `getOrCreateBadge` calls, one per destination.
 */
data class ConversationNavBadges(
    val chats: Int = 0,
    val calls: Boolean = false,
    val stories: Boolean = false,
    val feeds: Boolean = false,
)

/** The bar's leading affordance: the drawer handle, the up arrow, or nothing. */
enum class ConversationLeading {
    NONE,
    DRAWER,
    BACK,
}

/**
 * One profile in the drawer's account block, the deleted `AccountHeaderView`'s rows.
 *
 * @param badge the account's unread count, `0` for none. The deleted header drew a blank badge for
 *     a zero count to keep the row's height; a Compose badge with no count draws nothing, which is
 *     the same height because the row's own padding carries it.
 * @param selected whether this is the active profile.
 */
data class DrawerProfile(
    val id: Long,
    val name: String,
    val description: String,
    val avatar: Drawable?,
    val badge: Int,
    val selected: Boolean,
)

/**
 * One entry of the drawer's item lists: the filters, the tags and the sticky rows.
 *
 * @param selected the filter this is, or the tag the owner has selected.
 * @param description the second line a sticky row may carry (`tulkki_drawer_summary`).
 */
data class DrawerEntry(
    val id: Long,
    val label: String,
    val description: String? = null,
    @DrawableRes val icon: Int? = null,
    val badge: Int = 0,
    val selected: Boolean = false,
)

/** The drawer's whole content, assembled by the activity on every read. */
data class ConversationDrawerState(
    val profiles: List<DrawerProfile> = emptyList(),
    val items: List<DrawerEntry> = emptyList(),
    val requests: List<DrawerEntry> = emptyList(),
    val tags: List<DrawerEntry> = emptyList(),
    val sticky: List<DrawerEntry> = emptyList(),
)

/**
 * The bar's and the frame's facts.
 *
 * @param title the conversation's name or the app's, the deleted `invalidateActionBarTitle`'s answer.
 * @param subtitle the typing or last-seen line, or `null` for the deleted bar's `GONE`.
 * @param showAvatar whether the conversation's avatar is drawn, the deleted bar's `toolbarAvatar`
 *     visibility.
 * @param titleClickable whether tapping the title opens the conversation's details.
 * @param leading the leading icon, the deleted `setDisplayHomeAsUpEnabled`/`setHomeAsUpIndicator` pair.
 * @param drawerEnabled whether the drawer may be opened at all, the deleted
 *     `setDrawerLockMode(LOCK_MODE_UNLOCKED)` branch.
 * @param showNavBar whether the bottom bar is drawn, the deleted `showNavigationBar()`'s answer.
 * @param tab which destination is selected; always [ConversationNavTab.CHATS] on this screen, which
 *     is what the deleted `onStart` selected.
 * @param twoPane whether the `-w945dp` arrangement applies.
 */
data class ConversationListChromeState(
    val title: String = "",
    val subtitle: String? = null,
    val subtitleEmphasis: Boolean = false,
    val showAvatar: Boolean = false,
    val titleClickable: Boolean = false,
    val leading: ConversationLeading = ConversationLeading.NONE,
    val drawerEnabled: Boolean = false,
    val showNavBar: Boolean = false,
    val tab: ConversationNavTab = ConversationNavTab.CHATS,
    val badges: ConversationNavBadges = ConversationNavBadges(),
    val twoPane: Boolean = false,
)

/**
 * The observable the host writes and the composition reads, the same shape
 * [uk.xa0.tulkki.ui.posts.PostsHost.Session] gives the posts screen: a re-read updates the state
 * instead of rebuilding the composition.
 */
class ConversationListChromeSession {

    internal var state by mutableStateOf(ConversationListChromeState())

    internal var drawer by mutableStateOf(ConversationDrawerState())

    /** The bar's facts, already assembled. */
    fun update(next: ConversationListChromeState) {
        state = next
    }

    /** The drawer's content, already assembled. */
    fun updateDrawer(next: ConversationDrawerState) {
        drawer = next
    }
}

/** Which FAB the list pane draws, which is the deleted pair's visibility rule. */
enum class ConversationListFab {
    /** Onboarding: neither FAB, exactly as the deleted `applyFabVisibility`'s first branch. */
    NONE,

    /** The extended FAB the deleted `fab` was; the bottom bar is hidden. */
    EXTENDED,

    /** The small FAB the deleted `fab_start_conversation` was; the bottom bar is visible. */
    START_CONVERSATION,
}

/**
 * The list pane's own state, where `fragment_conversation_list.xml`'s `overview_snackbar` strip and
 * its two FABs were.
 *
 * @param fab which FAB is drawn.
 * @param warning the MAM-preference warning's message, or `null` for no strip. The strip is the one
 *     surface `mam_pref_fix.xml`'s `Ignore` item belonged to, so it is state rather than a view.
 */
data class ConversationListPaneState(
    val fab: ConversationListFab = ConversationListFab.NONE,
    val warning: String? = null,
)

/** The observable the fragment writes and the pane reads, the same shape `ConversationListHost.Session` has. */
class ConversationListPaneSession {

    internal var state by mutableStateOf(ConversationListPaneState())

    fun update(next: ConversationListPaneState) {
        state = next
    }
}

/**
 * The list pane, where `fragment_conversation_list.xml` was: the Compose list, the two FABs and the
 * MAM warning strip.
 *
 * <p>The list is [ConversationListScreen], which was already the `ComposeView` `list`; the two FABs
 * are M3's own at the same `end|bottom` 16 dp the XML gave them; and the strip is the deleted
 * `overview_snackbar` `RelativeLayout` at the same 8 dp/4 dp margins and 48 dp minimum height, with
 * `mam_pref_fix.xml`'s single `Ignore` item behind a long press on the action - which is exactly
 * where the deleted `setOnLongClickListener` + `PopupMenu` put it.
 *
 * <p>The swipe's undo is not here: it is the tree's own Material `Snackbar`, hung on the pane's own
 * view by the fragment, because its five-second window and its `DismissEvent` callback are
 * behaviour rather than layout.
 *
 * @param session the fragment's list state, swiped row and avatar capability.
 * @param paneSession the FAB and the strip.
 */
@Composable
fun ConversationListPane(
    session: ConversationListHost.Session,
    paneSession: ConversationListPaneSession,
    listState: LazyListState,
    events: ConversationListEvents,
    onFab: () -> Unit,
    onWarningAction: () -> Unit,
    onWarningIgnore: () -> Unit,
) {
    val state = paneSession.state
    Box(modifier = Modifier.fillMaxSize()) {
        ConversationListScreen(
            state = session.state,
            events = events,
            listState = listState,
            swipeEnabled = session.swipeEnabled,
            menuEnabled = session.menuEnabled,
            avatar = session.avatar,
        )
        when (state.fab) {
            ConversationListFab.NONE -> Unit
            ConversationListFab.EXTENDED ->
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.start_chat)) },
                    icon = { Icon(painterResource(R.drawable.ic_chat_24dp), contentDescription = null) },
                    onClick = onFab,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            ConversationListFab.START_CONVERSATION ->
                FloatingActionButton(
                    onClick = onFab,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_24dp),
                        contentDescription = stringResource(R.string.start_chat),
                    )
                }
        }
        val warning = state.warning
        if (warning != null) {
            MamWarning(
                message = warning,
                onAction = onWarningAction,
                onIgnore = onWarningIgnore,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** The deleted `overview_snackbar`: its message, its `action_fix` action and its long-press menu. */
@Composable
private fun MamWarning(
    message: String,
    onAction: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp).padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 24.dp),
            )
            Box {
                Text(
                    text = stringResource(R.string.action_fix).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier =
                        Modifier.combinedClickable(onClick = onAction, onLongClick = { menu = true })
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ignore)) },
                        onClick = {
                            menu = false
                            onIgnore()
                        },
                    )
                }
            }
        }
    }
}
