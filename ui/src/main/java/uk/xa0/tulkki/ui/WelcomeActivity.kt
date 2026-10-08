package uk.xa0.tulkki.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.text.InputType
import android.util.Log
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.HashSet
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.updb.UnifiedPushDatabase
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.utils.InstallReferrerUtils
import uk.xa0.tulkki.ui.utils.PermissionUtils
import uk.xa0.tulkki.ui.welcome.WelcomeInfo
import uk.xa0.tulkki.ui.welcome.WelcomeScreen
import uk.xa0.tulkki.ui.welcome.WelcomeSettings
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki's front door: the four intro/preferences/sign-in slides, and the account-creation and
 * certificate callbacks they lead to.
 *
 * <p>The screen is [WelcomeScreen], composed straight into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome]. `activity_welcome.xml` (715 lines, 35 ids) was deleted with
 * the swap, and so was `welcome_menu.xml`: the three items it carried are the chrome's overflow
 * items below. The `ViewPager`, its `PagerAdapter` and the `DotsIndicator` went with the layout.
 *
 * <p>What this class kept is everything that was never a view: the invite/install-referrer handling,
 * the onboarding reconnect, the preference reads and writes, the info dialogs and the
 * database-encryption flow. Its only Compose state is what the screen draws.
 */
class WelcomeActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnAccountCreated,
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    KeyChainAliasCallback {
    private var inviteUri: XmppUri? = null
    private var onboardingAccount: Account? = null

    /** The settings slide's values; the pager's own settle writes them back. */
    private var settings: WelcomeSettings by mutableStateOf(WelcomeSettings())

    /** The onboarding account is connecting: the sign-in button says so and is disabled. */
    private var working: Boolean by mutableStateOf(false)

    /** The database already has a password, so its row shows the enabled summary and cannot be pressed. */
    private var databaseEncryptionConfigured: Boolean by mutableStateOf(false)

    public fun onInstallReferrerDiscovered(referrer: Uri) {
        Log.d(Config.LOGTAG, "welcome activity: on install referrer discovered " + referrer)
        if ("xmpp".equals(referrer.scheme, ignoreCase = true)) {
            val xmppUri = XmppUri(referrer)
            runOnUiThread { processXmppUri(xmppUri) }
        } else {
            Log.i(Config.LOGTAG, "install referrer was not an XMPP uri")
        }
    }

    private fun processXmppUri(xmppUri: XmppUri) {
        if (!xmppUri.isValidJid()) {
            return
        }
        val preAuth = xmppUri.getParameter(XmppUri.PARAMETER_PRE_AUTH)
        val jid = xmppUri.getJid() ?: throw NullPointerException()
        var intent: Intent? = null
        if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
            intent = UiHost.installed().tokenRegistrationIntent(this, jid.toString(), preAuth)
        } else if (xmppUri.isAction(XmppUri.ACTION_ROSTER) &&
            "y" == xmppUri.getParameter(XmppUri.PARAMETER_IBR)
        ) {
            val registration =
                UiHost.installed().tokenRegistrationIntent(
                    this,
                    jid.getDomain().toString(),
                    preAuth,
                )
            registration.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString())
            intent = registration
        }
        if (intent != null) {
            startActivity(intent)
            finish()
            return
        }
        this.inviteUri = xmppUri
    }

    @Synchronized
    protected override fun refreshUiReal() {
        val account = onboardingAccount ?: return
        if (account.getStatus() != Account.State.ONLINE) return

        val intent = Intent(this, StartConversationActivity::class.java)
        intent.putExtra("init", true)
        intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
        onboardingAccount = null
        startActivity(intent)
        finish()
    }

    public override fun onAccountUpdate() {
        refreshUi()
    }

    protected override fun onBackendConnected() {
        if (xmppConnectionService.isOnboarding()) {
            // The sign-in slide and its disabled "Working..." button, which the screen derives from
            // the one flag; the old setCurrentItem(4) was clamped by the ViewPager to the last slide.
            working = true
            val account = AccountRegistry.get().getAccounts()[0]
            onboardingAccount = account
            xmppConnectionService.reconnectAccountInBackground(account)
        }
    }

    public override fun onResume() {
        super.onResume()
        updateDatabaseEncryptionButton()
    }

    public override fun onStart() {
        super.onStart()
        InstallReferrerUtils(this)
    }

    public override fun onStop() {
        super.onStop()
    }

    public override fun onNewIntent(intent: Intent) {
        if (intent != null) {
            setIntent(intent)
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        if (resources.getBoolean(R.bool.portrait_only)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        getPreferences().edit().putStringSet("pstn_gateways", HashSet<String>()).apply()
        // The chrome owns the system bars and the window insets now.
        enableEdgeToEdge()
        settings = readSettings()
        val hasCamera = UiHost.installed().hasFeatureCamera(this)
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // The label the XML action bar showed: `@string/app_name`, which lives in :app and
                // is therefore reached through the activity's own title rather than a ui string.
                title = getTitle().toString(),
                onUp = null,
                menu = onboardingMenu(hasCamera),
            ) {
                WelcomeScreen(
                    settings = settings,
                    working = working,
                    databaseEncryptionConfigured = databaseEncryptionConfigured,
                    onSettingsChange = { settings = it },
                    onInfo = { showInfo(it) },
                    onSetupDatabaseEncryption = { showDatabaseEncryptionDialog() },
                    onRegister = { startRegister() },
                    onLogIn = { startLogIn() },
                    onBackup = { importBackup() },
                    onSnikket = { startSnikket() },
                    onCertificate = { addAccountFromKey() },
                    onSettingsSettled = { persistSettings() },
                )
            }
        }
    }

    /**
     * `welcome_menu.xml`, in the chrome's overflow: the QR scanner when the device has a camera (the
     * layout's `showAsAction="ifRoom"` icon is a text item here, which is the chrome's one shape),
     * the certificate login and Restore backup, which were both `never`. No menu file survives.
     */
    private fun onboardingMenu(hasCamera: Boolean): List<ChromeMenuItem> =
        buildList {
            if (hasCamera) {
                add(
                    ChromeMenuItem(getString(R.string.scan_qr_code)) {
                        UriHandlerActivity.scan(this@WelcomeActivity, true)
                    },
                )
            }
            add(
                ChromeMenuItem(getString(R.string.action_add_account_with_certificate)) {
                    addAccountFromKey()
                },
            )
            add(ChromeMenuItem(getString(R.string.restore_backup)) { importBackup() })
        }

    /** The honest onboarding: no sign-up page of our own, straight to in-band registration. */
    private fun startRegister() {
        val intent = Intent(this, MagicCreateActivity::class.java)
        addInviteUri(intent)
        startActivity(intent)
    }

    private fun startLogIn() {
        val accounts = AccountRegistry.get().getAccounts()
        var intent = Intent(this, EditAccountActivity::class.java)
        intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false)
        if (accounts.size == 1) {
            intent.putExtra("jid", accounts[0].getJid().asBareJid().toString())
            intent.putExtra("init", true)
        } else if (accounts.size >= 1) {
            intent = Intent(this, ManageAccountActivity::class.java)
        }
        addInviteUri(intent)
        startActivity(intent)
    }

    private fun startSnikket() {
        val accounts = AccountRegistry.get().getAccounts()
        var intent = Intent(this, EditAccountActivity::class.java)
        intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false)
        intent.putExtra("snikket", true)
        if (accounts.size == 1) {
            intent.putExtra("jid", accounts[0].getJid().asBareJid().toString())
            intent.putExtra("init", true)
        } else if (accounts.size >= 1) {
            intent = Intent(this, ManageAccountActivity::class.java)
        }
        addInviteUri(intent)
        startActivity(intent)
    }

    private fun importBackup() {
        if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
            startActivity(Intent(this, ImportBackupActivity::class.java))
        }
    }

    /** `getDefaults()`: the same keys, the same resource defaults. */
    private fun readSettings(): WelcomeSettings {
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        return WelcomeSettings(
            loadProvidersExternal =
                preferences.getBoolean(
                    AppSettings.LOAD_PROVIDERS_EXTERNAL,
                    resources.getBoolean(R.bool.load_providers_list_external),
                ),
            allowScreenshots =
                preferences.getBoolean(
                    AppSettings.ALLOW_SCREENSHOTS,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.allow_screenshots),
                ),
            showLinks =
                preferences.getBoolean(
                    AppSettings.SHOW_LINK_PREVIEWS,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.show_link_previews),
                ),
            chatStates =
                preferences.getBoolean(
                    Namespace.CHAT_STATES,
                    resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.chat_states),
                ),
            confirmMessages =
                preferences.getBoolean(
                    AppSettings.CONFIRM_MESSAGES,
                    resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.confirm_messages),
                ),
            lastSeen =
                preferences.getBoolean(
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.last_activity),
                ),
            blindTrust =
                preferences.getBoolean(
                    AppSettings.BLIND_TRUST_BEFORE_VERIFICATION,
                    resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.btbv),
                ),
            dane =
                preferences.getBoolean(
                    AppSettings.DANE_ENFORCED,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.enforce_dane),
                ),
            useSecureTls =
                preferences.getBoolean(
                    AppSettings.REQUIRE_TLS_V1_3,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.require_tls_v1_3),
                ),
            storeSecurely =
                preferences.getBoolean(
                    AppSettings.USE_INTERNAL_SECURE_STORAGE,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.default_store_media_securely),
                ),
            sendCrashReports =
                preferences.getBoolean(
                    AppSettings.SEND_CRASH_REPORTS,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.send_crash_reports),
                ),
        )
    }

    /** `setSettings()`: one editor rather than eleven, with exactly the layout's keys. */
    private fun persistSettings() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(AppSettings.LOAD_PROVIDERS_EXTERNAL, settings.loadProvidersExternal)
            .putBoolean(AppSettings.ALLOW_SCREENSHOTS, settings.allowScreenshots)
            .putBoolean(AppSettings.SHOW_LINK_PREVIEWS, settings.showLinks)
            .putBoolean(Namespace.CHAT_STATES, settings.chatStates)
            .putBoolean(AppSettings.CONFIRM_MESSAGES, settings.confirmMessages)
            .putBoolean(AppSettings.BROADCAST_LAST_ACTIVITY, settings.lastSeen)
            .putBoolean(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, settings.blindTrust)
            .putBoolean(AppSettings.DANE_ENFORCED, settings.dane)
            .putBoolean(AppSettings.REQUIRE_TLS_V1_3, settings.useSecureTls)
            .putBoolean(AppSettings.USE_INTERNAL_SECURE_STORAGE, settings.storeSecurely)
            .putBoolean(AppSettings.SEND_CRASH_REPORTS, settings.sendCrashReports)
            .apply()
    }

    private fun showInfo(setting: Int) {
        val title: String
        val message: String
        when (setting) {
            WelcomeInfo.LOAD_PROVIDERS_EXTERNAL -> {
                title = getString(R.string.pref_load_providers_list_external)
                message = getString(R.string.pref_load_providers_list_external_summary)
            }
            WelcomeInfo.ALLOW_SCREENSHOTS -> {
                title = getString(R.string.pref_allow_screenshots)
                message = getString(R.string.pref_allow_screenshots_summary)
            }
            WelcomeInfo.SHOW_WEBLINKS -> {
                title = getString(R.string.show_link_previews)
                message = getString(R.string.show_link_previews_summary)
            }
            WelcomeInfo.CHAT_STATES -> {
                title = getString(R.string.pref_chat_states)
                message = getString(R.string.pref_chat_states_summary)
            }
            WelcomeInfo.CONFIRM_MESSAGES -> {
                title = getString(R.string.pref_confirm_messages)
                message = getString(R.string.pref_confirm_messages_summary)
            }
            WelcomeInfo.LAST_SEEN -> {
                title = getString(R.string.pref_broadcast_last_activity)
                message = getString(R.string.pref_broadcast_last_activity_summary)
            }
            WelcomeInfo.BLIND_TRUST -> {
                title = getString(R.string.pref_blind_trust_before_verification)
                message = getString(R.string.blindly_trusted_omemo_keys)
            }
            WelcomeInfo.ENFORCE_DANE -> {
                title = getString(R.string.pref_enforce_dane)
                message = getString(R.string.pref_enforce_dane_summary)
            }
            WelcomeInfo.USE_SECURE_TLS_CIPHERS -> {
                title = getString(R.string.strong_transport_security)
                message = getString(R.string.require_tls_v1_3)
            }
            WelcomeInfo.USE_SECURE_STORAGE -> {
                title = getString(R.string.store_media_only_in_cache)
                message = getString(R.string.pref_store_media_in_cache)
            }
            WelcomeInfo.SEND_CRASH_REPORTS -> {
                title = getString(R.string.pref_send_crash_reports)
                message = getString(R.string.pref_never_send_crash_summary)
            }
            WelcomeInfo.DATABASE_ENCRYPTION -> {
                title = getString(R.string.pref_database_encryption)
                message = getString(R.string.pref_database_encryption_summary)
            }
            else -> {
                title = getString(R.string.error)
                message = getString(R.string.error)
            }
        }
        Log.d(Config.LOGTAG, "STRING value " + title)
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setNeutralButton(getString(R.string.ok), null)
        builder.create().show()
    }

    private fun updateDatabaseEncryptionButton() {
        databaseEncryptionConfigured =
            try {
                AppSettings(this).getDatabasePasswordChars() != null
            } catch (e: Exception) {
                false
            }
    }

    private fun showDatabaseEncryptionDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.dialog_set_db_password_title)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val warningText = TextView(this)
        warningText.setText(R.string.dialog_db_password_policy_warning)
        warningText.setTextColor(getColor(android.R.color.holo_red_dark))
        warningText.setPadding(0, 0, 0, padding)
        layout.addView(warningText)

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        if (keyguardManager != null && !keyguardManager.isDeviceSecure) {
            val lockWarningText = TextView(this)
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning)
            lockWarningText.setTextColor(getColor(android.R.color.holo_orange_dark))
            lockWarningText.setPadding(0, 0, 0, padding)
            layout.addView(lockWarningText)
        }

        val passwordInput = TextInputEditText(this)
        passwordInput.setHint(R.string.dialog_db_password_hint)
        passwordInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passwordInput)

        val confirmInput = TextInputEditText(this)
        confirmInput.setHint(R.string.dialog_db_password_confirm_hint)
        confirmInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(confirmInput)

        val noRecoveryCheckbox = CheckBox(this)
        noRecoveryCheckbox.setText(R.string.dialog_db_password_no_recovery_checkbox)
        noRecoveryCheckbox.setPadding(0, padding / 2, 0, 0)
        layout.addView(noRecoveryCheckbox)

        builder.setView(layout)
        builder.setPositiveButton(android.R.string.ok, null)
        builder.setNegativeButton(android.R.string.cancel, null)
        val d = builder.show()
        val okButton = d.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.setEnabled(false)
        noRecoveryCheckbox.setOnCheckedChangeListener { buttonView, isChecked ->
            okButton.setEnabled(isChecked)
        }
        okButton.setOnClickListener { v ->
            val password = CharArray(passwordInput.length())
            (passwordInput.text ?: throw NullPointerException())
                .getChars(0, passwordInput.length(), password, 0)
            val confirm = CharArray(confirmInput.length())
            (confirmInput.text ?: throw NullPointerException())
                .getChars(0, confirmInput.length(), confirm, 0)

            if (password.isEmpty()) {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show()
            } else if (password.size < 8) {
                Toast.makeText(
                    this,
                    R.string.toast_db_password_error_too_short,
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (CryptoHelper.isEqual(password, confirm)) {
                (passwordInput.text ?: throw NullPointerException()).clear()
                (confirmInput.text ?: throw NullPointerException()).clear()
                d.dismiss()
                performDatabaseMigration(password)
                FileHelper.zero(confirm)
                return@setOnClickListener
            } else {
                Toast.makeText(
                    this,
                    R.string.toast_db_password_error_mismatch,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            FileHelper.zero(password)
            FileHelper.zero(confirm)
        }
    }

    private fun performDatabaseMigration(newPassword: CharArray) {
        val progressBuilder = MaterialAlertDialogBuilder(this)
        progressBuilder.setTitle("Database Migration")
        progressBuilder.setMessage("Please wait...")
        progressBuilder.setCancelable(false)
        val progressBar = ProgressBar(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        progressBar.setPadding(padding, padding, padding, padding)
        progressBuilder.setView(progressBar)
        val progressDialog = progressBuilder.show()

        Thread {
            try {
                DatabaseBackend.migrate(this, null, newPassword)
                UnifiedPushDatabase.migrate(this, null, newPassword)
                val service = xmppConnectionService
                if (service != null) {
                    service.swapDatabaseBackend(DatabaseBackend.getInstance(this))
                }
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this,
                        R.string.toast_db_password_success_set,
                        Toast.LENGTH_SHORT,
                    ).show()
                    databaseEncryptionConfigured = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Error")
                        .setMessage(e.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                FileHelper.zero(newPassword)
            }
        }.start()
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
        addInviteUri(intent)
        startActivity(intent)
    }

    public override fun informUser(r: Int) {
        runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults)
        if (grantResults.isNotEmpty()) {
            if (PermissionUtils.allGranted(grantResults)) {
                when (requestCode) {
                    REQUEST_IMPORT_BACKUP -> {
                        startActivity(Intent(this, ImportBackupActivity::class.java))
                    }
                }
            } else if (permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show()
            }
        }
        if (PermissionUtils.writeGranted(grantResults, permissions)) {
            val service = xmppConnectionService
            if (service != null) {
                service.restartFileObserver()
            }
        }
    }

    protected fun hasInviteUri(): Boolean {
        val from = intent
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) return true
        return this.inviteUri != null
    }

    public fun addInviteUri(to: Intent) {
        val from = intent
        val uri = this.inviteUri
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) {
            val invite = from.getStringExtra(StartConversationActivity.EXTRA_INVITE_URI)
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, invite)
        } else if (uri != null) {
            Log.d(Config.LOGTAG, "injecting referrer uri into on-boarding flow")
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, uri.toString())
        }
    }

    companion object {
        private const val REQUEST_IMPORT_BACKUP = 0x63fb

        @JvmStatic
        public fun launch(activity: AppCompatActivity) {
            val intent = Intent(activity, WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
        }
    }
}
