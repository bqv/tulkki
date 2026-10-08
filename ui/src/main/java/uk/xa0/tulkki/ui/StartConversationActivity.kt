package uk.xa0.tulkki.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.util.Pair
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.ImmutableList

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Bookmark
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.list.PickerList
import uk.xa0.tulkki.ui.list.PickerRow
import uk.xa0.tulkki.ui.list.pickerRow
import uk.xa0.tulkki.ui.startchat.StartChatAction
import uk.xa0.tulkki.ui.startchat.StartChatEvents
import uk.xa0.tulkki.ui.startchat.StartChatScreen
import uk.xa0.tulkki.ui.util.JidDialog
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.XmppUri

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The new-chat screen: the contact list, the list of bookmarked group chats, and the ways to reach a
 * conversation that does not exist yet.
 *
 * <p>**The layout and the two menus are gone.** `activity_start_conversation.xml`,
 * `menu/start_conversation.xml` and `menu/start_conversation_fab_submenu.xml` are deleted. The bar
 * is the shared chrome ([TulkkiChrome]): the XML bar's `action_search` action view and its
 * `action_scan_qr_code` icon are the chrome's own actions, and the overflow carries the same four
 * live items - `action_note_to_self`, `action_hide_offline`, `action_accounts`/`action_account`
 * (whichever `AccountUtils.showHideMenuItems` showed) and `action_settings`. `action_privacy_policy`
 * did not survive, and could not: `onCreateOptionsMenu` made it visible only when
 * `BuildConfig.PRIVACY_POLICY != null && UiHost.installed().playStoreFlavor()`, and this fork's
 * `playStoreFlavor()` answers `false` always, so the item was never shown. The four FAB items are
 * [StartChatAction] now, drawn by [StartChatScreen]; the ids that menu declared
 * (`discover_public_channels`, `create_public_channel`, `create_private_group_chat`,
 * `create_contact`) are the `EXTRA_GOTO` values `ConversationListActivity` still passes, so
 * `values/ids.xml` declares them rather than letting the deleted menu take them.
 *
 * <p>**The two lists are Compose now.** `ListView`, `ListItemAdapter` and `item_contact.xml` are
 * deleted: each page is a [PickerList] over its own resolved [PickerRow]s, and a filter moves them by
 * re-resolving, exactly as `notifyDataSetChanged` did. The reads and the filters (`filterContacts`,
 * `filterConferences`, the dynamic tags, the offline rule), the invite/JID handling ([Invite],
 * `handleJid`, `processViewIntent`), the permission dance, the dialogs and the navigation are
 * unchanged. The row's long press opens the same items the two row menus declared, in the same order
 * and under the same conditions, drawn through a `PopupMenu` anchored to the row; the screen builds
 * those items itself now ([configureRowMenu]) because the menu files are deleted, which is the route
 * `MucUsersActivity` already took. The `ListView`'s fast-scroll thumb and the focus the IME's search
 * action handed back to the list are the two things it took with it.
 *
 * <p>**The search field moved into the body**, and with it the whole action-view dance: the field
 * is [StartChatScreen]'s while `searchOpen`, the query is [searchQuery], and every edit filters as
 * the old `TextWatcher` did. `mInitialSearchValue`, `oneShotKeyboardSuppress` and the
 * `invalidateOptionsMenu` override that pushed the text back into the re-created action view are
 * gone with the action view - Compose keeps the field, so there is no text to preserve.
 *
 * <p>**One recorded liberty.** The deleted action view's collapse handler ran `navigateBack()` as
 * well as clearing the field, so collapsing the search finished the screen; that read as a defect,
 * not a behaviour, and the field's close affordance now only closes the search. The up arrow and
 * the system back button still navigate.
 */
class StartConversationActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate,
    uk.xa0.tulkki.xmpp.services.OnRosterUpdate,
    OnUpdateBlocklist,
    CreatePrivateGroupChatDialog.CreateConferenceDialogListener,
    JoinConferenceDialog.JoinConferenceDialogListener,
    CreatePublicChannelDialog.CreatePublicChannelDialogListener {

    private val REQUEST_SYNC_CONTACTS = 0x28cf
    private val REQUEST_CREATE_CONFERENCE = 0x39da
    private val pendingViewIntent = PendingItem<Intent>()
    @JvmField
    var contextItem: ListItem? = null
    private val contacts: MutableList<ListItem> = ArrayList()
    private val conferences: MutableList<ListItem> = ArrayList()

    // The two pages' rows: `ListItemAdapter` and `item_contact.xml` are deleted, so each page keeps
    // its own resolved rows and the pager's slots are `PickerList`s.
    private var contactRows by mutableStateOf<List<PickerRow>>(emptyList())
    private var conferenceRows by mutableStateOf<List<PickerRow>>(emptyList())
    private val mActivatedAccounts = ArrayList<String>()
    private val mRequestedContactsPermission = AtomicBoolean(false)
    private val mOpenedFab = AtomicBoolean(false)
    private var createdByViewIntent = false
    private var mPostponedActivityResult: Pair<Int, Intent?>? = null

    /** The JID dialog this screen launched, so a backend connect can refresh its known hosts. */
    private var enterJidSession: EnterJidDialog.Session? = null

    // The bar and the body were XML; they are Compose now, so what they draw is this class's state.
    private var searchOpen by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var searchTags by mutableStateOf<List<ListItem.Tag>>(emptyList())
    private var showDynamicTags by mutableStateOf(true)
    private var hideOfflineContacts by mutableStateOf(false)
    private var fabOpen by mutableStateOf(false)
    private var upEnabled by mutableStateOf(false)
    private var cameraAvailable by mutableStateOf(false)
    private var noteToSelfVisible by mutableStateOf(true)

    private val mAdhocConferenceCallback =
        object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> {
            override fun success(conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef) {
                runOnUiThread {
                    hideToast()
                    switchToConversation(conversation as Conversation?)
                }
            }

            override fun error(
                errorCode: Int,
                conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef?,
            ) {
                runOnUiThread { replaceToast(getString(errorCode)) }
            }

            override fun userInputRequired(
                pi: PendingIntent?,
                conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef,
            ) {}
        }

    /** What a gesture on the screen means; the screen itself decides nothing. */
    private val events =
        object : StartChatEvents {
            override fun onQueryChange(query: String) {
                searchQuery = query
                filter(query)
            }

            override fun onSearchClose() {
                closeSearch()
            }

            override fun onSearchSubmit(page: Int) {
                submitSearch(page)
            }

            override fun onTagChip(query: String) {
                searchQuery = query
                filter(query)
            }

            override fun onFabToggle(open: Boolean) {
                fabOpen = open
            }

            override fun onFabAction(action: StartChatAction) {
                performFabAction(action)
            }
        }

    override fun colorCodeAccounts(): Boolean {
        return mActivatedAccounts.size > 1
    }

    internal override fun hideToast() {
        mToast?.cancel()
    }

    internal override fun replaceToast(msg: String) {
        hideToast()
        mToast = Toast.makeText(this, msg, Toast.LENGTH_LONG)
        (mToast ?: throw NullPointerException()).show()
    }

    // Tulkki: 3.7 pair 9, part 15 - the island's ref, written fully qualified with no import
    // (rounds 151/161); the body reads nothing off the contact.
    public override fun onRosterUpdate(
        reason: uk.xa0.tulkki.xmpp.services.UpdateRosterReason,
        contact: uk.xa0.tulkki.xmpp.refs.ContactRef?,
    ) {
        this.refreshUi()
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAvailable = isCameraFeatureAvailable()

        val preferences = getPreferences()
        hideOfflineContacts = preferences.getBoolean("hide_offline", false)
        showDynamicTags =
            preferences.getBoolean(
                AppSettings.SHOW_DYNAMIC_TAGS,
                resources.getBoolean(R.bool.show_dynamic_tags),
            )
        val startSearching =
            preferences.getBoolean(
                "start_searching", resources.getBoolean(R.bool.start_searching))

        var initialSearch: String? = null
        val intent: Intent
        if (savedInstanceState == null) {
            intent = getIntent()
            initialSearch = intent.getStringExtra(EXTRA_TEXT_FILTER)
        } else {
            createdByViewIntent = savedInstanceState.getBoolean("created_by_view_intent", false)
            initialSearch = savedInstanceState.getString("search")
            intent =
                savedInstanceState.getParcelable<Intent>("intent")
                    ?: throw NullPointerException("intent")
        }

        if (intent.getBooleanExtra("init", false)) {
            pendingViewIntent.push(intent)
        } else if (intent.hasExtra(EXTRA_GOTO)) {
            pendingViewIntent.push(intent)
            setIntent(createLauncherIntent(this))
        } else if (intent.hasExtra(EXTRA_ACCOUNT_FILTER)) {
            pendingViewIntent.push(intent)
            setIntent(intent)
        }
        if (isViewIntent(intent) && pendingViewIntent.peek() == null) {
            pendingViewIntent.push(intent)
            createdByViewIntent = true
            setIntent(createLauncherIntent(this))
        } else if (startSearching && initialSearch == null) {
            initialSearch = ""
        }
        if (initialSearch != null) {
            searchOpen = true
            searchQuery = initialSearch
        }
        mRequestedContactsPermission.set(
            savedInstanceState != null &&
                savedInstanceState.getBoolean("requested_contacts_permission", false))
        mOpenedFab.set(
            savedInstanceState != null && savedInstanceState.getBoolean("opened_fab", false))
        refreshNoteToSelf()

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            val menu = ArrayList<ChromeMenuItem>()
            if (noteToSelfVisible) {
                menu.add(ChromeMenuItem(stringResource(R.string.note_to_self)) { openNoteToSelf() })
            }
            menu.add(
                ChromeMenuItem(
                    stringResource(R.string.hide_offline), checked = hideOfflineContacts) {
                    applyHideOfflineToggle()
                })
            // `AccountUtils.showHideMenuItems` showed exactly one of the two.
            if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
                menu.add(
                    ChromeMenuItem(stringResource(R.string.action_accounts)) {
                        AccountUtils.launchManageAccounts(this@StartConversationActivity)
                    })
            } else {
                menu.add(
                    ChromeMenuItem(stringResource(R.string.action_account)) {
                        switchToAccount(
                            AccountUtils.getFirst(AccountRegistry.get().getAccounts())
                                ?: throw NullPointerException())
                    })
            }
            menu.add(
                ChromeMenuItem(stringResource(R.string.action_settings)) {
                    startActivity(
                        Intent(
                            this@StartConversationActivity,
                            uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
                })
            TulkkiChrome(
                // The XML bar drew the manifest's label; the chrome reads it, and `setTitle` went
                // with the action bar.
                title = stringResource(R.string.title_activity_new_chat),
                // The up arrow `configureHomeButton` enabled; `finish` was never what it ran.
                onUp = if (upEnabled) ({ navigateBack() }) else null,
                menu = menu,
                actions = {
                    IconButton(onClick = { openSearch() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    if (cameraAvailable) {
                        IconButton(
                            onClick = { UriHandlerActivity.scan(this@StartConversationActivity) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_scanner_24dp),
                                contentDescription = stringResource(R.string.scan_qr_code),
                            )
                        }
                    }
                },
            ) {
                StartChatScreen(
                    searchOpen = searchOpen,
                    query = searchQuery,
                    tags = searchTags,
                    showTags = showDynamicTags,
                    contactsList = {
                        PickerList(
                            rows = contactRows,
                            onRowClick = { position -> openConversationForContact(position) },
                            onRowLongClick = { position, anchor ->
                                showRowMenu(contacts[position], anchor)
                            },
                            onTagClick = { tag -> showTagInSearch(tag) },
                        )
                    },
                    conferencesList = {
                        PickerList(
                            rows = conferenceRows,
                            onRowClick = { position -> openConversationForBookmark(position) },
                            onRowLongClick = { position, anchor ->
                                showRowMenu(conferences[position], anchor)
                            },
                            onTagClick = {},
                        )
                    },
                    fabActions = fabActions(),
                    fabOpen = fabOpen,
                    events = events,
                )
            }
        }
    }

    /** Both pages' rows re-resolved: the deleted `ListItemAdapter.refreshSettings`, twice. */
    private fun refreshSettings() {
        showDynamicTags =
            getPreferences()
                .getBoolean(
                    AppSettings.SHOW_DYNAMIC_TAGS,
                    resources.getBoolean(R.bool.show_dynamic_tags),
                )
        refreshContactRows()
        refreshConferenceRows()
    }

    /** The deleted `mContactsAdapter.notifyDataSetChanged`. */
    private fun refreshContactRows() {
        contactRows =
            contacts.mapIndexed { index, item -> pickerRow(this, item, index, showDynamicTags) }
    }

    /** The deleted `mConferenceAdapter.notifyDataSetChanged`. */
    private fun refreshConferenceRows() {
        conferenceRows =
            conferences.mapIndexed { index, item -> pickerRow(this, item, index, showDynamicTags) }
    }

    /** The four FAB items `inflateFab` read out of the deleted submenu, minus the Play flavour's. */
    private fun fabActions(): List<StartChatAction> =
        StartChatAction.entries.filter {
            !(UiHost.installed().playStoreFlavor() && it == StartChatAction.DiscoverChannels)
        }

    private fun performFabAction(action: StartChatAction) {
        val prefilled: String?
        if (isValidJid(searchQuery)) {
            prefilled = Jid.of(searchQuery).toString()
        } else {
            prefilled = null
        }
        when (action) {
            StartChatAction.DiscoverChannels -> {
                if (UiHost.installed().playStoreFlavor()) {
                    throw IllegalStateException(
                        "Channel discovery is not available on Google Play flavor")
                } else {
                    startActivity(Intent(this, ChannelDiscoveryActivity::class.java))
                }
            }
            StartChatAction.CreatePrivateGroupChat -> showCreatePrivateGroupChatDialog()
            StartChatAction.CreatePublicChannel -> showPublicChannelDialog()
            StartChatAction.CreateContact -> showCreateContactDialog(prefilled, null)
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        val pendingIntent = pendingViewIntent.peek()
        savedInstanceState.putParcelable(
            "intent", if (pendingIntent != null) pendingIntent else getIntent())
        savedInstanceState.putBoolean(
            "requested_contacts_permission", mRequestedContactsPermission.get())
        savedInstanceState.putBoolean("opened_fab", mOpenedFab.get())
        savedInstanceState.putBoolean("created_by_view_intent", createdByViewIntent)
        if (searchOpen) {
            savedInstanceState.putString("search", searchQuery)
        }
        super.onSaveInstanceState(savedInstanceState)
    }

    public override fun onStart() {
        super.onStart()
        refreshSettings()
        if (!createdByViewIntent) {
            if (askForContactsPermissions()) {
                return
            }
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATION,
            )
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (xmppConnectionServiceBound) {
            processViewIntent(intent)
        } else {
            pendingViewIntent.push(intent)
        }
        setIntent(createLauncherIntent(this))
    }

    protected fun openConversationForContact(position: Int) {
        openConversation(contacts[position])
    }

    protected fun openConversation(item: ListItem) {
        if (item is Contact) {
            openConversationForContact(item)
        } else {
            openConversationForBookmark(item as Bookmark)
        }
    }

    protected fun openConversationForContact(contact: Contact) {
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                contact.getAccount(), contact.getJid(), false, true) as Conversation
        SoftKeyboardUtils.hideSoftKeyboard(this)
        switchToConversation(conversation)
    }

    protected fun openConversationForBookmark(position: Int) {
        val bookmark = conferences[position] as Bookmark
        openConversationForBookmark(bookmark)
    }

    protected fun shareBookmarkUri() {
        shareAsChannel(this, contextItem!!.getJid().asBareJid().toString())
    }

    protected fun shareBookmarkUri(position: Int) {
        val bookmark = conferences[position] as Bookmark
        shareAsChannel(this, bookmark.getJid().asBareJid().toString())
    }

    protected fun openConversationForBookmark(bookmark: Bookmark) {
        val jid = bookmark.getFullJid()
        if (jid == null) {
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show()
            return
        }
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                bookmark.getAccount(), jid, true, true, true) as Conversation
        bookmark.setConversation(conversation)
        if (!bookmark.autojoin()) {
            bookmark.setAutojoin(true)
            xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark)
        }
        SoftKeyboardUtils.hideSoftKeyboard(this)
        switchToConversation(conversation)
    }

    protected fun openDetailsForContact() {
        switchToContactDetails(contextItem as Contact)
    }

    protected fun showQrForContact() {
        showQrCode("xmpp:" + contextItem!!.getJid().asBareJid().toString())
    }

    protected fun toggleContactBlock() {
        BlockContactDialog.show(this, contextItem as Contact)
    }

    protected fun deleteContact() {
        val contact = contextItem as Contact
        val builder = MaterialAlertDialogBuilder(this)
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setTitle(R.string.action_delete_contact)
        builder.setMessage(
            JidDialog.style(this, R.string.remove_contact_text, contact.getJid().toString()))
        builder.setPositiveButton(
            R.string.delete,
            { dialog, which ->
                xmppConnectionService.deleteContactOnServer(contact)
                filter(searchQuery)
            })
        builder.create().show()
    }

    protected fun deleteConference() {
        val bookmark = contextItem as Bookmark
        val conversation = bookmark.getConversation()
        val hasConversation = conversation != null
        val builder = MaterialAlertDialogBuilder(this)
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setTitle(R.string.delete_bookmark)
        if (hasConversation) {
            builder.setMessage(
                JidDialog.style(
                    this,
                    R.string.remove_bookmark_and_close,
                    bookmark.getJid().toString()))
        } else {
            builder.setMessage(
                JidDialog.style(this, R.string.remove_bookmark, bookmark.getJid().toString()))
        }
        builder.setPositiveButton(
            if (hasConversation) R.string.delete_and_close else R.string.delete,
            { dialog, which ->
                bookmark.setConversation(null as Conversation?)
                val account = bookmark.getAccount()
                xmppConnectionService.deleteBookmark(account, bookmark)
                if (conversation != null) {
                    xmppConnectionService.archiveConversation(conversation)
                }
                filter(searchQuery)
            })
        builder.create().show()
    }

    protected fun showCreateContactDialog(prefilledJid: String?, invite: Invite?) {
        enterJidSession =
            EnterJidDialog.show(
                activity = this,
                activatedAccounts = mActivatedAccounts,
                title = getString(R.string.start_chat),
                positiveButton = getString(R.string.write),
                secondaryButton = getString(R.string.call),
                prefilledJid = prefilledJid,
                account = invite?.account,
                allowEditJid = invite == null || !invite.hasFingerprints(),
                showBookmarkCheckbox = true,
                sanityCheck = EnterJidDialog.SanityCheck.ALLOW_MUC,
            ) { accountJid, contactJid, call, save ->
                handleEnteredJid(accountJid, contactJid, call, save, invite)
            }
    }

    /**
     * The dialog's answer for this host: the body the deleted anonymous listener held, with its
     * `return`s. The one deferred branch - the ad-hoc MUC check - dismisses the dialog itself.
     */
    private fun handleEnteredJid(
        accountJid: Jid,
        contactJid: Jid,
        call: Boolean,
        save: Boolean,
        invite: Invite?,
    ): Boolean {
        if (!xmppConnectionServiceBound) {
            return false
        }

        val account = AccountRegistry.get().findAccountByJid(accountJid) ?: return true
        val contact = account.getRoster().getContact(contactJid)

        if (invite?.getName() != null) {
            contact.setServerName(invite.getName())
        }

        if (contact.isSelf() || contact.showInRoster()) {
            switchToConversationDoNotAppend(contact, invite?.getBody(), if (call) "call" else null)
            return true
        }

        xmppConnectionService.checkIfMuc(account, contactJid) { isMuc ->
            runOnUiThread {
                if (isMuc) {
                    if (save) {
                        var bookmark = account.getBookmark(contactJid)
                        if (bookmark != null) {
                            openConversationForBookmark(bookmark)
                        } else {
                            bookmark = Bookmark(account, contactJid.asBareJid())
                            bookmark.setAutojoin(
                                getBooleanPreference("autojoin", R.bool.autojoin))
                            val nick = contactJid.getResource()
                            if (nick != null && !nick.isEmpty() &&
                                nick != MucOptions.defaultNick(account)) {
                                bookmark.setNick(nick)
                            }
                            xmppConnectionService.createBookmark(account, bookmark)
                            val conversation =
                                xmppConnectionService.findOrCreateConversation(
                                    account, contactJid, true, true, true) as Conversation
                            bookmark.setConversation(conversation)
                            switchToConversationDoNotAppend(conversation, invite?.getBody())
                        }
                    } else {
                        val conversation =
                            xmppConnectionService.findOrCreateConversation(
                                account, contactJid, true, true, true) as Conversation
                        switchToConversationDoNotAppend(conversation, invite?.getBody())
                    }
                } else {
                    if (save) {
                        val preAuth = invite?.getParameter(XmppUri.PARAMETER_PRE_AUTH)
                        xmppConnectionService.createContact(contact, true, preAuth)
                        if (invite != null && invite.hasFingerprints()) {
                            xmppConnectionService.verifyFingerprints(
                                contact, invite.getFingerprints())
                        }
                    }
                    switchToConversationDoNotAppend(
                        contact, invite?.getBody(), if (call) "call" else null)
                }

                enterJidSession?.dismiss()
            }
        }

        return false
    }

    protected fun showJoinConferenceDialog(prefilledJid: String, invite: Invite) {
        JoinConferenceDialog.show(
            this,
            prefilledJid,
            invite.getParameter("password"),
            mActivatedAccounts,
            knownConferenceHosts(),
            this,
        )
    }

    private fun showCreatePrivateGroupChatDialog() {
        CreatePrivateGroupChatDialog.show(this, mActivatedAccounts, this)
    }

    private fun showPublicChannelDialog() {
        CreatePublicChannelDialog.show(
            this,
            mActivatedAccounts,
            knownConferenceHosts(),
            this,
        )
    }

    /** The known MUC hosts the address fields suggest; the dialogs refreshed theirs on connect. */
    private fun knownConferenceHosts(): List<String> =
        if (xmppConnectionServiceBound) {
            xmppConnectionService.getKnownConferenceHosts().toList()
        } else {
            emptyList()
        }

    protected fun switchToConversation(contact: Contact) {
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                contact.getAccount(), contact.getJid(), false, true) as Conversation
        switchToConversation(conversation)
    }

    protected fun switchToConversationDoNotAppend(contact: Contact, body: String?) {
        switchToConversationDoNotAppend(contact, body, null)
    }

    protected fun switchToConversationDoNotAppend(
        contact: Contact,
        body: String?,
        postInit: String?,
    ) {
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                contact.getAccount(), contact.getJid(), false, true) as Conversation
        switchToConversation(conversation, body, false, null, false, true, postInit)
    }

    /** The chrome's search action: the old `action_search` action view, expanded. */
    private fun openSearch() {
        searchOpen = true
        if (fabOpen) {
            fabOpen = false
        }
    }

    /** The field's close affordance: the old action view's collapse, without its `navigateBack`. */
    private fun closeSearch() {
        SoftKeyboardUtils.hideSoftKeyboard(this)
        searchOpen = false
        searchQuery = ""
        filter(null)
    }

    /** A tag of a row: the field opens on the tag, exactly as the old action view expanded on it. */
    private fun showTagInSearch(tag: String) {
        searchOpen = true
        searchQuery = tag
        filter(tag)
    }

    /** The IME's search action: the old `OnEditorActionListener`, on the page it ran on. */
    private fun submitSearch(page: Int) {
        if (page == 0) {
            if (contacts.size == 1) {
                openConversation(contacts[0])
                return
            } else if (contacts.isEmpty() && conferences.size == 1) {
                openConversationForBookmark(conferences[0] as Bookmark)
                return
            }
        } else {
            if (conferences.size == 1) {
                openConversationForBookmark(conferences[0] as Bookmark)
                return
            } else if (conferences.isEmpty() && contacts.size == 1) {
                openConversation(contacts[0])
                return
            }
        }
        SoftKeyboardUtils.hideSoftKeyboard(this)
        // The deleted `ListView.requestFocus` put the focus back on the page; the Compose list is
        // not focusable, and the keyboard is already gone, so nothing takes its place.
    }

    public override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && !event.isLongPress()) {
            openSearch()
            return true
        }
        val c = event.getUnicodeChar()
        if (c > 32) {
            if (!searchOpen) {
                openSearch()
                searchQuery = searchQuery + c.toChar()
                filter(searchQuery)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == RESULT_OK) {
            if (xmppConnectionServiceBound) {
                this.mPostponedActivityResult = null
                if (requestCode == REQUEST_CREATE_CONFERENCE) {
                    val account = extractAccount(intent)
                    val name = intent?.getStringExtra(ChooseContactActivity.EXTRA_GROUP_CHAT_NAME)
                    val jids = ChooseContactActivity.extractJabberIds(intent ?: throw NullPointerException())
                    if (account != null && jids.size > 0) {
                        // This hardcodes cheogram.com and is in general a terrible hack
                        // Ideally this would be based around XEP-0033 but until we think of a good
                        // fallback behaviour we keep using this gross commas thing
                        if (jids.all { it.getDomain().toString() == "cheogram.com" }) {
                            AlertDialog.Builder(this)
                                .setMessage(
                                    "You appear to be creating a group with only SMS contacts. " +
                                        "Would you like to create a channel or an MMS group text?")
                                .setNeutralButton("Channel") { d, w ->
                                    if (xmppConnectionService.createAdhocConference(
                                            account, name, jids, mAdhocConferenceCallback)) {
                                        mToast = Toast.makeText(
                                            this, R.string.creating_conference, Toast.LENGTH_LONG)
                                        (mToast ?: throw NullPointerException()).show()
                                    }
                                }.setPositiveButton("Group Text") { d, w ->
                                    val groupJid = Jid.ofLocalAndDomain(
                                        jids.map { it.getLocal() ?: throw NullPointerException() }.sorted().joinToString(","),
                                        "cheogram.com")
                                    val group = account.getRoster().getContact(groupJid)
                                    if (!name.isNullOrEmpty()) group.setServerName(name)
                                    xmppConnectionService.createContact(group, true)
                                    switchToConversation(group)
                                }.create().show()
                        } else {
                            if (xmppConnectionService.createAdhocConference(
                                    account, name, jids, mAdhocConferenceCallback)) {
                                mToast = Toast.makeText(
                                    this, R.string.creating_conference, Toast.LENGTH_LONG)
                                (mToast ?: throw NullPointerException()).show()
                            }
                        }
                    }
                }
            } else {
                this.mPostponedActivityResult = Pair(requestCode, intent)
            }
        }
        // Tulkki: 3.7 pair 9, part 15 - the Java handed `requestCode` twice; `FragmentActivity`
        // reads the second argument as the fragments' resultCode, so the fix is `resultCode`.
        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun askForContactsPermissions(): Boolean {
        if (!UiHost.installed().contactListIntegration(this)) {
            return false
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (mRequestedContactsPermission.compareAndSet(false, true)) {
            val permissionBuilder = ImmutableList.Builder<String>()
            permissionBuilder.add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionBuilder.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val permission = permissionBuilder.build().toTypedArray()
            val consent =
                PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    .getString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, null)
            val requiresConsent = "agreed" != consent
            if (requiresConsent && "declined" == consent) {
                Log.d(
                    Config.LOGTAG,
                    "not asking for contacts permission because consent has been declined")
                return false
            }
            if (requiresConsent ||
                shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                val builder = MaterialAlertDialogBuilder(this)
                val requestPermission = AtomicBoolean(false)
                if (UiHost.installed().quicksy()) {
                    builder.setTitle(R.string.quicksy_wants_your_consent)
                    builder.setMessage(
                        Html.fromHtml(getString(R.string.sync_with_contacts_quicksy_static)))
                } else {
                    builder.setTitle(R.string.sync_with_contacts)
                    builder.setMessage(
                        getString(
                            R.string.sync_with_contacts_long,
                            BuildConfig.APP_NAME))
                }
                val confirmButtonText: Int
                if (requiresConsent) {
                    confirmButtonText = R.string.agree_and_continue
                } else {
                    confirmButtonText = R.string.next
                }
                builder.setPositiveButton(
                    confirmButtonText,
                    { dialog, which ->
                        if (requiresConsent) {
                            PreferenceManager.getDefaultSharedPreferences(
                                applicationContext)
                                .edit()
                                .putString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, "agreed")
                                .apply()
                        }
                        if (requestPermission.compareAndSet(false, true)) {
                            requestPermissions(permission, REQUEST_SYNC_CONTACTS)
                        }
                    })
                if (requiresConsent) {
                    builder.setNegativeButton(
                        R.string.decline,
                        { dialog, which ->
                            PreferenceManager.getDefaultSharedPreferences(
                                applicationContext)
                                .edit()
                                .putString(
                                    PREF_KEY_CONTACT_INTEGRATION_CONSENT,
                                    "declined")
                                .apply()
                        })
                } else {
                    builder.setOnDismissListener(
                        { dialog ->
                            if (requestPermission.compareAndSet(false, true)) {
                                requestPermissions(permission, REQUEST_SYNC_CONTACTS)
                            }
                        })
                }
                builder.setCancelable(requiresConsent)
                val dialog = builder.create()
                dialog.setCanceledOnTouchOutside(requiresConsent)
                dialog.setOnShowListener { dialogInterface ->
                    val tv: TextView? = dialog.findViewById(android.R.id.message)
                    if (tv != null) {
                        tv.setMovementMethod(LinkMovementMethod.getInstance())
                    }
                }
                dialog.show()
            } else {
                requestPermissions(permission, REQUEST_SYNC_CONTACTS)
            }
        }
        return true
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults)
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    xmppConnectionService.loadPhoneContacts()
                    xmppConnectionService.startContactObserver()
                }
            }
        }
    }

    private fun configureHomeButton() {
        val service = xmppConnectionService ?: return
        upEnabled = !createdByViewIntent && !service.isConversationListEmpty(null)
    }

    /** `onCreateOptionsMenu`'s own rule for the note-to-self item, kept for the chrome's. */
    private fun refreshNoteToSelf() {
        noteToSelfVisible =
            !(xmppConnectionService != null && AccountRegistry.get().getAccounts().size != 1)
    }

    protected override fun onBackendConnected() {
        if (UiHost.installed().contactListIntegration(this) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED)) {
            xmppConnectionService.getContactListSyncService().considerSyncBackground(false)
        }
        val postponed = mPostponedActivityResult
        if (postponed != null) {
            onActivityResult(postponed.first, RESULT_OK, postponed.second)
            this.mPostponedActivityResult = null
        }
        val popped = pendingViewIntent.pop()
        val intent = popped ?: getIntent()
        this.mActivatedAccounts.clear()
        val accountFilterJid = intent.getStringExtra(EXTRA_ACCOUNT_FILTER)
        if (accountFilterJid == null) {
            this.mActivatedAccounts.addAll(
                AccountUtils.getEnabledAccounts(AccountRegistry.get().getAccounts()))
        } else {
            this.mActivatedAccounts.add(accountFilterJid)
        }
        configureHomeButton()
        refreshNoteToSelf()

        val goTo = intent.getIntExtra(EXTRA_GOTO, 0)
        if (goTo == R.id.discover_public_channels) {
            startActivity(Intent(this, ChannelDiscoveryActivity::class.java))
        } else if (goTo == R.id.create_private_group_chat) {
            showCreatePrivateGroupChatDialog()
        } else if (goTo == R.id.create_public_channel) {
            showPublicChannelDialog()
        } else if (goTo == R.id.create_contact) {
            showCreateContactDialog(null, null)
        }

        // Tulkki: a commented-out "Better Onboarding later" block stood here that recognised the
        // onboarding account by `Config.ONBOARDING_DOMAIN`; that constant is deleted, so the block
        // could not be restored as written and is removed with it.

        if (processViewIntent(intent)) {
            filter(null)
        } else {
            filter(searchQuery)
        }
        enterJidSession?.onBackendConnected()
        if (AccountUtils.hasEnabledAccounts(AccountRegistry.get().getAccounts()) &&
            this.contacts.size == 0 &&
            this.conferences.size == 0 &&
            mOpenedFab.compareAndSet(false, true)) {
            fabOpen = true
        }
    }

    protected fun processViewIntent(intent: Intent): Boolean {
        val inviteUri = intent.getStringExtra(EXTRA_INVITE_URI)
        if (inviteUri != null) {
            val invite = Invite(inviteUri)
            invite.account = intent.getStringExtra(EXTRA_ACCOUNT)
            if (invite.isValidJid()) {
                return invite.invite()
            }
        }
        val action = intent.getAction() ?: return false
        when (action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val uri = intent.getData()
                if (uri != null) {
                    val invite =
                        Invite(uri, intent.getBooleanExtra("scanned", false))
                    invite.account = intent.getStringExtra(EXTRA_ACCOUNT)
                    invite.forceDialog = intent.getBooleanExtra("force_dialog", false)
                    return invite.invite()
                } else {
                    return false
                }
            }
        }
        return false
    }

    private fun handleJid(invite: Invite): Boolean {
        // Tulkki: 3.7 pair 9, part 15 - `findContacts` answers the island's refs now; the fully
        // qualified type argument keeps this file from importing one (rounds 151/161).
        val contacts: List<uk.xa0.tulkki.xmpp.refs.ContactRef> =
            xmppConnectionService.findContacts(invite.getJid() ?: throw NullPointerException(), invite.account)
        val muc =
            xmppConnectionService.findFirstMuc(invite.getJid() ?: throw NullPointerException(), invite.account) as Conversation?
        if (invite.isAction(XmppUri.ACTION_JOIN) || (contacts.isEmpty() && muc != null)) {
            if (muc != null && !invite.forceDialog) {
                if (invite.getParameter("password") != null) {
                    xmppConnectionService.providePasswordForMuc(
                        muc, invite.getParameter("password"))
                }
                switchToConversationDoNotAppend(muc, invite.getBody())
                return true
            } else {
                showJoinConferenceDialog(invite.getJid()!!.asBareJid().toString(), invite)
                return false
            }
        } else if (contacts.isEmpty()) {
            showCreateContactDialog(invite.getJid()!!.toString(), invite)
            return false
        } else if (contacts.size == 1) {
            val contact = contacts[0] as Contact
            if (!invite.isSafeSource() && invite.hasFingerprints()) {
                displayVerificationWarningDialog(contact, invite)
            } else {
                if (invite.hasFingerprints()) {
                    if (xmppConnectionService.verifyFingerprints(
                            contact, invite.getFingerprints())) {
                        Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                if (invite.account != null) {
                    xmppConnectionService.getShortcutService().report(contact)
                }
                switchToConversationDoNotAppend(contact, invite.getBody())
            }
            return true
        } else {
            searchOpen = true
            searchQuery = invite.getJid()!!.toString()
            filter(searchQuery)
            return true
        }
    }

    private fun displayVerificationWarningDialog(contact: Contact, invite: Invite) {
        // `dialog_verify_fingerprints.xml` is gone: the warning - the contact's bare JID monospaced,
        // as `JidDialog.style` drew it - and its checkbox are `VerifyFingerprintsDialog`, and the
        // three answers are the same code. The outside tap is still refused and the back gesture
        // still finishes the screen, which is the dialog's `onDismiss` - the old
        // `setOnCancelListener` and the Cancel button in one.
        val bareJid = contact.getJid().asBareJid().toString()
        showTulkkiDialog { dismiss ->
            VerifyFingerprintsDialog(
                warningRes = R.string.verifying_omemo_keys_trusted_source,
                confirmRes = R.string.confirm,
                warningArgs = listOf(bareJid, contact.getDisplayName()),
                onDismiss = {
                    dismiss()
                    finish()
                },
                onConfirm = { trusted ->
                    dismiss()
                    if (trusted && invite.hasFingerprints()) {
                        xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints())
                    }
                    switchToConversationDoNotAppend(contact, invite.getBody())
                },
            )
        }
    }

    protected fun filter(needle: String?) {
        if (xmppConnectionServiceBound) {
            synchronized(this.contacts) { this.filterContacts(needle) }
            this.filterConferences(needle)
        }
    }

    protected fun filterContacts(needle: String?) {
        this.contacts.clear()
        val tags = ArrayList<ListItem.Tag>()
        val accounts = ArrayList<Account>()
        for (account in AccountRegistry.get().getAccounts()) {
            if (mActivatedAccounts.contains(account.getJid().asBareJid().toString())) {
                accounts.add(account)
            }
        }
        for (account in accounts) {
            for (contact in account.getRoster().getContacts()) {
                val s = contact.shownStatus
                if (contact.showInContactList() &&
                    contact.match(this, needle) &&
                    (!this.hideOfflineContacts ||
                        (needle != null && needle.trim { it <= ' ' }.isNotEmpty()) ||
                        s.compareTo(Presence.Status.OFFLINE) < 0)) {
                    this.contacts.add(contact)
                    tags.addAll(contact.getTags(this))
                }
            }

            val self = Contact(account.getSelfContact())
            self.setSystemName(getString(R.string.note_to_self))
            if (self.match(this, needle)) {
                this.contacts.add(self)
            }

            for (bookmark in account.getBookmarks()) {
                if (bookmark.match(this, needle)) {
                    this.contacts.add(bookmark)
                    tags.addAll(bookmark.getTags(this))
                }
            }
        }

        val counts = LinkedHashMap<ListItem.Tag, Int>()
        for (tag in tags) {
            counts[tag] = (counts[tag] ?: 0) + 1
        }
        // Rebuilt without the Java `Collectors.toMap` stream: descending count, then ascending name.
        val sorted = counts.entries.sortedWith(
            compareByDescending<Map.Entry<ListItem.Tag, Int>> { it.value }
                .thenBy { it.key.name })
        searchTags = sorted.map { it.key }
        contacts.sort()

        refreshContactRows()
    }

    protected fun filterConferences(needle: String?) {
        this.conferences.clear()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                for (bookmark in account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        this.conferences.add(bookmark)
                    }
                }
            }
        }
        conferences.sort()
        refreshConferenceRows()
    }

    public override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        refreshUi()
    }

    protected override fun refreshUiReal() {
        filter(searchQuery)
        configureHomeButton()
        refreshNoteToSelf()
        showDynamicTags =
            getPreferences().getBoolean(
                AppSettings.SHOW_DYNAMIC_TAGS,
                resources.getBoolean(R.bool.show_dynamic_tags),
            )
    }

    public override fun onBackPressed() {
        if (fabOpen) {
            fabOpen = false
            return
        }
        navigateBack()
    }

    private fun navigateBack() {
        if (!createdByViewIntent &&
            xmppConnectionService != null &&
            !xmppConnectionService.isConversationListEmpty(null)) {
            val intent = Intent(this, ConversationListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
        }
        finish()
    }

    public override fun onCreateDialogPositiveClick(account: Account?, name: String) {
        if (!xmppConnectionServiceBound || account == null) {
            return
        }
        val intent = Intent(applicationContext, ChooseContactActivity::class.java)
        intent.putExtra(ChooseContactActivity.EXTRA_SHOW_ENTER_JID, true)
        intent.putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true)
        intent.putExtra(ChooseContactActivity.EXTRA_GROUP_CHAT_NAME, name.trim { it <= ' ' })
        intent.putExtra(
            XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
        intent.putExtra(ChooseContactActivity.EXTRA_TITLE_RES_ID, R.string.choose_participants)
        startActivityForResult(intent, REQUEST_CREATE_CONFERENCE)
    }

    public override fun onJoinDialogPositiveClick(account: Account, jid: Jid, password: String?) {
        if (!xmppConnectionServiceBound) {
            return
        }
        val existingBookmark = account.getBookmark(jid)
        if (existingBookmark != null) {
            openConversationForBookmark(existingBookmark)
        } else {
            val bookmark = Bookmark(account, jid.asBareJid())
            bookmark.setAutojoin(true)
            val nick = jid.getResource()
            if (nick != null && !nick.isEmpty() && nick != MucOptions.defaultNick(account)) {
                bookmark.setNick(nick)
            }
            xmppConnectionService.createBookmark(account, bookmark)
            val conversation =
                xmppConnectionService.findOrCreateConversation(
                    account, jid, true, true, null, true, password) as Conversation
            bookmark.setConversation(conversation)
            switchToConversation(conversation)
        }
    }

    public override fun onConversationUpdate() {
        refreshUi()
    }

    public override fun onCreatePublicChannel(account: Account, name: String, address: Jid) {
        mToast = Toast.makeText(this, R.string.creating_channel, Toast.LENGTH_LONG)
        (mToast ?: throw NullPointerException()).show()
        xmppConnectionService.createPublicChannel(
            account,
            name,
            address,
            object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> {
                override fun success(conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef) {
                    runOnUiThread {
                        hideToast()
                        switchToConversation(conversation as Conversation?)
                    }
                }

                override fun error(
                    errorCode: Int,
                    conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef?,
                ) {
                    runOnUiThread {
                        replaceToast(getString(errorCode))
                        switchToConversation(conversation as Conversation?)
                    }
                }

                override fun userInputRequired(
                    pi: PendingIntent?,
                    conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef,
                ) {}
            })
    }

    /**
     * One row's long press: the deleted `registerForContextMenu` menu, as a `PopupMenu` anchored to
     * the row that was pressed. The deleted `MyListFragment`'s own `onCreateContextMenu` built it,
     * on whichever item sat at that position - a page holds contacts and bookmarks both - so the
     * item is [contextItem] and [configureRowMenu] branches exactly as it did.
     */
    private fun showRowMenu(item: ListItem, anchor: View) {
        contextItem = item
        val popupMenu = PopupMenu(this, anchor)
        configureRowMenu(item, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { handleRowMenu(it) }
        popupMenu.show()
    }

    /**
     * The deleted `onCreateContextMenu`'s body, with the two menus it inflated now built here: the
     * items, their order and the conditions that hid them are the deleted row menus' own, and an item
     * whose condition failed is not added at all, which is what `setVisible(false)` meant. The ids are
     * `values/ids.xml`'s, because the menu files that declared them are gone.
     */
    private fun configureRowMenu(item: ListItem, menu: Menu) {
        if (item is Bookmark) {
            val conversation = item.getConversation()
            menu.add(
                Menu.NONE,
                R.id.context_delete_conference,
                0,
                if (conversation != null) R.string.delete_and_close else R.string.delete_bookmark,
            )
            if (conversation == null || !conversation.isPrivateAndNonAnonymous()) {
                menu.add(Menu.NONE, R.id.context_share_uri, 1, R.string.share_uri_with)
            }
        } else if (item is Contact) {
            if (!item.isSelf()) {
                menu.add(Menu.NONE, R.id.context_contact_details, 0, R.string.view_contact_details)
            }
            menu.add(Menu.NONE, R.id.context_show_qr, 1, R.string.show_qr_code)
            val xmpp: XmppConnection? = item.getAccount().getXmppConnection()
            if (xmpp != null && xmpp.getFeatures().blocking() && !item.isSelf()) {
                menu.add(
                    Menu.NONE,
                    R.id.context_contact_block_unblock,
                    2,
                    if (item.isBlocked()) R.string.unblock_contact else R.string.block_contact,
                )
            }
            if (item.showInRoster() && !item.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                menu.add(Menu.NONE, R.id.context_delete_contact, 3, R.string.delete_contact)
            }
        }
    }

    /** The deleted `onContextItemSelected`'s body, on the item the popup reports. */
    private fun handleRowMenu(item: MenuItem): Boolean {
        val menuId = item.getItemId()
        if (menuId == R.id.context_contact_details) {
            openDetailsForContact()
        } else if (menuId == R.id.context_show_qr) {
            showQrForContact()
        } else if (menuId == R.id.context_contact_block_unblock) {
            toggleContactBlock()
        } else if (menuId == R.id.context_delete_contact) {
            deleteContact()
        } else if (menuId == R.id.context_share_uri) {
            shareBookmarkUri()
        } else if (menuId == R.id.context_delete_conference) {
            deleteConference()
        } else {
            return false
        }
        return true
    }

    /** The chrome's note-to-self item: the old `action_note_to_self`, on the one account. */
    private fun openNoteToSelf() {
        val accounts = AccountRegistry.get().getAccounts()
        if (accounts.size == 1) {
            val self = Contact(accounts[0].getSelfContact())
            openConversationForContact(self)
        }
    }

    /** The chrome's hide-offline item: the old `action_hide_offline` toggle, its check mark on the
     * chrome item. */
    private fun applyHideOfflineToggle() {
        hideOfflineContacts = !hideOfflineContacts
        getPreferences().edit().putBoolean("hide_offline", hideOfflineContacts).apply()
        filter(searchQuery)
    }

    inner class Invite : XmppUri {

        var account: String? = null

        var forceDialog = false

        constructor(uri: String) : super(uri)

        constructor(uri: Uri, safeSource: Boolean) : super(uri, safeSource)

        fun invite(): Boolean {
            if (!isValidJid()) {
                Toast.makeText(
                    this@StartConversationActivity,
                    R.string.invalid_jid,
                    Toast.LENGTH_SHORT)
                    .show()
                return false
            }
            if (getJid() != null) {
                return handleJid(this)
            }
            return false
        }
    }

    companion object {
        private const val PREF_KEY_CONTACT_INTEGRATION_CONSENT =
            "contact_list_integration_consent"

        const val EXTRA_INVITE_URI = "uk.xa0.app.invite_uri"
        const val EXTRA_ACCOUNT_FILTER = "account_filter"
        const val EXTRA_TEXT_FILTER = "text_filter"
        const val EXTRA_GOTO = "goto"

        @JvmStatic
        fun launch(context: Context) {
            launch(context, null, null, 0)
        }

        @JvmStatic
        fun launch(context: Context, account: Account?, q: String?, goTo: Int) {
            val intent = Intent(context, StartConversationActivity::class.java)
            if (account != null) {
                intent.putExtra(
                    EXTRA_ACCOUNT_FILTER,
                    account.getJid().asBareJid().toString())
            }
            if (q != null) {
                intent.putExtra(EXTRA_TEXT_FILTER, q)
            }
            intent.putExtra(EXTRA_GOTO, goTo)
            context.startActivity(intent)
        }

        private fun createLauncherIntent(context: Context): Intent {
            val intent = Intent(context, StartConversationActivity::class.java)
            intent.setAction(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            return intent
        }

        private fun isViewIntent(i: Intent?): Boolean {
            return i != null &&
                (Intent.ACTION_VIEW == i.getAction() ||
                    Intent.ACTION_SENDTO == i.getAction() ||
                    i.hasExtra(EXTRA_INVITE_URI))
        }

        @JvmStatic
        fun isValidJid(input: String?): Boolean {
            return try {
                val jid = Jid.ofUserInput(input ?: throw NullPointerException())
                !jid.isDomainJid()
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        @JvmStatic
        fun shareAsChannel(context: Context, address: String) {
            val shareIntent = Intent()
            shareIntent.setAction(Intent.ACTION_SEND)
            shareIntent.putExtra(Intent.EXTRA_TEXT, "xmpp:" + Uri.encode(address, "@/+") + "?join")
            shareIntent.setType("text/plain")
            try {
                context.startActivity(
                    Intent.createChooser(shareIntent, context.getText(R.string.share_uri_with)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT)
                    .show()
            }
        }

        @JvmStatic
        fun addInviteUri(to: Intent, from: Intent?) {
            if (from != null && from.hasExtra(EXTRA_INVITE_URI)) {
                val invite = from.getStringExtra(EXTRA_INVITE_URI)
                to.putExtra(EXTRA_INVITE_URI, invite)
            }
        }
    }
}
