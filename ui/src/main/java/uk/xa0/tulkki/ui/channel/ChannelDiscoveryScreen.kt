package uk.xa0.tulkki.ui.channel

import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.MessageFormat
import java.util.Locale
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.widget.AvatarView

/**
 * The channel search and its results: the search field the bar's `action_search` action view used
 * to be, and the rows the deleted `ChannelSearchResultAdapter` used to bind.
 *
 * <p>**What it replaces.** `activity_channel_discovery.xml` held a toolbar, a `ProgressBar` and a
 * `RecyclerView`; `item_channel_discovery.xml` held one result. Both files are gone - the bar is the
 * shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now - and so is
 * `uk.xa0.tulkki.ui.adapter.ChannelSearchResultAdapter`, which inflated the row layout and the
 * context menu. The search field is [ChannelSearchField], the row is [ChannelResultRow] in a
 * `LazyColumn`, and the loader and the empty-result background keep their old places: the spinner
 * above the list, the surface and the centred cancel mark behind an empty result set.
 *
 * <p>**The action view became a field in the content.** `actionview_search.xml` - shared by four
 * other screens, all converted and deleted with it - put an `EditText` into the toolbar's collapsed
 * menu item; this screen no longer inflates it. The field keeps the old hint
 * (`R.string.search_channels`), the email keyboard
 * and the search IME action, focuses itself and raises the IME as soon as it is drawn (the old
 * `onMenuItemActionExpand`), and its clear button is the old collapse: the caller clears the text
 * and re-runs the discovery for a null query.
 *
 * <p>**The context menu is a long-press drop-down.** The old `channel_item_context.xml` was a
 * framework context menu on the row; a `LazyColumn` row is not a view, so [ChannelResultRow] opens a
 * `DropdownMenu` on long press with the same two items and the same strings. The rows themselves
 * keep the adapter's display rules call for call: `MessageFormat`'s `name [nusers]`, a description
 * that is hidden when empty, a language shown only when it is two characters, uppercased, and the
 * room's bare JID or an empty string.
 *
 * <p>**What the cells cannot build.** A row's avatar is the platform `AvatarView` loaded by
 * `AvatarWorkerTask`, which needs a live Activity; [ChannelRow.avatarable] is therefore nullable and
 * a cell passes `null` for it, which draws the placeholder the avatar would stand in for. This is
 * the same argument the posts screen's cells make about a live account.
 *
 * @param rows the results, each a room plus the avatar source a preview cannot construct
 * @param loading whether the search is in flight; the old `ProgressBar` was visible until the first
 *     answer arrived
 * @param noResults whether the last answer was empty, which is when the old list swapped in
 *     `background_no_results`; an empty list that no search has answered yet draws plain surface
 * @param searchOpen whether the search field is drawn, focused and holding the IME
 * @param query the field's text
 * @param onQueryChange called on every edit, and typed or submitted by the field's own actions
 * @param onSearchClose the field's clear button: the old action view's collapse
 * @param onSearchSubmit the IME's search action, the old `OnEditorActionListener` hook
 * @param onChannelClick a row's tap: the old `setOnClickListener` on the row root
 * @param onShare the context menu's `share_with`
 * @param onOpenJoinDialog the context menu's `open_join_dialog`
 */
@Composable
fun ChannelDiscoveryScreen(
    rows: List<ChannelRow>,
    loading: Boolean,
    noResults: Boolean,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Unit,
    onChannelClick: (Room) -> Unit,
    onShare: (Room) -> Unit,
    onOpenJoinDialog: (Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row's long-press menu is open, keyed the way the list is keyed so a new answer cannot
    // move the menu onto a different channel.
    var openMenuKey by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        if (searchOpen) {
            ChannelSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearchClose = onSearchClose,
                onSearchSubmit = onSearchSubmit,
            )
        }
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (noResults) {
                NoResults()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = rows,
                        key = { index, row -> rowKey(row, index) },
                    ) { index, row ->
                        val key = rowKey(row, index)
                        ChannelResultRow(
                            row = row,
                            menuOpen = openMenuKey == key,
                            onOpen = { onChannelClick(row.room) },
                            onLongPress = { openMenuKey = key },
                            onMenuDismiss = { if (openMenuKey == key) openMenuKey = null },
                            onShare = {
                                openMenuKey = null
                                onShare(row.room)
                            },
                            onOpenJoinDialog = {
                                openMenuKey = null
                                onOpenJoinDialog(row.room)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One row: the room whose row [ChannelDiscoveryScreen] draws, and the avatar a preview cannot build. */
data class ChannelRow(val room: Room, val avatarable: Avatarable?)

/**
 * The `LazyColumn` key: the adapter's `DiffUtil` compared rows by `address`, so the address is the
 * identity, and a null address falls back to the row's position, which is also what `DiffUtil`'s
 * `areItemsTheSame` effectively did for one.
 */
private fun rowKey(row: ChannelRow, index: Int): String = row.room.address ?: "channel-$index"

/** One search result, exactly the deleted `item_channel_discovery.xml`'s relative arrangement. */
@Composable
private fun ChannelResultRow(
    row: ChannelRow,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenuDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenJoinDialog: () -> Unit,
) {
    val room = row.room
    val name = MessageFormat.format("{0} {1}", room.getName(), "[" + room.nusers + "]")
    val description = room.getDescription()
    val language = room.getLanguage()
    // The XML's own `8sp` start margin, read as sp against the font scale.
    val languageGap = with(LocalDensity.current) { 8.sp.toDp() }
    Box {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                    .padding(dimensionResource(R.dimen.list_padding)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelAvatar(
                avatarable = row.avatarable,
                loadSize = R.dimen.avatar,
                size = dimensionResource(R.dimen.avatar),
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.avatar_item_distance)))
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        // The XML baseline-aligned the language to this line, so both carry the
                        // baseline alignment.
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The old `language` view was GONE unless it held exactly two characters.
                    if (language != null && language.length == 2) {
                        Text(
                            text = language.uppercase(Locale.ENGLISH),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = languageGap).alignByBaseline(),
                            maxLines = 1,
                        )
                    }
                }
                if (!TextUtils.isEmpty(description)) {
                    Text(
                        text = description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = room.getRoom()?.asBareJid()?.toString() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_uri_with)) },
                onClick = onShare,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_join_dialog)) },
                onClick = onOpenJoinDialog,
            )
        }
    }
}

/**
 * The avatar as the XML drew it: an [AvatarView] in an [AndroidView], loaded the way it always was.
 * A null [Avatarable] is a cell's stand-in and draws a group mark instead of hosting a view.
 */
@Composable
private fun ChannelAvatar(
    avatarable: Avatarable?,
    loadSize: Int,
    size: Dp,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (avatarable == null) {
            Icon(
                painter = painterResource(R.drawable.ic_group_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AndroidView(
                factory = { context ->
                    AvatarView(context).also {
                        AvatarWorkerTask.loadAvatar(avatarable, it, loadSize)
                    }
                },
                update = { view -> AvatarWorkerTask.loadAvatar(avatarable, view, loadSize) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The field that replaces `actionview_search.xml`: the same hint, the same email keyboard, the same
 * search IME action, focused with the IME up as soon as it appears, and cleared by the same
 * affordance the action view's collapse was.
 */
@Composable
private fun ChannelSearchField(
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
        placeholder = { Text(stringResource(R.string.search_channels)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search_24dp),
                contentDescription = null,
            )
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
        keyboardActions =
            KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearchSubmit()
                }
            ),
    )
}

/**
 * `background_no_results` as Compose: the surface the layer-list painted plus its centred 96 dp
 * mark, which is `:data`'s own drawable and so keeps the picture the old list swapped in.
 */
@Composable
private fun NoResults() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(uk.xa0.tulkki.data.R.drawable.ic_cancel_96dp),
            contentDescription = null,
        )
    }
}
