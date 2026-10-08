package uk.xa0.tulkki.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.SoundEffectConstants
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.base.Strings
import java.util.Collections
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.util.ActivityResult
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The picker's choice mode, without the `ListView` that used to carry it.
 *
 * <p>`ListView`'s `CHOICE_MODE_MULTIPLE` and its `MultiChoiceModeListener` are gone with
 * `item_contact.xml`; what they did is [selected] and the base's checked-keys provider. A long press enters the
 * same action mode (`onCreateActionMode`), a tap toggles the row's check
 * (`onItemCheckedStateChanged`), the mode's finish clears the selection (`onDestroyActionMode`), and
 * the checked rows are the ones whose JID is in [selected]. The sound and the FAB's icon are the
 * same. The fast-scroll toggles those two callbacks made have no `LazyColumn` counterpart, which is
 * the one thing the `ListView` took with it.
 */
class ChooseContactActivity : AbstractSearchableListItemActivity() {
    private val mActivatedAccounts = ArrayList<String>()

    // The checked set, and the state the Compose rows read: `onItemCheckedStateChanged` and
    // `onItemClick` both wrote it in the Java, and the list redrew from it on every change.
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private val filterContacts: MutableSet<String> = HashSet()
    private val extraContacts: MutableSet<ListItem> = HashSet()

    private var showEnterJid = false
    private var startSearching = false
    private var multiple = false

    private val postponedActivityResult = PendingItem<ActivityResult>()

    /** The action mode a long press opened, so a second one is not started over it. */
    private var mActionMode: ActionMode? = null

    /** The JID dialog this screen launched, so a backend connect can refresh its known hosts. */
    private var enterJidSession: EnterJidDialog.Session? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val selectedContacts = savedInstanceState.getStringArray("selected_contacts")
            if (selectedContacts != null) {
                selected = selectedContacts.toSet()
            }
        }

        val contacts = intent.getStringArrayExtra(EXTRA_FILTERED_CONTACTS)
        if (contacts != null) {
            Collections.addAll(filterContacts, *contacts)
        }

        multiple = intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)
        if (multiple) {
            // The checked rows are the selection the host holds: the provider is read during
            // composition, so `selected` moving redraws the list the way `setItemChecked` did.
            setCheckedKeysProvider { selected }
            setRowLongClickListener { position -> enterChoiceMode(position) }
        }
        setRowClickListener { position -> onRowClicked(position) }
        this.showEnterJid = intent.getBooleanExtra(EXTRA_SHOW_ENTER_JID, false)
        setFabClickListener { onFabClicked() }
        if (this.showEnterJid) {
            showFab()
        } else {
            applyFabIcon(R.drawable.ic_navigate_next_24dp)
        }

        val preferences: SharedPreferences = getPreferences()
        this.startSearching =
            intent.getBooleanExtra("direct_search", false) &&
            preferences.getBoolean(
                "start_searching",
                resources.getBoolean(R.bool.start_searching),
            )

        refreshSettings()
        setTagClickListener { tag -> showTagInSearch(tag) }

        // The bar's one live item: the QR scan, shown only for the enter-a-JID flow. The chrome's
        // menu is state, so it is set here, where `showEnterJid` is known - the base's first
        // composition runs inside `super.onCreate` and could not see it.
        if (isCameraFeatureAvailable() && showEnterJid) {
            setChromeMenu(
                listOf(
                    ChromeMenuItem(getString(R.string.scan_qr_code)) { ScanActivity.scan(this) }
                )
            )
        }

        if (startSearching) {
            openSearch()
        }
    }

    /** The bar's title, read from the intent exactly as `onStart` read it. */
    override fun titleRes(): Int {
        // An explicit zero resource id is the one value `setTitle` could not draw; the old code
        // caught that and fell back, and `stringResource(0)` would not.
        val res = getTitleFromIntent()
        return if (res == 0) R.string.title_activity_choose_contact else res
    }

    private fun onFabClicked() {
        if (selected.isEmpty()) {
            showEnterJidDialog(null)
        } else {
            submitSelection()
        }
    }

    public override fun colorCodeAccounts(): Boolean = mActivatedAccounts.size > 1

    /**
     * The action mode the long press opens: the deleted `MultiChoiceModeListener`, whose menu was
     * always empty and whose title was this screen's own.
     */
    private val mActionModeCallback =
        object : ActionMode.Callback {
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.setTitle(getTitleFromIntent())
                applyFabIcon(R.drawable.ic_navigate_next_24dp)
                showFab()
                hideKeyboard()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                mActionMode = null
                applyFabIcon(R.drawable.ic_person_add_24dp)
                if (showEnterJid) {
                    showFab()
                } else {
                    hideFab()
                }
                selected = emptySet()
                refreshList()
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        }

    /** The long press that checks the row and opens the mode: the deleted `setItemChecked` path. */
    private fun enterChoiceMode(position: Int) {
        if (mActionMode == null) {
            mActionMode = startActionMode(mActionModeCallback)
        }
        setChecked(position, true)
        applyChoiceFab()
    }

    /** One tap in choice mode: the toggle `onItemCheckedStateChanged` and `onItemClick` both made. */
    private fun toggleChoice(position: Int) {
        setChecked(position, listItems[position].getJid().toString() !in selected)
        applyChoiceFab()
    }

    /** The `isItemChecked` write, with the click sound and the redraw the Java ran around it. */
    private fun setChecked(position: Int, checked: Boolean) {
        if (selected.isNotEmpty()) {
            window.decorView.playSoundEffect(SoundEffectConstants.CLICK)
        }
        val item = listItems[position] as Contact
        val jid = item.getJid().toString()
        selected = if (checked) selected + jid else selected - jid
        refreshList()
    }

    /** The FAB `onItemClick` chose from the selection: next while any row is checked. */
    private fun applyChoiceFab() {
        if (selected.isEmpty()) {
            applyFabIcon(R.drawable.ic_person_add_24dp)
            if (showEnterJid) {
                showFab()
            } else {
                hideFab()
            }
        } else {
            applyFabIcon(R.drawable.ic_navigate_next_24dp)
            showFab()
        }
    }

    /** A row's tap: a toggle in choice mode, the ordinary pick otherwise. */
    private fun onRowClicked(position: Int) {
        if (multiple) {
            toggleChoice(position)
            return
        }
        hideKeyboard()
        onListItemClicked(listItems[position])
    }

    private fun submitSelection() {
        val request = intent
        val data = Intent()
        data.putExtra("contacts", getSelectedContactJids())
        data.putExtra(EXTRA_SELECT_MULTIPLE, true)
        data.putExtra(XmppActivity.EXTRA_ACCOUNT, request.getStringExtra(XmppActivity.EXTRA_ACCOUNT))
        copy(request, data)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    @StringRes
    public fun getTitleFromIntent(): Int {
        val currentIntent = intent
        val multipleFromIntent =
            currentIntent != null && currentIntent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)
        val fallback =
            if (multipleFromIntent) {
                R.string.title_activity_choose_contacts
            } else {
                R.string.title_activity_choose_contact
            }
        return if (currentIntent != null) {
            currentIntent.getIntExtra(EXTRA_TITLE_RES_ID, fallback)
        } else {
            fallback
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putStringArray("selected_contacts", getSelectedContactJids())
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onSearchAction(): Boolean =
        if (multiple) {
            false
        } else {
            val items = listItems
            if (items.size == 1) {
                onListItemClicked(items[0])
                true
            } else {
                false
            }
        }

    protected override fun filterContacts(needle: String?) {
        listItems.clear()
        if (xmppConnectionService == null) {
            refreshList()
            return
        }
        val accounts = ArrayList<Account>()
        for (account in AccountRegistry.get().getAccounts()) {
            if (mActivatedAccounts.contains(account.getJid().asBareJid().toString())) {
                accounts.add(account)
            }
        }
        for (contact in extraContacts) {
            if (!filterContacts.contains(contact.getJid().asBareJid().toString()) &&
                contact.match(this, needle)
            ) {
                listItems.add(contact)
            }
        }
        for (account in accounts) {
            for (contact in account.getRoster().getContacts()) {
                if (contact.showInContactList() &&
                    !filterContacts.contains(contact.getJid().asBareJid().toString()) &&
                    contact.match(this, needle)
                ) {
                    listItems.add(contact)
                }
            }

            val self = Contact(account.getSelfContact())
            self.setSystemName(getString(R.string.note_to_self))
            if (self.match(this, needle)) {
                listItems.add(self)
            }
        }
        Collections.sort(listItems)
        // The deleted `setItemChecked` loop is the checked-keys provider now: a row whose JID is in
        // `selected` comes back checked, whatever the filter left out.
        refreshList()
    }

    private fun getSelectedContactJids(): Array<String> = selected.toTypedArray()

    public override fun refreshUiReal() {
        // nothing to do. This Activity doesn't implement any listeners
    }

    protected fun showEnterJidDialog(uri: XmppUri?) {
        val jid = uri?.getJid()
        enterJidSession =
            EnterJidDialog.show(
                activity = this,
                activatedAccounts = mActivatedAccounts,
                title = getString(R.string.enter_contact),
                positiveButton = getString(R.string.select),
                secondaryButton = null,
                prefilledJid = jid?.asBareJid()?.toString(),
                account = intent.getStringExtra(XmppActivity.EXTRA_ACCOUNT),
                allowEditJid = true,
                showBookmarkCheckbox = false,
                sanityCheck = EnterJidDialog.SanityCheck.NO,
            ) { accountJid, contactJid, x, y ->
                for (account in AccountRegistry.get().getAccounts()) {
                    if (account.getJid().asBareJid() == accountJid) {
                        val contact = account.getRoster().getContact(contactJid)
                        if (multiple) {
                            extraContacts.add(contact)
                            selected = selected + contactJid.toString()
                            postDelayed(200L) { showTagInSearch(contactJid.toString()) }
                            filterContacts(contactJid.toString())
                            applyFabIcon(R.drawable.ic_navigate_next_24dp)
                        } else {
                            onListItemClicked(contact)
                        }
                    }
                }

                true
            }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        val activityResult = ActivityResult.of(requestCode, resultCode, intent)
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult)
        } else {
            this.postponedActivityResult.push(activityResult)
        }
    }

    private fun handleActivityResult(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK &&
            activityResult.requestCode == ScanActivity.REQUEST_SCAN_QR_CODE
        ) {
            val result =
                (activityResult.data ?: throw NullPointerException())
                    .getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            val uri = XmppUri(Strings.nullToEmpty(result))
            if (uri.isValidJid()) {
                showEnterJidDialog(uri)
            }
        }
    }

    protected override fun onBackendConnected() {
        this.mActivatedAccounts.clear()
        val selectedAccount = intent.getStringExtra(XmppActivity.EXTRA_ACCOUNT)
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled() &&
                (selectedAccount == null ||
                    selectedAccount == account.getJid().asBareJid().toString())
            ) {
                this.mActivatedAccounts.add(account.getJid().asBareJid().toString())
            }
        }
        filterContacts()
        val activityResult = this.postponedActivityResult.pop()
        if (activityResult != null) {
            handleActivityResult(activityResult)
        }
        enterJidSession?.onBackendConnected()
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults)
    }

    private fun onListItemClicked(item: ListItem) {
        val request = intent
        val data = Intent()
        data.putExtra("contact", item.getJid().toString())
        var account = request.getStringExtra(XmppActivity.EXTRA_ACCOUNT)
        if (account == null && item is Contact) {
            account = item.getAccount().getJid().asBareJid().toString()
        }
        data.putExtra(XmppActivity.EXTRA_ACCOUNT, account)
        data.putExtra(EXTRA_SELECT_MULTIPLE, false)
        copy(request, data)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    companion object {
        public const val EXTRA_TITLE_RES_ID = "extra_title_res_id"
        public const val EXTRA_GROUP_CHAT_NAME = "extra_group_chat_name"
        public const val EXTRA_SELECT_MULTIPLE = "extra_select_multiple"
        public const val EXTRA_SHOW_ENTER_JID = "extra_show_enter_jid"
        public const val EXTRA_CONVERSATION = "extra_conversation"
        private const val EXTRA_FILTERED_CONTACTS = "extra_filtered_contacts"

        @JvmStatic
        public fun create(activity: Activity, conversation: Conversation): Intent {
            val intent = Intent(activity, ChooseContactActivity::class.java)
            val contacts: MutableList<String> = ArrayList()
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (user in conversation.getMucOptions().getUsers(false)) {
                    val jid = user.getRealJid()
                    if (jid != null) {
                        contacts.add(jid.asBareJid().toString())
                    }
                }
            } else {
                contacts.add(
                    (conversation.getJid() ?: throw NullPointerException())
                        .asBareJid()
                        .toString(),
                )
            }
            intent.putExtra(EXTRA_FILTERED_CONTACTS, contacts.toTypedArray())
            intent.putExtra(EXTRA_CONVERSATION, conversation.getUuid())
            intent.putExtra(EXTRA_SELECT_MULTIPLE, true)
            intent.putExtra(EXTRA_SHOW_ENTER_JID, true)
            intent.putExtra(
                XmppActivity.EXTRA_ACCOUNT,
                (conversation.getAccount() ?: throw NullPointerException())
                    .getJid()
                    .asBareJid()
                    .toString(),
            )
            return intent
        }

        @JvmStatic
        public fun extractJabberIds(result: Intent): List<Jid> {
            val jabberIds: MutableList<Jid> = ArrayList()
            try {
                if (result.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)) {
                    val toAdd = result.getStringArrayExtra("contacts")
                    for (item in toAdd ?: throw NullPointerException()) {
                        jabberIds.add(Jid.of(item))
                    }
                } else {
                    jabberIds.add(
                        Jid.of(result.getStringExtra("contact") ?: throw NullPointerException()))
                }
                return jabberIds
            } catch (e: IllegalArgumentException) {
                return jabberIds
            }
        }

        private fun copy(from: Intent, to: Intent) {
            to.putExtra(EXTRA_CONVERSATION, from.getStringExtra(EXTRA_CONVERSATION))
            to.putExtra(EXTRA_GROUP_CHAT_NAME, from.getStringExtra(EXTRA_GROUP_CHAT_NAME))
        }
    }
}
