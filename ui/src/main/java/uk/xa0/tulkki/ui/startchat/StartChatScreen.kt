package uk.xa0.tulkki.ui.startchat

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.utils.XEP0392Helper

/**
 * The new-chat screen: the contacts and the group chats the last filter found, the search field the
 * bar's search action opens, and the FAB that adds one.
 *
 * <p>**What it replaces.** `activity_start_conversation.xml` - the `MaterialToolbar`, the
 * `TabLayout`, the `ViewPager`, the `SpeedDialOverlayLayout` and the `SpeedDialView` - is deleted;
 * the bar is the shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) and this file is everything
 * that drew below it. The one liberty is that the old XML hid its `TabLayout`
 * (`android:visibility="gone"`) while still putting both pages in the pager, so the two pages were
 * reachable by swiping and by nothing else: [HorizontalPager] keeps exactly that, two pages and no
 * tab row.
 *
 * <p>**The rows are Compose now, and the pages are still slots.** `ListView`, `ListItemAdapter` and
 * `item_contact.xml` are deleted: each page is a [uk.xa0.tulkki.ui.list.PickerList] over the rows its
 * host resolved, and the slot is kept so a screenshot cell can compose the shell without the rows.
 * The screen owns everything the old shell drew around them.
 *
 * <p>**The search field is the bar's action view, moved into the body.** The XML inflated
 * `actionview_search.xml` into the `action_search` menu item: an `EditText` and a `RecyclerView` of
 * the dynamic tags. The chrome has no action view, so the field is drawn here while [searchOpen],
 * with the same hint per page (`search_chats` for the contacts, `search_group_chats` for the group
 * chats), the same search IME action, the same clear affordance, and the same tag row - the tags
 * `TagsAdapter` showed, filtered by the same rule and harmonised with the same primary.
 *
 * <p>**The FAB is the SpeedDial.** `SpeedDialView` with `SpeedDialOverlayLayout` became a
 * `FloatingActionButton` - the same `ic_add_24dp` on the same `colorPrimaryContainer` - whose tap
 * opens the same four labelled actions, with the same icons and the same dismissal on an outside
 * tap, that `inflateFab` read out of `start_conversation_fab_submenu.xml`. The one difference is
 * the shape of the reveal: a `DropdownMenu` above the button rather than the speed dial's arc.
 *
 * @param searchOpen whether the search field and its tag row are drawn and focused.
 * @param query the search field's text, owned by the host.
 * @param tags every dynamic tag the last filter found, unfiltered; the screen drops the ones the
 *     query already names, exactly as the deleted `TagsAdapter.setTags` did.
 * @param showTags whether the tag row is drawn at all (`show_dynamic_tags`).
 * @param contactsList the contacts page, a `PickerList`.
 * @param conferencesList the group chats page, likewise.
 * @param fabActions the FAB's items, in menu order.
 * @param fabOpen whether the FAB's actions are drawn.
 * @param events what a gesture means; the host performs it.
 */
@Composable
fun StartChatScreen(
    searchOpen: Boolean,
    query: String,
    tags: List<ListItem.Tag>,
    showTags: Boolean,
    contactsList: @Composable () -> Unit,
    conferencesList: @Composable () -> Unit,
    fabActions: List<StartChatAction>,
    fabOpen: Boolean,
    events: StartChatEvents,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (searchOpen) {
                SearchField(
                    hint =
                        stringResource(
                            if (pagerState.currentPage == 0) R.string.search_chats
                            else R.string.search_group_chats,
                        ),
                    query = query,
                    onQueryChange = { events.onQueryChange(it) },
                    onSearchClose = { events.onSearchClose() },
                    onSearchSubmit = { events.onSearchSubmit(pagerState.currentPage) },
                )
                if (showTags) {
                    val visible = visibleTags(tags, query)
                    if (visible.isNotEmpty()) {
                        TagsRow(
                            tags = visible,
                            onClick = { events.onTagChip(needleFor(query, it.name)) },
                        )
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                // Both pages stay composed, exactly as the deleted `ViewPager` kept both of its
                // fragments alive: each page is one long-lived `ListView` handed in as a slot, and
                // a page that is disposed and composed again would hand the same view to a second
                // `AndroidView` holder.
                beyondViewportPageCount = 1,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                if (page == 0) contactsList() else conferencesList()
            }
        }
        Fab(
            open = fabOpen,
            actions = fabActions,
            onToggle = { events.onFabToggle(it) },
            onAction = { events.onFabAction(it) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/** One item of the FAB's menu: the icon and label the deleted `start_conversation_fab_submenu` carried. */
enum class StartChatAction(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    DiscoverChannels(R.drawable.ic_travel_explore_24dp, R.string.discover_channels),
    CreatePublicChannel(R.drawable.ic_public_24dp, R.string.create_public_channel),
    CreatePrivateGroupChat(R.drawable.ic_group_24dp, R.string.create_private_group_chat),
    CreateContact(R.drawable.ic_person_24dp, R.string.new_contact),
}

/**
 * What a gesture on the screen means. The host performs it and writes its new state back, so the
 * screen decides nothing and names no string of its own.
 */
interface StartChatEvents {

    /** Every edit of the search field, so the host can filter. */
    fun onQueryChange(query: String)

    /** The field's clear affordance: the old action view's collapse. */
    fun onSearchClose()

    /** The IME's search action, with the page it ran on: the old `OnEditorActionListener` hook. */
    fun onSearchSubmit(page: Int)

    /** A tag chip tapped: the query the chip stands for, already composed. */
    fun onTagChip(query: String)

    /** The FAB's own tap, with the state it should take. */
    fun onFabToggle(open: Boolean)

    /** One of the FAB's actions chosen. */
    fun onFabAction(action: StartChatAction)
}

/** The FAB and its four actions: `SpeedDialView`, with the overlay's dismissal kept. */
@Composable
private fun Fab(
    open: Boolean,
    actions: List<StartChatAction>,
    onToggle: (Boolean) -> Unit,
    onAction: (StartChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FloatingActionButton(onClick = { onToggle(!open) }) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24dp),
                contentDescription =
                    stringResource(R.string.add_contact_or_create_or_join_group_chat),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { onToggle(false) }) {
            for (action in actions) {
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(painterResource(action.icon), contentDescription = null) },
                    onClick = {
                        onToggle(false)
                        onAction(action)
                    },
                )
            }
        }
    }
}

/** The search field, focused and with the IME shown as soon as it is drawn. */
@Composable
private fun SearchField(
    hint: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester),
        placeholder = { Text(hint) },
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.ic_search_24dp), contentDescription = null)
        },
        trailingIcon = {
            IconButton(
                onClick = {
                    keyboard?.hide()
                    onSearchClose()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear_24dp),
                    contentDescription = stringResource(R.string.clear_search),
                )
            }
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Search,
            ),
        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
    )
}

/**
 * The dynamic tags, as the deleted `TagsAdapter` row drew them: a tinted pill per tag.
 *
 * <p>The tint is the deleted adapter's harmonisation of the tag's `XEP0392Helper.rgbFromNick`
 * colour with the theme's primary, but the primary is read from the Compose theme this row is
 * drawn in, not from the hosting Android theme: `MaterialColors.harmonizeWithPrimary` reads
 * `colorPrimary` out of the `Context`'s theme and throws where there is none, which is what a
 * Composable cannot know - a preview renders under no application theme at all. The two values are
 * the same one: `TulkkiColors`' `primary` mirrors the `md_theme_*_primary` that `Theme.Tulkki`
 * sets, and `TokenParityTest` keeps them equal.
 */
@Composable
private fun TagsRow(tags: List<ListItem.Tag>, onClick: (ListItem.Tag) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tag in tags) {
            val tint = MaterialColors.harmonize(XEP0392Helper.rgbFromNick(tag.name), primary)
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = Color(tint),
                modifier = Modifier.clickable { onClick(tag) },
            ) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * The tags to draw for [query] - the body of the deleted `TagsAdapter.setTags`: a tag the query
 * already names is dropped, and `Channel` is drawn first while the query does not name it.
 */
private fun visibleTags(tags: List<ListItem.Tag>, query: String): List<ListItem.Tag> {
    val channelTag = ListItem.Tag("Channel")
    val needle = query.lowercase(Locale.US).trim { it <= ' ' }
    val parts = HashSet(Pattern.compile("[,\\s]+").split(needle).toList())
    val filtered = ArrayList<ListItem.Tag>()
    for (tag in tags) {
        if (tag != channelTag && !parts.contains(tag.name.lowercase(Locale.US))) {
            filtered.add(tag)
        }
    }
    if (!parts.contains("channel") && tags.contains(channelTag)) {
        filtered.add(0, channelTag)
    }
    return filtered
}

/**
 * The query a tag chip stands for - the body of the deleted `TagsAdapter.ViewHolder`: the tag
 * replaces the part the query ends on, or is appended after a comma.
 */
private fun needleFor(query: String, tag: String): String {
    val parts = Pattern.compile("[,\\s]+").split(query)
    return if (query.isEmpty()) {
        tag
    } else if (tag.lowercase(Locale.US).contains(parts[parts.size - 1])) {
        query.replace(parts[parts.size - 1], tag)
    } else {
        query + ", " + tag
    }
}
