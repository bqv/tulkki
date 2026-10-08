package uk.xa0.tulkki.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.util.Pair
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.openintents.openpgp.util.OpenPgpApi
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.accounts.AccountContextMenu
import uk.xa0.tulkki.ui.accounts.AccountRow
import uk.xa0.tulkki.ui.accounts.ManageAccountsScreen
import uk.xa0.tulkki.ui.accounts.ManageAccountsState
import uk.xa0.tulkki.ui.accounts.accountAvatar
import uk.xa0.tulkki.ui.accounts.accountFrameColor
import uk.xa0.tulkki.ui.accounts.accountRow
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.util.MenuDoubleTabUtil
import uk.xa0.tulkki.ui.utils.PermissionUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.Resolver

/**
 * The account list: every account with its status, enable switch and drag handle, plus the account
 * overflow that adds, imports, enables, disables and restarts onboarding.
 *
 * <p>**The layout is gone.** `activity_manage_accounts.xml`, `item_account.xml`, the
 * `AccountAdapter` that inflated the row and the two menus `manageaccounts.xml` /
 * `manageaccounts_context.xml` are deleted. The bar is the shared chrome ([TulkkiChrome]), the body
 * is [ManageAccountsScreen], the overflow is the chrome's `menu` list, and the row's long-press menu
 * is the screen's own `DropdownMenu`. `setSupportActionBar`, `configureActionBar`,
 * `Activities.setStatusAndNavigationBarColors`, the `RecyclerView` and the `ItemTouchHelper` went
 * with the views they belonged to.
 *
 * <p>**What the swap left unchanged.** The drag reorder still swaps the rows in [accountList] and
 * commits the order to the registry on drag end, exactly as the deleted `ItemTouchHelper.Callback`
 * did in `onMove`/`clearView`. The overflow's visibility rules (X509 hides *add account*, and the two
 * bulk items hide themselves when there is nothing to enable or disable) and the context menu's
 * rules (an enabled account offers disable, avatar and PGP; a disabled one offers enable; delete is
 * always there) are the deleted `onCreateOptionsMenu`/`onCreateContextMenu`, read at composition
 * time instead of menu-inflation time.
 */
class ManageAccountActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate,
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    KeyChainAliasCallback,
    uk.xa0.tulkki.xmpp.services.OnAccountCreated {
    private val STATE_SELECTED_ACCOUNT = "selected_account"

    protected var selectedAccount: Account? = null
    protected var selectedAccountJid: Jid? = null

    protected val accountList: MutableList<Account> = ArrayList()
    protected val mInvokedAddAccount = AtomicBoolean(false)
    protected var mMicIntent: Intent? = null

    protected var mPostponedActivityResult: Pair<Int, Intent?>? = null

    private var screen by mutableStateOf(ManageAccountsState())

    public override fun onAccountUpdate() {
        refreshUi()
    }

    public override fun onConversationUpdate() {
        refreshUi()
    }

    protected override fun refreshUiReal() {
        synchronized(accountList) {
            accountList.clear()
            accountList.addAll(AccountRegistry.get().getAccounts())
        }
        screen = screen.copy(showPhoneAccounts = hasPhoneAccounts())
        rebuildRows()
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            val jid = savedInstanceState.getString(STATE_SELECTED_ACCOUNT)
            if (jid != null) {
                try {
                    this.selectedAccountJid = Jid.of(jid)
                } catch (e: IllegalArgumentException) {
                    this.selectedAccountJid = null
                }
            }
        }

        // The chrome draws its own bar behind the system bars; the window must not inset for them.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.title_activity_manage_accounts),
                // The deleted `refreshUiReal` switched the arrow off while the list was empty.
                onUp = if (screen.rows.isEmpty()) null else (::navigateUp),
                menu = manageAccountsMenu(),
            ) {
                ManageAccountsScreen(
                    state = screen,
                    avatarShape = conversationAvatars().shape(),
                    onRow = { row -> accountFor(row)?.let { switchToAccount(it) } },
                    onToggle = { row, checked ->
                        accountFor(row)?.let { toggleAccount(it, checked) }
                    },
                    onMove = { from, to -> moveAccount(from, to) },
                    onMoveFinished = { commitAccountOrder() },
                    contextMenu = { row -> accountContextMenu(row) },
                    onPhoneAccounts = { openPhoneAccounts() },
                    onPhoneAccountsSettings = { openPhoneAccountsSettings() },
                )
            }
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        val account = selectedAccount
        if (account != null) {
            savedInstanceState.putString(
                STATE_SELECTED_ACCOUNT,
                account.getJid().asBareJid().toString(),
            )
        }
        super.onSaveInstanceState(savedInstanceState)
    }

    protected override fun onBackendConnected() {
        val jid = selectedAccountJid
        if (jid != null) {
            this.selectedAccount = AccountRegistry.get().findAccountByJid(jid)
        }
        refreshUiReal()
        val postponed = this.mPostponedActivityResult
        if (postponed != null) {
            this.onActivityResult(postponed.first, Activity.RESULT_OK, postponed.second)
        }
        if (Config.X509_VERIFICATION && this.accountList.isEmpty()) {
            if (mInvokedAddAccount.compareAndSet(false, true)) {
                addAccountFromKey()
            }
        }
    }

    /**
     * The overflow `manageaccounts.xml` carried. The dead `action_settings` item is not dead here:
     * the base activity opens the settings screen for it, so it is carried over resolved.
     */
    @Composable
    private fun manageAccountsMenu(): List<ChromeMenuItem> {
        val items = ArrayList<ChromeMenuItem>()
        if (!Config.X509_VERIFICATION) {
            items.add(
                ChromeMenuItem(stringResource(R.string.action_add_account)) {
                    guarded { startActivity(Intent(this, EditAccountActivity::class.java)) }
                }
            )
        }
        items.add(
            ChromeMenuItem(stringResource(R.string.restore_backup)) {
                guarded {
                    if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
                        startActivity(Intent(this, ImportBackupActivity::class.java))
                    }
                }
            }
        )
        items.add(
            ChromeMenuItem(stringResource(R.string.action_add_account_with_certificate)) {
                guarded { addAccountFromKey() }
            }
        )
        if (accountsLeftToEnable()) {
            items.add(
                ChromeMenuItem(stringResource(R.string.enable_all_accounts)) {
                    guarded { enableAllAccounts() }
                }
            )
        }
        if (accountsLeftToDisable()) {
            items.add(
                ChromeMenuItem(stringResource(R.string.disable_all_accounts)) {
                    guarded { disableAllAccounts() }
                }
            )
        }
        items.add(
            ChromeMenuItem(stringResource(R.string.restart_onboarding)) {
                guarded { startActivity(Intent(this, WelcomeActivity::class.java)) }
            }
        )
        items.add(
            ChromeMenuItem(stringResource(R.string.action_settings)) {
                guarded {
                    startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
                }
            }
        )
        return items
    }

    /**
     * The long-press menu `manageaccounts_context.xml` carried, with the deleted
     * `onCreateContextMenu`'s visibility rules. It also remembers the account as [selectedAccount],
     * which is what the old adapter's context listener did before inflation.
     */
    private fun accountContextMenu(row: AccountRow): AccountContextMenu? {
        val account = accountFor(row) ?: return null
        this.selectedAccount = account
        val items = ArrayList<ChromeMenuItem>()
        if (account.isEnabled()) {
            items.add(
                ChromeMenuItem(getString(R.string.mgmt_account_publish_avatar)) {
                    publishAvatar(account)
                }
            )
            if (Config.supportOpenPgp()) {
                items.add(
                    ChromeMenuItem(getString(R.string.mgmt_account_publish_pgp)) {
                        publishOpenPGPPublicKey(account)
                    }
                )
            }
            items.add(
                ChromeMenuItem(getString(R.string.mgmt_account_disable)) { disableAccount(account) }
            )
        } else {
            items.add(
                ChromeMenuItem(getString(R.string.mgmt_account_enable)) { enableAccount(account) }
            )
        }
        items.add(
            ChromeMenuItem(getString(R.string.mgmt_account_delete)) { deleteAccount(account) }
        )
        return AccountContextMenu(row.jid, items)
    }

    public override fun onNavigateUp(): Boolean {
        if (xmppConnectionService.getConversationList().isEmpty()) {
            val contactsIntent = Intent(this, StartConversationActivity::class.java)
            contactsIntent.flags =
                (
                    // if activity exists in stack, pop the stack and go back to it
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        // otherwise, make a new task for it
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        // don't use the new activity animation; finish
                        // animation runs instead
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            startActivity(contactsIntent)
            finish()
            return true
        } else {
            return super.onNavigateUp()
        }
    }

    /** The arrow: the framework's own up, and a finish when nothing above handled it. */
    private fun navigateUp() {
        if (!onNavigateUp()) {
            finish()
        }
    }

    /** The deleted adapter's row facts, rebuilt whenever the registry changes. */
    private fun rebuildRows() {
        val rows =
            accountList.map { account ->
                accountRow(
                    this,
                    account,
                    accountAvatar(account),
                    accountFrameColor(account),
                )
            }
        screen = screen.copy(rows = rows)
    }

    private fun accountFor(row: AccountRow): Account? =
        accountList.firstOrNull { it.getJid().asBareJid().toString() == row.jid }

    /** The deleted `AccountAdapter.onItemMove`, unchanged. */
    private fun moveAccount(from: Int, to: Int) {
        synchronized(accountList) {
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(accountList, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(accountList, i, i - 1)
                }
            }
        }
        rebuildRows()
    }

    /** The deleted `ItemTouchHelper.Callback.clearView`, unchanged. */
    private fun commitAccountOrder() {
        if (xmppConnectionService != null) {
            // The Java mutated the registry's own list in place; that list is only read-only
            // through the interface, so the cast names the Java type.
            val serviceAccounts =
                AccountRegistry.get().getAccounts() as MutableList<Account>
            synchronized(serviceAccounts) {
                serviceAccounts.clear()
                serviceAccounts.addAll(accountList)
            }
            xmppConnectionService.updateAccountOrder()
        }
    }

    /** The switch's effect: the deleted `onClickTglAccountState`, without the interface. */
    private fun toggleAccount(account: Account, checked: Boolean) {
        if (checked) {
            enableAccount(account)
        } else {
            disableAccount(account)
        }
    }

    /** `onOptionsItemSelected`'s first line, kept for every overflow item. */
    private fun guarded(action: () -> Unit) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return
        }
        action()
    }

    private fun hasPhoneAccounts(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        if (Build.VERSION.SDK_INT >= 33) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM) &&
                !packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
            ) {
                return false
            }
        } else {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) {
                return false
            }
        }
        return AccountRegistry.get().getAccounts().any { a -> a.getGateways("pstn").size > 0 }
    }

    private fun openPhoneAccounts() {
        val intent = Intent()
        intent.component =
            ComponentName(
                "com.android.server.telecom",
                "com.android.server.telecom.settings.EnableAccountPreferenceActivity",
            )
        mMicIntent = intent
        requestMicPermission()
    }

    private fun openPhoneAccountsSettings() {
        mMicIntent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        requestMicPermission()
    }

    private fun requestMicPermission() {
        val permissions: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions =
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
        } else {
            permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED &&
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Dialler Integration")
                builder.setMessage(
                    "You will be asked to grant microphone permission, which is needed for the " +
                        "dialler integration to function.",
                )
                builder.setPositiveButton("I Understand") { dialog, which ->
                    requestPermissions(permissions, REQUEST_MICROPHONE)
                }
                builder.setCancelable(true)
                val dialog = builder.create()
                dialog.setCanceledOnTouchOutside(true)
                dialog.show()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_MICROPHONE,
                )
            }
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (PermissionUtils.allGranted(grantResults)) {
                when (requestCode) {
                    REQUEST_MICROPHONE -> {
                        try {
                            startActivity(mMicIntent ?: throw NullPointerException())
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this,
                                "Your OS has blocked dialler integration",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        mMicIntent = null
                        return
                    }
                    REQUEST_IMPORT_BACKUP -> {
                        startActivity(Intent(this, ImportBackupActivity::class.java))
                    }
                }
            } else {
                if (requestCode == REQUEST_MICROPHONE) {
                    Toast.makeText(this, "Microphone access was denied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (PermissionUtils.writeGranted(grantResults, permissions)) {
            val service = xmppConnectionService
            if (service != null) {
                service.restartFileObserver()
            }
        }
    }

    private fun addAccountFromKey() {
        try {
            KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                R.string.device_does_not_support_certificates,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun publishAvatar(account: Account) {
        val intent =
            Intent(
                applicationContext,
                PublishProfilePictureActivity::class.java,
            )
        intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
        startActivity(intent)
    }

    private fun disableAllAccounts() {
        val list = ArrayList<Account>()
        synchronized(this.accountList) {
            for (account in this.accountList) {
                if (account.isEnabled()) {
                    list.add(account)
                }
            }
        }
        for (account in list) {
            disableAccount(account)
        }
    }

    private fun accountsLeftToDisable(): Boolean {
        synchronized(this.accountList) {
            for (account in this.accountList) {
                if (account.isEnabled()) {
                    return true
                }
            }
            return false
        }
    }

    private fun accountsLeftToEnable(): Boolean {
        synchronized(this.accountList) {
            for (account in this.accountList) {
                if (!account.isEnabled()) {
                    return true
                }
            }
            return false
        }
    }

    private fun enableAllAccounts() {
        val list = ArrayList<Account>()
        synchronized(this.accountList) {
            for (account in this.accountList) {
                if (!account.isEnabled()) {
                    list.add(account)
                }
            }
        }
        for (account in list) {
            enableAccount(account)
        }
    }

    private fun disableAccount(account: Account) {
        Resolver.clearCache()
        account.setOption(Account.OPTION_DISABLED, true)
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableAccount(account: Account) {
        account.setOption(Account.OPTION_DISABLED, false)
        val connection = account.getXmppConnection()
        if (connection != null) {
            connection.resetEverything()
        }
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show()
        }
    }

    private fun publishOpenPGPPublicKey(account: Account) {
        if (this.hasPgp()) {
            announcePgp(account, null, null, onOpenPGPKeyPublished)
        } else {
            this.showInstallPgpDialog()
        }
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (xmppConnectionServiceBound) {
                if (requestCode == XmppActivity.REQUEST_CHOOSE_PGP_ID) {
                    val extras = data?.extras ?: throw NullPointerException()
                    if (extras.containsKey(OpenPgpApi.EXTRA_SIGN_KEY_ID)) {
                        (selectedAccount ?: throw NullPointerException())
                            .setPgpSignId(extras.getLong(OpenPgpApi.EXTRA_SIGN_KEY_ID))
                        announcePgp(
                            selectedAccount ?: throw NullPointerException(),
                            null,
                            null,
                            onOpenPGPKeyPublished,
                        )
                    } else {
                        choosePgpSignId(selectedAccount ?: throw NullPointerException())
                    }
                } else if (requestCode == XmppActivity.REQUEST_ANNOUNCE_PGP) {
                    announcePgp(
                        selectedAccount ?: throw NullPointerException(),
                        null,
                        data,
                        onOpenPGPKeyPublished,
                    )
                }
                this.mPostponedActivityResult = null
            } else {
                this.mPostponedActivityResult = Pair(requestCode, data)
            }
        }
    }

    public override fun alias(alias: String?) {
        if (alias == null) {
            runOnUiThread {
                Toast.makeText(this, R.string.no_certificate_selected, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val service = xmppConnectionService
        if (service != null) {
            service.createAccountFromKey(alias, this)
        }
    }

    // Tulkki: C5-E1 - the port's parameter is the island's ref now. It is named fully
    // qualified and imported nowhere, which is what keeps `ui-reaches-island` at its count; the
    // body reads only `getJid()`, the ref's own member.
    public override fun onAccountCreated(account: uk.xa0.tulkki.xmpp.refs.AccountRef) {
        val intent = Intent(this, EditAccountActivity::class.java)
        intent.putExtra("jid", account.getJid().asBareJid().toString())
        intent.putExtra("init", true)
        startActivity(intent)
    }

    public override fun informUser(r: Int) {
        runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val REQUEST_IMPORT_BACKUP = 0x63fb
        private const val REQUEST_MICROPHONE = 0x63fb1
    }
}
