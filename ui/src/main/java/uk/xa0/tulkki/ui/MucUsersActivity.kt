package uk.xa0.tulkki.ui

import android.content.Context
import android.content.IntentSender
import android.os.Bundle
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.color.MaterialColors
import com.google.common.collect.Collections2
import com.google.common.collect.Ordering
import org.openintents.openpgp.util.OpenPgpUtils
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.MucDetailsAction
import uk.xa0.tulkki.ui.util.MucDetailsContextMenuHelper
import uk.xa0.tulkki.ui.util.MucUserDropdownMenu
import uk.xa0.tulkki.ui.util.MucUserMenu
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.widget.AvatarView
import java.util.Locale

/**
 * The participants of a group chat: the room's roster, filtered by a search field.
 *
 * <p>**The layout is gone.** `activity_muc_users.xml` held a toolbar and a `RecyclerView`; the file
 * is deleted, the bar is the shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, and the
 * body is [MucUsersScreen]. `setSupportActionBar`, `configureActionBar`,
 * `Activities.setStatusAndNavigationBarColors`, the `R.menu.muc_users_activity` inflation and the
 * `actionview_search` `EditText` went with it: the menu's one live item is the chrome's own search
 * action and the field is [MucUsersScreen]'s own; `actionview_search.xml`, which it shared with the
 * other three screens, is deleted with them.
 *
 * <p>**The rows are Compose items now.** The old `RecyclerView` drew `item_contact.xml` through
 * `UserAdapter`; both are deleted, and this screen no longer inflates anything - every user becomes
 * one [MucUserRow] and one [MucUsersScreen] row. The row's own drawing is the deleted layout's: the
 * avatar under the presence dot, the display name, then the nick or the affiliation line, the hats
 * as tinted pills and the advanced-mode PGP key.
 *
 * <p>**The filter is the old filter.** [submitFilteredList] is `MucUsersActivity`'s own body,
 * `Collections2.filter` over the natural order, unchanged; only its input is the Compose field's
 * text rather than the collapsed action view's. The long-press menu is
 * [MucDetailsContextMenuHelper.visibleEntries]'s - the same arithmetic the deleted `PopupMenu` fed -
 * drawn as a Compose `DropdownMenu` in the row itself, so `muc_details_context.xml` is no longer
 * inflated here.
 */
class MucUsersActivity : XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnMucRosterUpdate,
    uk.xa0.tulkki.xmpp.services.OnAffiliationChanged {

    private var mConversation: Conversation? = null

    private var allUsers: ArrayList<MucOptions.User> = ArrayList()

    private var advancedMode: Boolean = false

    /** The rows the screen draws, in the order the filter left them. */
    private var userRows by mutableStateOf<List<MucUserRow>>(emptyList())

    /** Each drawn row's model, so a tap or a long-press finds the user it was built from. */
    private var usersByKey: Map<String, MucOptions.User> = emptyMap()

    private var searchOpen by mutableStateOf(false)

    private var query by mutableStateOf("")

    /**
     * The participant whose long-press menu is open, and the entries the host resolved for them: the
     * deleted `PopupMenu` + `muc_details_context.xml`, held as state because the screen draws the
     * menu where the row is.
     */
    private var userMenu by mutableStateOf<MucUserMenu?>(null)

    private var menuUser: MucOptions.User? = null

    /**
     * The search field's collapse: while it is open the system back button closes it instead of the
     * screen, which is what the framework's collapsed action view did for the menu item.
     */
    private val searchBackCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }

    override fun refreshUiReal() {}

    override fun onBackendConnected() {
        val uuid = intent?.getStringExtra("uuid")
        if (uuid != null) {
            mConversation = xmppConnectionService.findConversationByUuid(uuid) as Conversation?
        }
        loadAndSubmitUsers()
    }

    private fun loadAndSubmitUsers() {
        val conversation = mConversation
        if (conversation != null) {
            val options = conversation.getMucOptions()
            allUsers =
                options.getUsers(
                    true,
                    options.getSelf().getAffiliation().ranks(MucOptions.Affiliation.ADMIN),
                )
            allUsers.add(options.getSelf())
            submitFilteredList(query)
        }
    }

    private fun submitFilteredList(search: String?) {
        val filtered =
            if (TextUtils.isEmpty(search)) {
                Ordering.natural<MucOptions.User>().immutableSortedCopy(allUsers)
            } else {
                val needle = search.orEmpty().lowercase(Locale.getDefault())
                Ordering.natural<MucOptions.User>().immutableSortedCopy(
                    Collections2.filter(allUsers) { user ->
                        val name = user.getName()
                        val contact: Contact? = user.getContact()
                        (name != null && name.lowercase(Locale.getDefault()).contains(needle)) ||
                            (contact != null &&
                                contact.getDisplayName()
                                    .lowercase(Locale.getDefault())
                                    .contains(needle))
                    },
                )
            }
        applyRows(filtered)
    }

    /** The old `UserAdapter.submitList`, one [MucUserRow] per user and its key kept beside it. */
    private fun applyRows(users: List<MucOptions.User>) {
        val byKey = LinkedHashMap<String, MucOptions.User>()
        val rows = ArrayList<MucUserRow>(users.size)
        users.forEachIndexed { index, user ->
            val key = "${user.getFullJid() ?: user.getRealJid() ?: user.getNick()}@$index"
            byKey[key] = user
            rows.add(mucUserRow(key, user))
        }
        usersByKey = byKey
        userRows = rows
    }

    private fun mucUserRow(key: String, user: MucOptions.User): MucUserRow {
        val name = user.getNick()
        val contact = user.getContact()
        val displayName: String
        val secondary: String
        if (contact != null) {
            displayName = contact.getDisplayName()
            secondary = if (name != null && name != displayName) name else ""
        } else {
            displayName = name ?: ""
            secondary = ConferenceDetailsActivity.getStatus(this, user, advancedMode)
        }
        val hats = ArrayList<MucUserHat>()
        for (hat in user.getPseudoHats(this)) {
            hats.add(MucUserHat(hat.toString(), MaterialColors.harmonizeWithPrimary(this, hat.getColor())))
        }
        for (hat in user.getHats()) {
            hats.add(MucUserHat(hat.toString(), MaterialColors.harmonizeWithPrimary(this, hat.getColor())))
        }
        val showStatus =
            contact != null &&
                getPreferences()
                    .getBoolean("show_contact_status", resources.getBoolean(R.bool.show_contact_status)) &&
                contact.account?.isOnlineAndConnected() == true
        val presenceColor =
            if (showStatus && contact != null) {
                UIHelper.getColorForStatus(contact.shownStatus)
            } else {
                null
            }
        return MucUserRow(
            key = key,
            displayName = displayName,
            secondary = secondary,
            hats = hats,
            pgpKey =
                if (advancedMode && user.getPgpKeyId() != 0L) {
                    OpenPgpUtils.convertKeyIdToHex(user.getPgpKeyId())
                } else {
                    null
                },
            presenceColor = presenceColor,
            avatarable = user,
        )
    }

    private fun openUser(key: String) {
        val user = usersByKey[key] ?: return
        val contact = user.getContact()
        if (user.getRole() == MucOptions.Role.NONE && contact != null) {
            Toast.makeText(
                this,
                getString(R.string.user_has_left_conference, contact.getDisplayName()),
                Toast.LENGTH_SHORT,
            ).show()
        }
        highlightInMuc(user.getConversation(), user.getName())
    }

    /**
     * The row's long press: the deleted `PopupMenu` + `inflate(R.menu.muc_details_context)`, as the
     * Compose menu the screen draws at the row. The entries are the helper's own visibility
     * arithmetic; nothing is inflated.
     */
    private fun showUserMenu(key: String) {
        val user = usersByKey[key] ?: return
        val conversation = mConversation ?: return
        menuUser = user
        userMenu =
            MucUserMenu(
                key = key,
                entries = MucDetailsContextMenuHelper.visibleEntries(this, conversation, user),
            )
    }

    /** The menu's own dismissal, or the end of a selection: nothing is left for the next row. */
    private fun closeUserMenu() {
        userMenu = null
        menuUser = null
    }

    /** The old `key` `TextView`'s click, on the advanced-mode PGP key. */
    private fun openPgpKey(key: String) {
        val user = usersByKey[key] ?: return
        val service = xmppConnectionService ?: return
        val pgpEngine = service.getPgpEngine() ?: return
        val intent = pgpEngine.getIntentForKey(user.getPgpKeyId()) ?: return
        try {
            startIntentSenderForResult(
                intent.getIntentSender(),
                0,
                null,
                0,
                0,
                0,
                UiHost.installed().pgpStartIntentSenderOptions(),
            )
        } catch (ignored: IntentSender.SendIntentException) {
        }
    }

    override fun onMucRosterUpdate() {
        loadAndSubmitUsers()
    }

    private fun displayToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onAffiliationChangedSuccessful(jid: Jid) {}

    override fun onAffiliationChangeFailed(jid: Jid, resId: Int) {
        displayToast(getString(resId, jid.asBareJid().toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advancedMode = getPreferences().getBoolean("advanced_muc_mode", false)
        // The old action view collapsed on back; the chrome has no action view, so the dispatcher
        // does it. Registered before the composition so the state it toggles is never stale.
        onBackPressedDispatcher.addCallback(this, searchBackCallback)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // The title the XML action bar drew from the manifest label.
                title = stringResource(R.string.group_chat_members),
                onUp = { finish() },
                actions = {
                    IconButton(onClick = { openSearch() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                },
            ) {
                MucUsersScreen(
                    rows = userRows,
                    searchOpen = searchOpen,
                    query = query,
                    onQueryChange = { onQueryChanged(it) },
                    onSearchClose = { closeSearch() },
                    onOpen = { row -> openUser(row.key) },
                    userMenu = userMenu,
                    onMenu = { row -> showUserMenu(row.key) },
                    onMenuDismiss = { closeUserMenu() },
                    onMenuSelected = { action ->
                        val user = menuUser
                        closeUserMenu()
                        if (user != null) {
                            MucDetailsContextMenuHelper.onMucDetailsAction(
                                action,
                                user,
                                this@MucUsersActivity,
                                null,
                            )
                        }
                    },
                    onKey = { row -> openPgpKey(row.key) },
                )
            }
        }
    }

    /** The chrome's search icon: the old `action_search` action view, expanded. */
    private fun openSearch() {
        searchOpen = true
        searchBackCallback.isEnabled = true
    }

    /** The old action view's collapse: the field goes, its text clears and the list resets. */
    private fun closeSearch() {
        // `onMenuItemActionCollapse` hid the IME before clearing the field; the field leaves the
        // composition with this call, so the hide is the window's, not an `EditText`'s.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(
            window.decorView.windowToken,
            InputMethodManager.HIDE_IMPLICIT_ONLY,
        )
        searchOpen = false
        searchBackCallback.isEnabled = false
        query = ""
        submitFilteredList("")
    }

    /** The old `TextWatcher.afterTextChanged`, on the field's own text. */
    private fun onQueryChanged(value: String) {
        query = value
        submitFilteredList(value)
    }
}

/**
 * One participant as a row draws them: every decision the old `UserAdapter` made, already made.
 *
 * <p>The strings and the colours are here so the screen is a pure drawing of facts and the
 * screenshot cells can build a row without an account, a conversation or the avatar service.
 *
 * @param key the row's identity, and the handle back to the model the Activity holds.
 * @param displayName the first line: the contact's display name, or the nick when there is none.
 * @param secondary the second line: the nick when it differs from the display name, the affiliation
 *     line when there is no contact at all, and the empty box the old `contact_jid` drew otherwise.
 * @param hats the pseudo hats and the real hats, in the order the adapter added them, each already
 *     harmonised with the theme's primary as `MaterialColors.harmonizeWithPrimary` did.
 * @param pgpKey the advanced-mode key id, or `null` when the row draws none.
 * @param presenceColor the presence dot's colour, or `null` for a row that draws no dot.
 * @param avatarable what the avatar service loads, or `null` in a screenshot cell.
 */
data class MucUserRow(
    val key: String,
    val displayName: String,
    val secondary: String,
    val hats: List<MucUserHat>,
    val pgpKey: String?,
    val presenceColor: Int?,
    val avatarable: Avatarable?,
)

/** One hat's pill: its word and the colour the adapter tinted it with. */
data class MucUserHat(val label: String, val color: Int)

/**
 * The participants list: the search field while it is open, and one row per user under it.
 *
 * <p>Every size is the deleted layout's: the rows' padding is `list_padding`, the avatar `avatar`,
 * the gap after it `avatar_item_distance`, and the presence dot `presence_indicator_size` inset by
 * `presence_indicator_offset`. The hats are `item_tag.xml` in Compose - a 50 dp pill on the
 * harmonised colour, `labelMedium` in white, 7 dp by 1 dp of padding - and they keep the adapter's
 * rule that a row with hats hides its second line.
 *
 * <p>The list is a [LazyColumn], so the rows are Compose items and nothing is inflated. The avatar
 * is the one hosted view, exactly as the feed's rows host theirs: `AvatarView` and
 * `AvatarWorkerTask` are the avatar service's own consumers, and the dot is drawn beside it from
 * the two facts `PresenceIndicator` read.
 *
 * @param rows the users to draw, already filtered and sorted.
 * @param searchOpen whether the search field is drawn at all.
 * @param query the field's text, owned by the Activity.
 * @param onQueryChange every edit, which is the old `afterTextChanged`.
 * @param onSearchClose the field's clear button: the old action view's collapse.
 * @param onOpen a row's tap: the old `root.setOnClickListener`.
 * @param onMenu a row's long press, which opens the host's [MucUserMenu].
 * @param userMenu the participant menu the host resolved, or `null` while none is open.
 * @param onMenuDismiss the menu's own dismissal.
 * @param onMenuSelected the verb chosen from the menu.
 * @param onKey the PGP key's tap in advanced mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MucUsersScreen(
    rows: List<MucUserRow>,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onOpen: (MucUserRow) -> Unit,
    onMenu: (MucUserRow) -> Unit,
    userMenu: MucUserMenu?,
    onMenuDismiss: () -> Unit,
    onMenuSelected: (MucDetailsAction) -> Unit,
    onKey: (MucUserRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (searchOpen) {
            MucUsersSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearchClose = onSearchClose,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.key }) { row ->
                // The menu is drawn in the row it belongs to, which is where the deleted
                // `PopupMenu` should have pointed.
                Box(modifier = Modifier.fillMaxWidth()) {
                    MucUserRowItem(
                        row = row,
                        onOpen = { onOpen(row) },
                        onMenuOpen = { onMenu(row) },
                        onKey = { onKey(row) },
                    )
                    val menu = userMenu?.takeIf { it.key == row.key }
                    if (menu != null) {
                        MucUserDropdownMenu(
                            menu = menu,
                            onDismiss = onMenuDismiss,
                            onSelected = onMenuSelected,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One participant's row.
 *
 * <p>The two-line shape, the dot on the avatar's foot and the key line are `item_contact.xml`'s;
 * the hosted `AvatarView` is what the adapter loaded its drawable into. A row with no avatarable
 * (a screenshot cell) draws the plate the avatar will cover instead of a view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MucUserRowItem(
    row: MucUserRow,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onKey: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onMenuOpen)
                .padding(dimensionResource(R.dimen.list_padding)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(dimensionResource(R.dimen.avatar))) {
            val avatarable = row.avatarable
            if (avatarable == null) {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                AndroidView(
                    factory = { context ->
                        AvatarView(context).also {
                            AvatarWorkerTask.loadAvatar(avatarable, it, R.dimen.avatar)
                        }
                    },
                    update = { view ->
                        AvatarWorkerTask.loadAvatar(avatarable, view, R.dimen.avatar)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val dot = row.presenceColor
            if (dot != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomEnd)
                            .padding(
                                end = dimensionResource(R.dimen.presence_indicator_offset),
                                bottom = dimensionResource(R.dimen.presence_indicator_offset),
                            )
                            .size(dimensionResource(R.dimen.presence_indicator_size))
                            .clip(CircleShape)
                            .background(Color(dot))
                            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Column(
            modifier =
                Modifier.padding(start = dimensionResource(R.dimen.avatar_item_distance)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The adapter showed the hats in place of the second line; a row without hats kept the
            // line, empty or not, which is why this branch draws it even when it is blank.
            if (row.hats.isEmpty()) {
                Text(
                    text = row.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (hat in row.hats) {
                        Text(
                            text = hat.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1,
                            modifier =
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(Color(hat.color))
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            row.pgpKey?.let { key ->
                Text(
                    text = key,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                    maxLines = 1,
                    modifier = Modifier.clickable(onClick = onKey),
                )
            }
        }
    }
}

/** The participants' search field: the old `actionview_search` `EditText`, in Compose. */
@Composable
private fun MucUsersSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The old `onMenuItemActionExpand`: focus the field and raise the IME as soon as it appears.
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
        placeholder = { Text(stringResource(R.string.search_participants)) },
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
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}
