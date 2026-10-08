package uk.xa0.tulkki.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.base.CharMatcher
import com.google.common.base.Strings
import com.rarepebble.colorpicker.ColorPickerView
import okhttp3.HttpUrl
import org.openintents.openpgp.util.OpenPgpUtils
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.data.model.PresenceTemplate
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.dialogs.showCaptchaDialog
import uk.xa0.tulkki.ui.editaccount.EditAccountField
import uk.xa0.tulkki.ui.editaccount.EditAccountScreen
import uk.xa0.tulkki.ui.editaccount.EditAccountScreenState
import uk.xa0.tulkki.ui.editaccount.EditAccountShareAction
import uk.xa0.tulkki.ui.editaccount.PresenceStatusChoice
import uk.xa0.tulkki.ui.editaccount.PresenceStatusDialog
import uk.xa0.tulkki.ui.editaccount.PresenceStatusDialogState
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.editaccount.PresenceTemplateOption
import uk.xa0.tulkki.ui.editaccount.VCardRowState
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.omemo.ContactKeyRowState
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.ui.utils.PermissionUtils
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.xmpp.utils.TorServiceUtils
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * The account editor. The Java surface is preserved member for member (proved with `javap` against
 * the compiled Java class); the only visibility change is `showColorDialog`, package-private in Java
 * because no external caller names it, widened to Kotlin's `public` as the register records for
 * `ThreadAdapter`/`WelcomePagerAdapter`.
 *
 * <p>**The layout and the menu are gone.** `activity_edit_account.xml` (1 030 lines, 76 ids) and
 * `R.menu.editaccount` are deleted, with the `captcha.xml`, `item_edit_vcard_entry.xml` and
 * `dialog_presence.xml` they pulled in. The screen is [EditAccountScreen] inside the shared chrome
 * ([TulkkiChrome]); the bar's three always-visible items are the chrome's `actions` slot and the rest
 * are its overflow. `setSupportActionBar`, `configureActionBar`, `setTitle`,
 * `Activities.setStatusAndNavigationBarColors`, `onCreateOptionsMenu`, `onPrepareOptionsMenu` and
 * `onOptionsItemSelected` went with the XML toolbar and its menu - every item they routed is a
 * callback on the chrome or on the screen now.
 *
 * <p>**Where the screen's state lives.** Every read the old listeners made through `binding` is a
 * read of [state] now and every write is a `copy` of it, so the branches - which field errors, which
 * card is visible, which of the save button's labels stands - are the old branches with their fields
 * renamed. Two things stay out of [state] because they are not drawn: [previewColor], which exists
 * only so `accountInfoEdited()` can tell "no swatch" from "swatch zero" exactly as the null
 * `ColorDrawable` did, and the account itself.
 *
 * <p>**The JID's known-hosts drop-down is computed here, not by `KnownHostsAdapter`.** The adapter's
 * `Filter` is asynchronous and takes the field, and this screen has no `AutoCompleteTextView` to hang
 * it on, so [refreshJidSuggestions] is the adapter's own rule written once more against a `String`.
 * The adapter stays for the two other screens that still use it; unifying the two is reported for a
 * later wave rather than done here, because the file belongs to another lane's hosts.
 */
class EditAccountActivity :
    OmemoActivity(),
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    OnUpdateBlocklist,
    OnKeyStatusUpdated,
    uk.xa0.tulkki.xmpp.services.OnCaptchaRequested,
    KeyChainAliasCallback,
    uk.xa0.tulkki.xmpp.services.OnShowErrorToast,
    uk.xa0.tulkki.xmpp.services.OnMamPreferencesFetched {

    private val mPendingPresenceTemplate = PendingItem<PresenceTemplate>()

    /**
     * The open CAPTCHA dialog's dismissal, or null.
     *
     * <p>It was an `AlertDialog` this screen built itself from `captcha.xml`; the Compose dialog
     * ([uk.xa0.tulkki.ui.dialogs.showCaptchaDialog]) is shown through `showTulkkiDialog` and answers
     * with the handle that closes it, because the window is not this Activity's to dismiss any more.
     */
    private var mCaptchaDialog: (() -> Unit)? = null

    private var jidToEdit: Jid? = null
    private var mInitMode = false
    private var mForceRegister: Boolean? = null
    private var mUsernameMode = false
    private var mShowOptions = false
    private var mAccount: Account? = null

    private var mVCardModified = false
    private var mIsLoadingVCard = false

    /**
     * The colour swatch's own value while it is not (yet) the account's: the old
     * `binding.colorPreview` background, null exactly when no `setBackgroundColor` had run.
     */
    private var previewColor: Int? = null

    /** What [EditAccountScreen] draws. Every old `binding` write is a `copy` of this. */
    private var state by mutableStateOf(EditAccountScreenState())

    /** The chrome's title, which the deleted `setTitle` calls used to set. */
    private var titleRes by mutableStateOf(R.string.account_details)

    /** The presence dialog, present exactly while it is up. */
    private var presenceDialog by mutableStateOf<PresenceStatusDialogState?>(null)

    /** The known hosts the JID's suggestions are built from, empty in the username mode. */
    private var mKnownHosts: List<String> = emptyList()

    /** The id the next dynamic vCard row takes; the old code had no id, only a `View`. */
    private var mNextVCardRowId = 0L

    /**
     * What the avatar bridge is keyed on. A new value makes the screen load the avatar again, which
     * is what each old `AvatarWorkerTask.loadAvatar` call did.
     */
    private var mAvatarToken: Any = Any()

    private val mAvatarFetchCallback = object : UiCallback<Avatar> {

        override fun userInputRequired(pi: PendingIntent?, obj: Avatar) {
            finishInitialSetup(obj)
        }

        override fun success(obj: Avatar) {
            finishInitialSetup(obj)
        }

        override fun error(errorCode: Int, obj: Avatar?) {
            finishInitialSetup(obj)
        }
    }

    private var messageFingerprint: String? = null
    private var mFetchingAvatar = false
    private var mFetchingMamPrefsToast: Toast? = null
    private var mSavedInstanceAccount: String? = null
    private var mSavedInstanceInit = false
    private var pendingUri: XmppUri? = null

    /**
     * The save button's work, exactly the old `mSaveButtonClickListener` with every `binding` read
     * replaced by a [state] read and every error write by [showError].
     */
    private fun saveAccount() {
        val password = state.password
        val account = mAccount
        val wasDisabled =
            account != null && account.getStatus() == Account.State.DISABLED
        val accountInfoEdited = accountInfoEdited()
        saveVCardData()
        mVCardModified = false // Reset flag
        refreshUi()
        val color = previewColor
        if (color != null && color != (account ?: throw NullPointerException()).getColor(isDark())) {
            (account ?: throw NullPointerException()).setColor(color)
        }

        if (mInitMode && account != null) {
            (account ?: throw NullPointerException()).setOption(Account.OPTION_DISABLED, false)
        }
        if (account != null &&
            listOf(Account.State.DISABLED, Account.State.LOGGED_OUT).contains(account.getStatus()) &&
            !accountInfoEdited
        ) {
            account.setOption(Account.OPTION_SOFT_DISABLED, false)
            account.setOption(Account.OPTION_DISABLED, false)
            if (!xmppConnectionService.updateAccount(account)) {
                Toast.makeText(
                    this@EditAccountActivity,
                    R.string.unable_to_update_account,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        val registerNewAccount =
            if (mForceRegister != null) {
                mForceRegister == true
            } else {
                state.registerNew && !Config.DISALLOW_REGISTRATION_IN_UI
            }
        if (mUsernameMode && state.jid.contains("@")) {
            showError(EditAccountField.Jid, getString(R.string.invalid_username))
            return
        }

        val connection = if (account == null) null else account.getXmppConnection()
        val startOrbot =
            account != null && account.getStatus() == Account.State.TOR_NOT_AVAILABLE
        val startI2P = account != null && account.getStatus() == Account.State.I2P_NOT_AVAILABLE
        if (startOrbot) {
            if (TorServiceUtils.isOrbotInstalled(this@EditAccountActivity)) {
                TorServiceUtils.startOrbot(this@EditAccountActivity, REQUEST_ORBOT)
            } else {
                TorServiceUtils.downloadOrbot(this@EditAccountActivity, REQUEST_ORBOT)
            }
            return
        }

        if (startI2P) {
            return // just exit
        }

        if (inNeedOfSaslAccept()) {
            (mAccount ?: throw NullPointerException()).resetPinnedMechanism()
            if (!xmppConnectionService.updateAccount(mAccount ?: throw NullPointerException())) {
                Toast.makeText(
                    this@EditAccountActivity,
                    R.string.unable_to_update_account,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }

        val openRegistrationUrl =
            registerNewAccount &&
                !accountInfoEdited &&
                account != null &&
                account.getStatus() == Account.State.REGISTRATION_WEB
        val openPaymentUrl =
            account != null && account.getStatus() == Account.State.PAYMENT_REQUIRED
        val redirectionWorthyStatus = openPaymentUrl || openRegistrationUrl
        val url =
            if (connection != null && redirectionWorthyStatus) {
                connection.getRedirectionUrl()
            } else {
                null
            }
        if (url != null && !wasDisabled) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                return
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this@EditAccountActivity,
                    R.string.application_found_to_open_website,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
        }

        val jid: Jid
        try {
            if (mUsernameMode) {
                jid =
                    Jid.of(
                        state.jid,
                        getUserModeDomain() ?: throw NullPointerException(),
                        null,
                    )
            } else {
                jid = Jid.ofUserInput(state.jid)
                Resolver.checkDomain(jid)
            }
        } catch (e: NullPointerException) {
            showError(
                EditAccountField.Jid,
                getString(if (mUsernameMode) R.string.invalid_username else R.string.invalid_jid),
            )
            return
        } catch (e: IllegalArgumentException) {
            showError(
                EditAccountField.Jid,
                getString(if (mUsernameMode) R.string.invalid_username else R.string.invalid_jid),
            )
            return
        }
        val hostname: String?
        var numericPort = 5222
        if (mShowOptions) {
            val host = CharMatcher.whitespace().removeFrom(state.hostname)
            hostname = host
            val port = CharMatcher.whitespace().removeFrom(state.port)
            if (Resolver.invalidHostname(host)) {
                showError(EditAccountField.Hostname, getString(R.string.not_valid_hostname))
                return
            }
            if (!host.isEmpty()) {
                try {
                    numericPort = Integer.parseInt(port)
                    if (numericPort < 0 || numericPort > 65535) {
                        showError(EditAccountField.Port, getString(R.string.not_a_valid_port))
                        return
                    }
                } catch (e: NumberFormatException) {
                    showError(EditAccountField.Port, getString(R.string.not_a_valid_port))
                    return
                }
            }
        } else {
            hostname = null
        }

        if (jid.getLocal() == null) {
            showError(
                EditAccountField.Jid,
                getString(if (mUsernameMode) R.string.invalid_username else R.string.invalid_jid),
            )
            return
        }
        if (account != null) {
            if (account.isOptionSet(Account.OPTION_MAGIC_CREATE)) {
                account.setOption(
                    Account.OPTION_MAGIC_CREATE,
                    (account.getPassword() ?: throw NullPointerException()).contains(password),
                )
            }
            account.setJid(jid)
            account.setPort(numericPort)
            account.setHostname(hostname)
            clearFieldError(EditAccountField.Jid)
            account.setPassword(password)
            account.setOption(Account.OPTION_REGISTER, registerNewAccount)
            if (!xmppConnectionService.updateAccount(account)) {
                Toast.makeText(
                    this@EditAccountActivity,
                    R.string.unable_to_update_account,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
        } else {
            if (AccountRegistry.get().findAccountByJid(jid) != null) {
                showError(
                    EditAccountField.Jid,
                    getString(uk.xa0.tulkki.xmpp.R.string.account_already_exists),
                )
                return
            }
            val created = Account(jid.asBareJid(), password)
            created.setPort(numericPort)
            created.setHostname(hostname)
            created.setOption(Account.OPTION_REGISTER, registerNewAccount)
            mAccount = created
            xmppConnectionService.createAccount(created)
        }
        clearFieldError(EditAccountField.Hostname)
        clearFieldError(EditAccountField.Port)
        val current = mAccount ?: throw NullPointerException()
        if (current.isOnion()) {
            Toast.makeText(
                this@EditAccountActivity,
                R.string.audio_video_disabled_tor,
                Toast.LENGTH_LONG,
            ).show()
        }
        if (current.isEnabled() && !registerNewAccount && !mInitMode) {
            finish()
        } else {
            updateSaveButton()
            updateAccountInformation(true)
        }
    }

    /** The old avatar click: the profile-picture screen, when there is an account. */
    private fun openAvatar() {
        val account = mAccount
        if (account != null) {
            val intent =
                Intent(applicationContext, PublishProfilePictureActivity::class.java)
            intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
            startActivity(intent)
        }
    }

    /**
     * The `TextWatcher` the deleted layout hung on all four connection fields: any edit re-reads the
     * silent port field and the save button's label.
     */
    private fun onConnectionFieldChanged() {
        updatePortLayout()
        updateSaveButton()
    }

    private fun jidChanged(value: String) {
        state = state.copy(jid = value)
        refreshJidSuggestions()
        onConnectionFieldChanged()
    }

    private fun passwordChanged(value: String) {
        state = state.copy(password = value)
        onConnectionFieldChanged()
    }

    private fun hostnameChanged(value: String) {
        state = state.copy(hostname = value)
        onConnectionFieldChanged()
    }

    private fun portChanged(value: String) {
        state = state.copy(port = value)
        onConnectionFieldChanged()
    }

    protected override fun processFingerprintVerification(uri: XmppUri) {
        processFingerprintVerification(uri, true)
    }

    protected fun processFingerprintVerification(uri: XmppUri, showWarningToast: Boolean) {
        val account = mAccount
        if (account != null &&
            account.getJid().asBareJid() == uri.getJid() &&
            uri.hasFingerprints()
        ) {
            if (xmppConnectionService.verifyFingerprints(account, uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show()
                updateAccountInformation(false)
            }
        } else if (showWarningToast) {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The port field is enabled exactly while a hostname is written, which is what the old
     * `updatePortLayout` did with `setEnabled`.
     */
    private fun updatePortLayout() {
        state =
            if (TextUtils.isEmpty(state.hostname)) {
                state.copy(portEnabled = false, portError = null)
            } else {
                state.copy(portEnabled = true)
            }
    }

    protected fun updateSaveButton() {
        val accountInfoEdited = accountInfoEdited()
        val account = mAccount

        if (accountInfoEdited && !mInitMode) {
            state = state.copy(saveLabel = R.string.save, saveEnabled = true)
        } else if (account != null &&
            (account.getStatus() == Account.State.CONNECTING ||
                account.getStatus() == Account.State.REGISTRATION_SUCCESSFUL ||
                mFetchingAvatar)
        ) {
            state =
                state.copy(
                    saveEnabled = false,
                    saveLabel = uk.xa0.tulkki.data.R.string.account_status_connecting,
                )
        } else if (account != null &&
            account.getStatus() == Account.State.DISABLED &&
            !mInitMode
        ) {
            state = state.copy(saveEnabled = true, saveLabel = R.string.enable)
        } else if (torNeedsInstall(account)) {
            state =
                state.copy(
                    saveEnabled = true,
                    saveLabel = uk.xa0.tulkki.xmpp.R.string.install_orbot,
                )
        } else if (torNeedsStart(account)) {
            state = state.copy(saveEnabled = true, saveLabel = R.string.start_orbot)
        } else {
            state = state.copy(saveEnabled = true)
            if (!mInitMode) {
                if (account != null && account.isOnlineAndConnected()) {
                    state = state.copy(saveLabel = R.string.save, saveEnabled = accountInfoEdited)
                } else {
                    val connection = account?.getXmppConnection()
                    val url =
                        if (connection != null &&
                            account != null &&
                            account.getStatus() == Account.State.PAYMENT_REQUIRED
                        ) {
                            connection.getRedirectionUrl()
                        } else {
                            null
                        }
                    state =
                        state.copy(
                            saveLabel =
                                if (url != null) {
                                    R.string.open_website
                                } else if (inNeedOfSaslAccept()) {
                                    R.string.accept
                                } else {
                                    R.string.connect
                                }
                        )
                }
            } else {
                val connection = account?.getXmppConnection()
                val url: HttpUrl? =
                    if (connection != null &&
                        account != null &&
                        account.getStatus() == Account.State.REGISTRATION_WEB
                    ) {
                        connection.getRedirectionUrl()
                    } else {
                        null
                    }
                state =
                    state.copy(
                        saveLabel =
                            if (url != null && state.registerNew && !accountInfoEdited) {
                                R.string.open_website
                            } else {
                                R.string.next
                            }
                    )
            }
        }
    }

    private fun torNeedsInstall(account: Account?): Boolean =
        account != null &&
            account.getStatus() == Account.State.TOR_NOT_AVAILABLE &&
            !TorServiceUtils.isOrbotInstalled(this)

    private fun torNeedsStart(account: Account?): Boolean =
        account != null && account.getStatus() == Account.State.TOR_NOT_AVAILABLE

    protected fun accountInfoEdited(): Boolean {
        val account = mAccount ?: return false
        return jidEdited() ||
            (account.getPassword() ?: throw NullPointerException()) != state.password ||
            account.getHostname() != state.hostname ||
            account.getColor(isDark()) != (previewColor ?: 0) ||
            account.getPort().toString() != state.port ||
            mVCardModified
    }

    protected fun jidEdited(): Boolean {
        val account = mAccount ?: throw NullPointerException()
        val unmodified: String =
            if (mUsernameMode) {
                account.getJid().getLocal() ?: throw NullPointerException()
            } else {
                account.getJid().asBareJid().toString()
            }
        return unmodified != state.jid
    }

    protected override fun getShareableUri(): String? {
        val account = mAccount
        return if (account != null) {
            account.getShareableUri()
        } else {
            null
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mSavedInstanceAccount = savedInstanceState.getString("account")
            mSavedInstanceInit = savedInstanceState.getBoolean("initMode", false)
        }
        // The deleted layout's `port` EditText was pre-filled with the STARTTLS port and then, on the
        // first `updateAccountInformation(true)`, overwritten with the account's own. The four
        // connection fields' contents were the framework's own view state across a recreation, and
        // `onSaveInstanceState` below carries them now - `state` is the Activity's, not a View's.
        state =
            EditAccountScreenState(
                jid = savedInstanceState?.getString("jid") ?: "",
                password = savedInstanceState?.getString("password") ?: "",
                hostname = savedInstanceState?.getString("hostname") ?: "",
                port =
                    savedInstanceState?.getString("port")
                        ?: Resolver.XMPP_PORT_STARTTLS.toString(),
                registerNewVisible = !Config.DISALLOW_REGISTRATION_IN_UI,
            )
        if (savedInstanceState != null && savedInstanceState.getBoolean("showMoreTable")) {
            changeMoreTableVisibility(true)
        }
        // The chrome owns the system bars, so the window must not inset itself for them.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) { EditAccountContent() }
    }

    /** The chrome and the screen, composed. Everything it reads is this Activity's own state. */
    @Composable
    private fun EditAccountContent() {
        val account = mAccount
        val online = account != null && account.isOnlineAndConnected()
        TulkkiChrome(
            title = stringResource(titleRes),
            // The arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`.
            onUp = {
                deleteAccountAndReturnIfNecessary()
                finish()
            },
            menu = buildList {
                if (account != null && online) {
                    val features = account.getXmppConnection()?.getFeatures()
                    if (features != null && features.blocking()) {
                        add(
                            ChromeMenuItem(stringResource(R.string.show_block_list)) {
                                openBlockList()
                            }
                        )
                    }
                }
                if (account != null && account.getPrivateKeyAlias() != null) {
                    add(
                        ChromeMenuItem(stringResource(R.string.action_renew_certificate)) {
                            renewCertificate()
                        }
                    )
                }
                if (account != null && online) {
                    val features = account.getXmppConnection()?.getFeatures()
                    add(
                        ChromeMenuItem(stringResource(R.string.server_info_show_more)) {
                            changeMoreTableVisibility(!state.showMore)
                        }
                    )
                    if (features != null && features.mam()) {
                        add(ChromeMenuItem(stringResource(R.string.mam_prefs)) { editMamPrefs() })
                    }
                    if (features != null && features.register()) {
                        add(
                            ChromeMenuItem(stringResource(R.string.change_password)) {
                                gotoChangePassword()
                            }
                        )
                        add(
                            ChromeMenuItem(stringResource(R.string.mgmt_account_delete)) {
                                deleteAccount()
                            }
                        )
                    }
                }
                add(ChromeMenuItem(stringResource(R.string.action_settings)) { openSettings() })
            },
            actions = {
                if (account == null || mInitMode) {
                    IconButton(onClick = { importBackup() }) {
                        Icon(
                            painter = painterResource(R.drawable.backup_restore_24dp),
                            contentDescription = stringResource(R.string.restore_backup),
                        )
                    }
                }
                if (account != null && online && !mInitMode) {
                    IconButton(onClick = { changePresence() }) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_info_24),
                            contentDescription = stringResource(R.string.edit_status_message),
                        )
                    }
                }
                EditAccountShareAction(
                    visible = account != null && !mInitMode,
                    onShareUri = { shareLink() },
                    onShareBarcode = { shareBarcode() },
                    onShowQrCode = { showQrCode() },
                )
            },
        ) {
            EditAccountScreen(
                state = state,
                onJidChange = { jidChanged(it) },
                onPasswordChange = { passwordChanged(it) },
                onHostnameChange = { hostnameChanged(it) },
                onPortChange = { portChanged(it) },
                onRegisterNewChange = { checked ->
                    state = state.copy(registerNew = checked)
                    updateSaveButton()
                },
                onAvatarClick = { openAvatar() },
                onBindAvatar = { view ->
                    mAccount?.let {
                        AvatarWorkerTask.loadAvatar(
                            it,
                            view,
                            R.dimen.avatar_on_details_screen_size,
                        )
                    }
                },
                onEditDisplayName = { onEditYourNameClicked() },
                onAccountColorClick = { showColorDialog() },
                onQuietHoursEnabledChange = { checked -> applyQuietHoursEnabled(checked) },
                onQuietHoursStartClick = { pickQuietHours(QUIET_HOURS_START, start = true) },
                onQuietHoursEndClick = { pickQuietHours(QUIET_HOURS_END, start = false) },
                onAddVCardRow = {
                    addVCardRow("org", "")
                    mVCardModified = true
                    refreshUi()
                },
                onVCardTypeChange = { id, type -> applyVCardType(id, type) },
                onVCardValueChange = { id, value -> applyVCardValue(id, value) },
                onVCardRemove = { id -> removeVCardRow(id) },
                onVCardMove = { from, to -> moveVCardRow(from, to) },
                onShowVCardQr = { showVCardQr() },
                onOsOptimizationAction = { applyOsOptimizationAction() },
                onPgpClick = {
                    val pgpKeyId = mAccount?.getPgpId() ?: 0L
                    if (pgpKeyId != 0L) {
                        launchOpenKeyChain(pgpKeyId)
                    }
                },
                onDeletePgp = { showDeletePgpDialog() },
                onCopyOmemoFingerprint = {
                    val fingerprint = mAccount?.getAxolotlService()?.getOwnFingerprint()
                    if (fingerprint != null) {
                        copyOmemoFingerprint(fingerprint)
                    }
                },
                onOmemoQr = { showQrCode() },
                onClearDevices = { showWipePepDialog() },
                onScan = { ScanActivity.scan(this) },
                onCancel = {
                    deleteAccountAndReturnIfNecessary()
                    finish()
                },
                onSave = { saveAccount() },
            )
        }
        presenceDialog?.let { dialog ->
            PresenceStatusDialog(
                state = dialog,
                onStatusChange = { choice -> presenceDialog = dialog.copy(status = choice) },
                onMessageChange = { message -> presenceDialog = dialog.copy(message = message) },
                onDismiss = { presenceDialog = null },
                onConfirm = { confirmPresence(dialog) },
            )
        }
    }

    /** The `action_show_block_list` item: the block list for this account. */
    private fun openBlockList() {
        val showBlocklistIntent = Intent(this, BlocklistActivity::class.java)
        showBlocklistIntent.putExtra(
            XmppActivity.EXTRA_ACCOUNT,
            (mAccount ?: throw NullPointerException()).getJid().toString(),
        )
        startActivity(showBlocklistIntent)
    }

    /** The `action_settings` item, which the base `onOptionsItemSelected` routed to settings. */
    private fun openSettings() {
        startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
    }

    /** The `action_import_backup` item, with its storage permission. */
    private fun importBackup() {
        if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
            startActivity(Intent(this, ImportBackupActivity::class.java))
        }
    }

    /** `quickEdit`'s display-name editor, exactly the old `onEditYourNameClicked`. */
    private fun onEditYourNameClicked() {
        quickEdit(
            (mAccount ?: throw NullPointerException()).getDisplayName(),
            R.string.your_name,
            { value ->
                val displayName = value.trim()
                updateDisplayName(displayName)
                (mAccount ?: throw NullPointerException()).setDisplayName(displayName)
                xmppConnectionService.publishDisplayName(mAccount ?: throw NullPointerException())
                refreshAvatar()
                null
            },
            true,
        )
    }

    /**
     * A new avatar token, which is what makes the screen's bridge load the avatar again - the old
     * `AvatarWorkerTask.loadAvatar` call in `refreshAvatar`.
     */
    private fun refreshAvatar() {
        mAvatarToken = Any()
        state = state.copy(avatarKey = mAvatarToken)
    }

    /** The quiet-hours switch, which wrote the account's preference and then re-read everything. */
    private fun applyQuietHoursEnabled(checked: Boolean) {
        val account = mAccount ?: throw NullPointerException()
        getPreferences()
            .edit()
            .putBoolean("enable_quiet_hours:" + account.getUuid(), checked)
            .apply()
        updateAccountInformation(false)
    }

    /** One of the two quiet-hours time pickers; [key] chooses which preference it reads and writes. */
    private fun pickQuietHours(key: String, start: Boolean) {
        val account = mAccount ?: throw NullPointerException()
        val preferences = getPreferences()
        val title =
            if (start) {
                R.string.editaccount_quiet_hours_start_title
            } else {
                R.string.editaccount_quiet_hours_end_title
            }
        val picker =
            com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTitleText(getString(title))
                .setHour((preferences.getLong(key + account.getUuid(), 1320) / 60).toInt())
                .setMinute((preferences.getLong(key + account.getUuid(), 1320) % 60).toInt())
                .build()
        picker.addOnPositiveButtonClickListener {
            preferences
                .edit()
                .putLong(
                    key + account.getUuid(),
                    (picker.getHour() * 60 + picker.getMinute()).toLong(),
                )
                .apply()
            updateAccountInformation(false)
        }
        picker.show(getSupportFragmentManager(), key)
    }

    /** The vCard QR button, which answered with the first toast when there was nothing to draw. */
    private fun showVCardQr() {
        val vcardString = generateVCardString()
        if (!vcardString.isNullOrEmpty()) {
            showQrCodeDialog(vcardString)
        } else {
            Toast.makeText(this, R.string.editaccount_vcard_qr_empty, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The battery / data-saver card's one button. Which intent it starts is the card's own headline,
     * exactly the two `setOnClickListener`s the old `showOsOptimizationWarning` installed.
     */
    private fun applyOsOptimizationAction() {
        val packageName = packageName
        if (state.osOptimizationAction == R.string.allow) {
            val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS)
            intent.setData(Uri.parse("package:$packageName"))
            try {
                startActivityForResult(intent, REQUEST_DATA_SAVER)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    getString(R.string.device_does_not_support_data_saver, BuildConfig.APP_NAME),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.setData(Uri.parse("package:$packageName"))
            try {
                startActivityForResult(intent, XmppActivity.REQUEST_BATTERY_OP)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.device_does_not_support_battery_op,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    public override fun onBackPressed() {
        deleteAccountAndReturnIfNecessary()
        super.onBackPressed()
    }

    private fun deleteAccountAndReturnIfNecessary() {
        val account = mAccount
        if (mInitMode &&
            account != null &&
            !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
        ) {
            xmppConnectionService.deleteAccount(account)
        }

        val magicCreate =
            account != null &&
                account.isOptionSet(Account.OPTION_MAGIC_CREATE) &&
                !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
        val jid = account?.getJid()

        // Tulkki: the `!jid.getDomain().equals(Config.MAGIC_CREATE_DOMAIN)` guard is deleted with that
        // constant, exactly as the Java comment records.
        if (UiHost.installed().tokenRegistrySupported() && jid != null && magicCreate) {
            val preset: Jid =
                if (account != null && account.isOptionSet(Account.OPTION_FIXED_USERNAME)) {
                    jid.asBareJid()
                } else {
                    jid.getDomain()
                }
            val intent =
                UiHost.installed()
                    .tokenRegistrationIntent(
                        this,
                        preset.toString(),
                        account?.getKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN),
                    )
            StartConversationActivity.addInviteUri(intent, getIntent())
            startActivity(intent)
            return
        }

        val accounts =
            if (xmppConnectionService == null) null else AccountRegistry.get().getAccounts()
        if (accounts != null && accounts.size == 0) {
            val intent =
                UiHost.installed()
                    .signUpIntent(this, mForceRegister != null && mForceRegister == true)
            StartConversationActivity.addInviteUri(intent, getIntent())
            startActivity(intent)
        }
    }

    override fun onAccountUpdate() {
        refreshUi()
    }

    protected fun finishInitialSetup(avatar: Avatar?) {
        runOnUiThread {
            SoftKeyboardUtils.hideSoftKeyboard(this)
            val intent: Intent
            val account = mAccount ?: throw NullPointerException()
            val connection = account.getXmppConnection()
            val wasFirstAccount =
                xmppConnectionService != null && AccountRegistry.get().getAccounts().size == 1
            if (avatar != null || (connection != null && !connection.getFeatures().pep())) {
                intent = Intent(applicationContext, StartConversationActivity::class.java)
                intent.putExtra("init", true)
                intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
            } else {
                intent = Intent(applicationContext, PublishProfilePictureActivity::class.java)
                intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
                intent.putExtra("setup", true)
            }
            if (wasFirstAccount) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            StartConversationActivity.addInviteUri(intent, getIntent())
            startActivity(intent)
            finish()
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // TODO check for Camera / Scan permission
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == XmppActivity.REQUEST_BATTERY_OP || requestCode == REQUEST_DATA_SAVER) {
            updateAccountInformation(mAccount == null)
        }
        if (requestCode == XmppActivity.REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            uk.xa0.tulkki.xmpp.services.ForegroundServiceLifecycle
                .toggleForegroundService(xmppConnectionService)
        }
        if (requestCode == REQUEST_CHANGE_STATUS) {
            val template = mPendingPresenceTemplate.pop()
            if (template != null && resultCode == Activity.RESULT_OK) {
                generateSignature(data, template)
            } else {
                Log.d(Config.LOGTAG, "pgp result not ok")
            }
        }
        if (requestCode == REQUEST_UNLOCK) {
            if (resultCode == Activity.RESULT_OK) {
                openChangePassword(true)
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        val intent = getIntent()
        if (intent != null) {
            try {
                jidToEdit = Jid.of(intent.getStringExtra("jid") ?: throw NullPointerException())
            } catch (e: IllegalArgumentException) {
                jidToEdit = null
            } catch (e: NullPointerException) {
                jidToEdit = null
            }
            val data = intent.getData()
            val xmppUri = if (data == null) null else XmppUri(data)
            val scanned = intent.getBooleanExtra("scanned", false)
            if (jidToEdit != null && xmppUri != null && xmppUri.hasFingerprints()) {
                if (scanned) {
                    if (xmppConnectionServiceBound) {
                        processFingerprintVerification(xmppUri, false)
                    } else {
                        pendingUri = xmppUri
                    }
                } else {
                    displayVerificationWarningDialog(xmppUri)
                }
            }
            val init = intent.getBooleanExtra("init", false)
            val openedFromNotification =
                intent.getBooleanExtra(EXTRA_OPENED_FROM_NOTIFICATION, false)
            Log.d(Config.LOGTAG, "extras " + intent.getExtras())
            mForceRegister =
                if (intent.hasExtra(EXTRA_FORCE_REGISTER)) {
                    intent.getBooleanExtra(EXTRA_FORCE_REGISTER, false)
                } else {
                    null
                }
            Log.d(Config.LOGTAG, "force register=" + mForceRegister)
            mInitMode = init || jidToEdit == null
            messageFingerprint = intent.getStringExtra("fingerprint")
            if (!mInitMode) {
                titleRes = R.string.account_details
                state = state.copy(registerNewVisible = false)
            } else {
                state = state.copy(showAvatar = false, showVCard = false)
                titleRes =
                    if (mForceRegister != null) {
                        if (mForceRegister == true) {
                            R.string.register_new_account
                        } else {
                            R.string.add_existing_account
                        }
                    } else {
                        R.string.action_add_account
                    }
            }
            if (openedFromNotification) {
                // Kept for parity with the old `configureActionBar(..., !openedFromNotification)`;
                // the chrome always draws the arrow, exactly as the Java third argument did.
                Log.d(Config.LOGTAG, "opened from notification")
            }
        }
        val preferences = getPreferences()
        mUseTor =
            preferences.getBoolean(
                "use_tor",
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.use_tor),
            )
        mUseI2P =
            preferences.getBoolean(
                "use_i2p",
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.use_i2p),
            )
        mShowOptions =
            mUseTor ||
                mUseI2P ||
                preferences.getBoolean(
                    "show_connection_options",
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.show_connection_options),
                )
        state =
            state.copy(
                showNamePort = mShowOptions,
                hostnamePlaceholder =
                    if (mUseTor) R.string.hostname_or_onion else R.string.hostname_example,
                jidPlaceholder =
                    if (mUsernameMode) {
                        R.string.username
                    } else {
                        R.string.account_settings_example_jabber_id
                    },
            )
        if (mForceRegister != null) {
            state = state.copy(registerNewVisible = false)
        }
        if (intent != null && intent.getBooleanExtra("snikket", false)) {
            state = state.copy(jidHint = R.string.editaccount_snikket_address)
        }
    }

    private fun displayVerificationWarningDialog(xmppUri: XmppUri) {
        // `dialog_verify_fingerprints.xml` is gone: the warning and its checkbox are
        // `VerifyFingerprintsDialog`, and the three answers are the same code. The outside tap is
        // still refused and the back gesture still finishes the screen, which is the dialog's
        // `onDismiss` - the old `setOnCancelListener` and the Cancel button in one.
        showTulkkiDialog { dismiss ->
            VerifyFingerprintsDialog(
                warningRes = R.string.verifying_omemo_keys_trusted_source_account,
                confirmRes = R.string.continue_btn,
                onDismiss = {
                    dismiss()
                    finish()
                },
                onConfirm = { trusted ->
                    dismiss()
                    if (trusted) {
                        processFingerprintVerification(xmppUri, false)
                    } else {
                        finish()
                    }
                },
            )
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.getData()
        if (data != null) {
            val uri = XmppUri(data)
            if (xmppConnectionServiceBound) {
                processFingerprintVerification(uri, false)
            } else {
                pendingUri = uri
            }
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        val account = mAccount
        if (account != null) {
            savedInstanceState.putString("account", account.getJid().asBareJid().toString())
            savedInstanceState.putBoolean("initMode", mInitMode)
            savedInstanceState.putBoolean("showMoreTable", state.showMore)
            // What the framework's own view state used to carry for the four `EditText`s.
            savedInstanceState.putString("jid", state.jid)
            savedInstanceState.putString("password", state.password)
            savedInstanceState.putString("hostname", state.hostname)
            savedInstanceState.putString("port", state.port)
        }
        super.onSaveInstanceState(savedInstanceState)
    }

    protected override fun onBackendConnected() {
        var init = true
        val savedInstanceAccount = mSavedInstanceAccount
        if (savedInstanceAccount != null) {
            try {
                mAccount = AccountRegistry.get().findAccountByJid(Jid.of(savedInstanceAccount))
                mInitMode = mSavedInstanceInit
                init = false
            } catch (e: IllegalArgumentException) {
                mAccount = null
            }
        } else {
            val jid = jidToEdit
            if (jid != null) {
                mAccount = AccountRegistry.get().findAccountByJid(jid)
            }
        }

        val account = mAccount
        if (account != null) {
            mInitMode = mInitMode or account.isOptionSet(Account.OPTION_REGISTER)
            mUsernameMode =
                mUsernameMode or
                    (account.isOptionSet(Account.OPTION_MAGIC_CREATE) &&
                        account.isOptionSet(Account.OPTION_REGISTER))
            val pending = mPendingFingerprintVerificationUri
            if (pending != null) {
                processFingerprintVerification(pending, false)
                mPendingFingerprintVerificationUri = null
            }
            updateAccountInformation(init)
        }

        // Tulkki: `if (Config.MAGIC_CREATE_DOMAIN == null && ...) cancelButton.setEnabled(false)` stood
        // here in Java; its first half was already false, so the branch never fired and deleting it
        // changes nothing.
        if (mUsernameMode) {
            mKnownHosts = emptyList()
            state =
                state.copy(
                    jidHint = R.string.username_hint,
                    jidPlaceholder = R.string.username,
                    jidSuggestions = emptyList(),
                )
        } else {
            mKnownHosts = xmppConnectionService.getKnownHosts().sorted()
            refreshJidSuggestions()
        }

        val pendingUri = pendingUri
        if (pendingUri != null) {
            processFingerprintVerification(pendingUri, false)
            this.pendingUri = null
        }
        loadVCardData()
        updatePortLayout()
        updateSaveButton()
    }

    /**
     * The base class's abstract refresh, carried over from the old override: it finishes the initial
     * setup by fetching the first avatar, re-reads the account information and resets the save button.
     *
     * <p>The old body began with `invalidateOptionsMenu()`, which has no counterpart here:
     * `onPrepareOptionsMenu` and its menu went with the XML toolbar, and the chrome's menu is composed
     * from [state] in [EditAccountContent], so the two `updateAccountInformation` / `updateSaveButton`
     * writes below are what recompose it.
     */
    protected override fun refreshUiReal() {
        val account = mAccount
        if (account != null && account.getStatus() != Account.State.ONLINE && mFetchingAvatar) {
            val intent = Intent(this, StartConversationActivity::class.java)
            StartConversationActivity.addInviteUri(intent, getIntent())
            startActivity(intent)
            finish()
        } else if (mInitMode && account != null && account.getStatus() == Account.State.ONLINE) {
            if (!mFetchingAvatar) {
                mFetchingAvatar = true
                xmppConnectionService.checkForAvatar(account, mAvatarFetchCallback)
            }
        }
        if (account != null) {
            updateAccountInformation(false)
        }
        updateSaveButton()
    }

    /**
     * The JID's suggestions, built the way `KnownHostsAdapter`'s `Filter` builds them: the address
     * split on `@`, one suggestion per known host, and a bare `+`-address becoming the quicksy
     * domain. The split is `Pattern.split`, so a trailing `@` is dropped exactly as Java dropped it.
     */
    private fun refreshJidSuggestions() {
        if (mUsernameMode) {
            state = state.copy(jidSuggestions = emptyList())
            return
        }
        val split = AT_PATTERN.split(state.jid)
        val suggestions: List<String> =
            when (split.size) {
                1 -> {
                    val local = split[0].lowercase(Locale.ENGLISH)
                    val quicksy = Config.QUICKSY_DOMAIN
                    if (quicksy != null && E164_PATTERN.matcher(local).matches()) {
                        listOf(local + '@' + quicksy)
                    } else {
                        mKnownHosts.map { local + '@' + it }
                    }
                }
                2 -> {
                    val localPart = split[0].lowercase(Locale.ENGLISH)
                    val domainPart = split[1].lowercase(Locale.ENGLISH)
                    if (mKnownHosts.contains(domainPart)) {
                        emptyList()
                    } else {
                        mKnownHosts.filter { it.contains(domainPart) }
                            .map { localPart + "@" + it }
                    }
                }
                else -> emptyList()
            }
        state = state.copy(jidSuggestions = suggestions)
    }

    private fun getUserModeDomain(): String? {
        val account = mAccount
        return if (account != null && account.getJid().getDomain() != null) {
            account.getServer()
        } else {
            null
        }
    }

    private fun deleteAccount() {
        deleteAccount(mAccount ?: throw NullPointerException(), Runnable { finish() })
    }

    private fun inNeedOfSaslAccept(): Boolean {
        val account = mAccount
        return account != null &&
            account.getLastErrorStatus() == Account.State.DOWNGRADE_ATTACK &&
            account.getPinnedMechanismPriority() >= 0 &&
            !accountInfoEdited()
    }

    private fun shareBarcode() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(
            Intent.EXTRA_STREAM,
            UiHost.installed().barcodeUri(this, mAccount ?: throw NullPointerException()),
        )
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setType("image/png")
        startActivity(Intent.createChooser(intent, getText(R.string.share_with)))
    }

    private fun changeMoreTableVisibility(visible: Boolean) {
        state = state.copy(showMore = visible)
    }

    private fun gotoChangePassword() {
        val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val credentialsIntent =
            keyguardManager.createConfirmDeviceCredentialIntent(
                "Unlock required",
                "Please unlock in order to change your password",
            )
        if (credentialsIntent == null) {
            openChangePassword(false)
        } else {
            startActivityForResult(credentialsIntent, REQUEST_UNLOCK)
        }
    }

    private fun openChangePassword(didUnlock: Boolean) {
        val changePasswordIntent = Intent(this, ChangePasswordActivity::class.java)
        changePasswordIntent.putExtra(
            XmppActivity.EXTRA_ACCOUNT,
            (mAccount ?: throw NullPointerException()).getJid().toString(),
        )
        changePasswordIntent.putExtra("did_unlock", didUnlock)
        startActivity(changePasswordIntent)
    }

    private fun renewCertificate() {
        KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null)
    }

    /**
     * The `action_change_presence` item's dialog, now [PresenceStatusDialog]: the four availabilities
     * under the app's own "manually change presence" gate, and the status-message field with the
     * account's templates.
     */
    private fun changePresence() {
        val sharedPreferences = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val manualStatus =
            sharedPreferences.getBoolean(
                AppSettings.MANUALLY_CHANGE_PRESENCE,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.manually_change_presence),
            )
        val account = mAccount ?: throw NullPointerException()
        val templates =
            xmppConnectionService
                .getPresenceTemplates(account)
                .mapNotNull { ref ->
                    val message = ref.getStatusMessage() ?: return@mapNotNull null
                    PresenceTemplateOption(
                        message = message,
                        status = Presence.Status.fromRef(ref.getStatusRef()).toChoice(),
                    )
                }
        presenceDialog =
            PresenceStatusDialogState(
                message = account.getPresenceStatusMessage().orEmpty(),
                status = account.getPresenceStatus().toChoice(),
                showStatuses = manualStatus,
                templates = templates,
            )
    }

    /** The dialog's OK: the same template the old positive button built, and the same two paths. */
    private fun confirmPresence(dialog: PresenceStatusDialogState) {
        presenceDialog = null
        val account = mAccount ?: throw NullPointerException()
        val template =
            PresenceTemplate(dialog.status.toStatus(), dialog.message.trim { it <= ' ' })
        if (account.getPgpId() != 0L && hasPgp()) {
            generateSignature(null, template)
        } else {
            xmppConnectionService.changeStatus(account, template, null)
        }
    }

    private fun generateSignature(intent: Intent?, template: PresenceTemplate) {
        (xmppConnectionService.getPgpEngine() ?: throw NullPointerException())
            .generateSignature(
                intent,
                mAccount ?: throw NullPointerException(),
                template.getStatusMessage() ?: throw NullPointerException(),
                object : UiCallback<String> {
                    override fun success(obj: String) {
                        xmppConnectionService.changeStatus(
                            mAccount ?: throw NullPointerException(),
                            template,
                            obj,
                        )
                    }

                    override fun error(errorCode: Int, obj: String?) {}

                    override fun userInputRequired(pi: PendingIntent?, obj: String) {
                        mPendingPresenceTemplate.push(template)
                        try {
                            startIntentSenderForResult(
                                (pi ?: throw NullPointerException()).getIntentSender(),
                                REQUEST_CHANGE_STATUS,
                                null,
                                0,
                                0,
                                0,
                                UiHost.installed().pgpStartIntentSenderOptions(),
                            )
                        } catch (e: IntentSender.SendIntentException) {
                        }
                    }
                },
            )
    }

    override fun alias(alias: String?) {
        if (alias != null) {
            xmppConnectionService.updateKeyInAccount(
                mAccount ?: throw NullPointerException(),
                alias,
            )
        }
    }

    fun showColorDialog() {
        val builder = AlertDialog.Builder(this)
        val picker = ColorPickerView(this)

        val account = mAccount
        if (account != null) picker.setColor(account.getColor(isDark()))
        picker.showAlpha(true)
        picker.showHex(true)
        picker.showPreview(true)
        builder
            .setTitle(null as CharSequence?)
            .setView(picker)
            .setPositiveButton(R.string.ok) { _, _ ->
                val color = picker.getColor()
                previewColor = color
                state = state.copy(accountColor = color)
                updateSaveButton()
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel) { _, _ -> }
        builder.show()
    }

    private fun updateAccountInformation(init: Boolean) {
        val account = mAccount ?: throw NullPointerException()
        if (init) {
            state =
                state.copy(
                    jid =
                        if (mUsernameMode) {
                            account.getJid().getLocal().orEmpty()
                        } else {
                            account.getJid().asBareJid().toString()
                        },
                    password = account.getPassword().orEmpty(),
                    hostname = account.getHostname().orEmpty(),
                    port = account.getPort().toString(),
                    showNamePort = mShowOptions,
                )
            refreshJidSuggestions()
        }

        val preferences = getPreferences()
        val quietHoursEnabled =
            preferences.getBoolean("enable_quiet_hours:" + account.getUuid(), false)

        val startTime = preferences.getLong("quiet_hours_start:" + account.getUuid(), 1320)
        val dateFormat: java.text.DateFormat = android.text.format.DateFormat.getTimeFormat(this)
        val date = TimePreference.minutesToCalender(startTime).getTime()

        val endTime = preferences.getLong("quiet_hours_end:" + account.getUuid(), 480)
        val dateE = TimePreference.minutesToCalender(endTime).getTime()

        val editable =
            !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY) &&
                !account.isOptionSet(Account.OPTION_FIXED_USERNAME)

        val togglePassword =
            account.isOptionSet(Account.OPTION_MAGIC_CREATE) ||
                !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
        val neverLoggedIn = !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
        val editPassword = account.unauthorized() || neverLoggedIn

        val multipleAccounts =
            xmppConnectionService != null && AccountRegistry.get().getAccounts().size > 1
        if (multipleAccounts) {
            previewColor = account.getColor(isDark())
        }

        var next =
            state.copy(
                quietHoursEnabled = quietHoursEnabled,
                quietHoursStart = dateFormat.format(date.getTime()),
                quietHoursEnd = dateFormat.format(dateE.getTime()),
                jidEnabled = editable,
                displayName = account.getDisplayName(),
                accountColorVisible = multipleAccounts,
                quietHoursVisible = multipleAccounts,
                accountColor = previewColor ?: 0,
                passwordToggleEnabled = togglePassword,
                passwordEditable = editPassword,
            )

        // `binding.accountPassword.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO)` has no Compose
        // counterpart on a `TextField`; it is not carried, and the field's own autofill semantics are
        // what the framework gives a Compose text field.

        if (!mInitMode) {
            next = next.copy(showAvatar = true, avatarKey = mAvatarToken)
        } else {
            next = next.copy(showAvatar = false)
        }
        next = next.copy(registerNew = account.isOptionSet(Account.OPTION_REGISTER))
        next =
            when {
                account.isOptionSet(Account.OPTION_MAGIC_CREATE) -> {
                    if (account.isOptionSet(Account.OPTION_REGISTER)) {
                        titleRes = R.string.create_account
                    }
                    next.copy(registerNewVisible = false)
                }
                account.isOptionSet(Account.OPTION_REGISTER) && mForceRegister == null ->
                    next.copy(registerNewVisible = true)
                else -> next.copy(registerNewVisible = false)
            }
        if (account.isOnlineAndConnected() && !mFetchingAvatar) {
            val features = (account.getXmppConnection() ?: throw NullPointerException()).getFeatures()
            val showBatteryWarning = isOptimizingBattery()
            val showDataSaverWarning = isAffectedByDataSaver()
            next =
                next.copy(
                    showStats = true,
                    showVCard = true,
                    sessionEstablished =
                        UIHelper.readableTimeDifferenceFull(
                            this,
                            (account.getXmppConnection() ?: throw NullPointerException())
                                .getLastSessionEstablished(),
                            allowRelativeTimestamps(),
                        ),
                    rosterVersion = available(features.rosterVersioning()),
                    carbons = available(features.carbons()),
                    mam = available(features.mam()),
                    csi = available(features.csi()),
                    blocking = available(features.blocking()),
                    sm = available(features.sm()),
                    externalService = available(features.externalServiceDiscovery()),
                    bind2 = available(features.bind2()),
                    sasl2 = available(features.sasl2()),
                    loginMechanism = Strings.nullToEmpty(features.loginMechanism()),
                    pep =
                        pepState(
                            account,
                            features.pep(),
                            features.pepPublishOptions(),
                            features.pepOmemoWhitelisted(),
                        ),
                    httpUpload = httpUploadState(features),
                    pushVisible = !xmppConnectionService.getPushManagementService().isStub(),
                    push =
                        available(
                            xmppConnectionService.getPushManagementService().available(account)
                        ),
                )
            // The battery / data-saver card, `showOsOptimizationWarning`'s two branches.
            val dataSaver = showDataSaverWarning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            next =
                next.copy(
                    osOptimizationVisible = showBatteryWarning || showDataSaverWarning,
                    osOptimizationHeadline =
                        if (dataSaver) {
                            R.string.data_saver_enabled
                        } else {
                            R.string.battery_optimizations_enabled
                        },
                    osOptimizationBody =
                        when {
                            dataSaver ->
                                getString(R.string.data_saver_enabled_explained, BuildConfig.APP_NAME)
                            showBatteryWarning ->
                                getString(
                                    R.string.battery_optimizations_enabled_explained,
                                    BuildConfig.APP_NAME,
                                )
                            else -> ""
                        },
                    osOptimizationAction = if (dataSaver) R.string.allow else R.string.disable,
                )
            val pgpKeyId = mAccount?.getPgpId() ?: 0L
            if (pgpKeyId != 0L && Config.supportOpenPgp()) {
                next =
                    next.copy(
                        pgpVisible = true,
                        pgpFingerprint = OpenPgpUtils.convertKeyIdToHex(pgpKeyId),
                        pgpHighlighted = "pgp" == messageFingerprint,
                    )
            } else {
                next = next.copy(pgpVisible = false)
            }
            val ownAxolotlFingerprint =
                (account.getAxolotlService() ?: throw NullPointerException()).getOwnFingerprint()
            if (ownAxolotlFingerprint != null && Config.supportOmemo()) {
                next =
                    next.copy(
                        omemoVisible = true,
                        omemoFingerprint =
                            CryptoHelper.prettifyFingerprint(ownAxolotlFingerprint.substring(2)),
                        omemoDesc =
                            if (ownAxolotlFingerprint == messageFingerprint) {
                                R.string.omemo_fingerprint_selected_message
                            } else {
                                R.string.omemo_fingerprint
                            },
                        omemoHighlighted = ownAxolotlFingerprint == messageFingerprint,
                    )
            } else {
                next = next.copy(omemoVisible = false)
            }
            val axolotlService = (mAccount?.getAxolotlService() ?: throw NullPointerException())
            val sessions = axolotlService.findOwnSessions()
            val shownSessions = sessions.filter { !it.getTrust().isCompromised() }
            val showUnverifiedWarning = sessions.any { it.getTrust().isUnverified() }
            if (shownSessions.isNotEmpty() && Config.supportOmemo()) {
                val otherDevices = account.getAxolotlService()?.getOwnDeviceIds()
                next =
                    next.copy(
                        otherDevicesVisible = true,
                        otherDeviceKeys = otherDeviceKeyRows(),
                        clearDevicesVisible = !(otherDevices == null || otherDevices.isEmpty()),
                        unverifiedWarningVisible = showUnverifiedWarning,
                        scanVisible = showUnverifiedWarning,
                    )
            } else {
                next = next.copy(otherDevicesVisible = false)
            }
            next = next.copy(verificationVisible = true)
            val connection = account.getXmppConnection()
            val authenticated = connection != null && connection.resolverAuthenticated()
            next =
                next.copy(
                    verificationMessage =
                        when {
                            authenticated && connection != null && connection.daneVerified() ->
                                getString(R.string.dnssec_dane_verified)
                            authenticated -> getString(R.string.dnssec_verified)
                            else -> getString(R.string.not_dnssec_verified)
                        },
                    verificationIndicator =
                        when {
                            authenticated && connection != null && connection.daneVerified() ->
                                R.drawable.shield_verified
                            authenticated -> R.drawable.shield
                            else -> R.drawable.shield_question
                        },
                )
            state = next
            return
        }

        val status = mAccount?.getStatus() ?: throw NullPointerException()
        val field: EditAccountField?
        if (status.isError ||
            listOf(Account.State.NO_INTERNET, Account.State.MISSING_INTERNET_PERMISSION)
                .contains(status)
        ) {
            field =
                when {
                    status == Account.State.UNAUTHORIZED ||
                        status == Account.State.DOWNGRADE_ATTACK -> EditAccountField.Password
                    mShowOptions &&
                        status == Account.State.SERVER_NOT_FOUND &&
                        state.hostname.length > 0 -> EditAccountField.Hostname
                    else -> EditAccountField.Jid
                }
        } else {
            field = null
        }
        state =
            next.copy(
                showStats = false,
                showVCard = false,
                otherDevicesVisible = false,
                verificationVisible = false,
            )
        if (field != null) {
            showError(
                field,
                getString(status.getReadableId()),
                focus = init || !accountInfoEdited(),
            )
        } else {
            removeErrorsOnAllBut(null)
        }
    }

    private fun available(value: Boolean): String =
        getString(
            if (value) R.string.server_info_available else R.string.server_info_unavailable
        )

    private fun pepState(
        account: Account,
        pep: Boolean,
        pepPublishOptions: Boolean,
        pepOmemoWhitelisted: Boolean,
    ): String {
        if (!pep) {
            return getString(R.string.server_info_unavailable)
        }
        val axolotlService = account.getAxolotlService()
        return getString(
            when {
                axolotlService != null && axolotlService.isPepBroken() ->
                    R.string.server_info_broken
                pepPublishOptions || pepOmemoWhitelisted -> R.string.server_info_available
                else -> R.string.server_info_partial
            }
        )
    }

    private fun httpUploadState(
        features: uk.xa0.tulkki.xmpp.XmppConnection.Features,
    ): String {
        if (!features.httpUpload(0)) {
            return getString(R.string.server_info_unavailable)
        }
        val maxFileSize = features.getMaxHttpUploadSize()
        return if (maxFileSize > 0) {
            UIHelper.filesizeToString(maxFileSize)
        } else {
            getString(R.string.server_info_available)
        }
    }

    private fun updateDisplayName(displayName: String?) {
        state = state.copy(displayName = displayName)
    }

    // --- the field errors, one per old TextInputLayout ------------------------------------------

    private fun clearFieldError(field: EditAccountField) {
        state =
            when (field) {
                EditAccountField.Jid -> state.copy(jidError = null)
                EditAccountField.Password -> state.copy(passwordError = null)
                EditAccountField.Hostname -> state.copy(hostnameError = null)
                EditAccountField.Port -> state.copy(portError = null)
            }
    }

    private fun removeErrorsOnAllBut(exception: EditAccountField?) {
        state =
            state.copy(
                jidError = if (exception == EditAccountField.Jid) state.jidError else null,
                passwordError =
                    if (exception == EditAccountField.Password) state.passwordError else null,
                hostnameError =
                    if (exception == EditAccountField.Hostname) state.hostnameError else null,
                portError = if (exception == EditAccountField.Port) state.portError else null,
            )
    }

    /**
     * `setError` + `removeErrorsOnAllBut` + `requestFocus`, which the old branches always ran
     * together.
     */
    private fun showError(field: EditAccountField, message: String, focus: Boolean = true) {
        state =
            when (field) {
                EditAccountField.Jid -> state.copy(jidError = message)
                EditAccountField.Password -> state.copy(passwordError = message)
                EditAccountField.Hostname -> state.copy(hostnameError = message)
                EditAccountField.Port -> state.copy(portError = message)
            }
        removeErrorsOnAllBut(field)
        if (focus) {
            state = state.copy(focusField = field, focusNonce = state.focusNonce + 1)
        }
    }

    private fun showDeletePgpDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.unpublish_pgp)
        builder.setMessage(R.string.unpublish_pgp_message)
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.confirm) { _, _ ->
            val account = mAccount ?: throw NullPointerException()
            account.setPgpSignId(0)
            account.unsetPgpSignature()
            DatabaseBackend.get().updateAccount(account)
            xmppConnectionService.sendPresence(account)
            refreshUiReal()
        }
        builder.create().show()
    }

    fun showWipePepDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.clear_other_devices))
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        builder.setMessage(getString(R.string.clear_other_devices_desc))
        builder.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.accept)) { _, _ ->
            ((mAccount ?: throw NullPointerException()).getAxolotlService()
                ?: throw NullPointerException())
                .wipeOtherPepDevices()
        }
        builder.create().show()
    }

    private fun editMamPrefs() {
        val toast = Toast.makeText(this, R.string.fetching_mam_prefs, Toast.LENGTH_LONG)
        this.mFetchingMamPrefsToast = toast
        toast.show()
        xmppConnectionService.fetchMamPreferences(mAccount ?: throw NullPointerException(), this)
    }

    /**
     * The other-devices rows: [OmemoActivity.contactKeyRow] for each of the account's own
     * uncompromised sessions, which is the same loop the old `otherDeviceKeys` container took.
     */
    private fun otherDeviceKeyRows(): List<ContactKeyRowState> {
        val account = mAccount ?: return emptyList()
        val rows = ArrayList<ContactKeyRowState>()
        for (session in (account.getAxolotlService() ?: return emptyList()).findOwnSessions()) {
            if (!session.getTrust().isCompromised()) {
                rows.add(contactKeyRow(session, session.getFingerprint() == messageFingerprint))
            }
        }
        return rows
    }

    override fun onKeyStatusUpdated(
        report: uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.FetchStatus?,
    ) {
        refreshUi()
    }

    // Tulkki: C5-C - `uk.xa0.tulkki.xmpp.services.OnCaptchaRequested`'s parameter is the island's
    // `AccountRef` now, so this override must name it. It is written fully qualified and imported
    // nowhere, which keeps the `ui-reaches-island` ratchet unmoved.
    override fun onCaptchaRequested(
        account: uk.xa0.tulkki.xmpp.refs.AccountRef,
        id: String,
        data: Data,
        captcha: Bitmap,
    ) {
        runOnUiThread {
            mCaptchaDialog?.invoke()
            mCaptchaDialog = null
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                Log.d(Config.LOGTAG, "activity not running when captcha was requested")
                return@runOnUiThread
            }
            mCaptchaDialog =
                showCaptchaDialog(
                    captcha = captcha,
                    onConfirm = { input ->
                        mCaptchaDialog = null
                        data.put("username", account.getUsername())
                        data.put("password", account.getPassword())
                        data.put("ocr", input)
                        data.submit()
                        if (xmppConnectionServiceBound) {
                            xmppConnectionService.sendCreateAccountWithCaptchaPacket(
                                account,
                                id,
                                data,
                            )
                        }
                    },
                    onCancel = {
                        mCaptchaDialog = null
                        if (xmppConnectionService != null) {
                            xmppConnectionService.sendCreateAccountWithCaptchaPacket(
                                account,
                                null,
                                null,
                            )
                        }
                    },
                )
        }
    }

    override fun onShowErrorToast(resId: Int) {
        runOnUiThread {
            Toast.makeText(this@EditAccountActivity, resId, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPreferencesFetched(prefs: Element) {
        runOnUiThread {
            mFetchingMamPrefsToast?.cancel()
            val builder = MaterialAlertDialogBuilder(this@EditAccountActivity)
            builder.setTitle(R.string.server_side_mam_prefs)
            val defaultAttr = prefs.getAttribute("default")
            val defaults = listOf("never", "roster", "always")
            val choice = AtomicInteger(maxOf(0, defaults.indexOfFirst { it == defaultAttr }))
            builder.setSingleChoiceItems(R.array.mam_prefs, choice.get()) { _, which ->
                choice.set(which)
            }
            builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            builder.setPositiveButton(R.string.ok) { _, _ ->
                prefs.setAttribute("default", defaults[choice.get()])
                xmppConnectionService.pushMamPreferences(
                    mAccount ?: throw NullPointerException(),
                    prefs,
                )
            }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                builder.create().show()
            }
        }
    }

    override fun onPreferencesFetchFailed() {
        runOnUiThread {
            mFetchingMamPrefsToast?.cancel()
            Toast.makeText(
                this@EditAccountActivity,
                R.string.unable_to_fetch_mam_prefs,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        refreshUi()
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (grantResults.size > 0) {
            if (PermissionUtils.allGranted(grantResults)) {
                when (requestCode) {
                    REQUEST_IMPORT_BACKUP ->
                        startActivity(Intent(this, ImportBackupActivity::class.java))
                }
            } else {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show()
            }
        }
        if (PermissionUtils.writeGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver()
            }
        }
    }

    private fun allowRelativeTimestamps(): Boolean {
        val p = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        return !p.getBoolean(
            "always_full_timestamps",
            resources.getBoolean(R.bool.always_full_timestamps),
        )
    }

    private fun loadVCardData() {
        val account = mAccount ?: return
        xmppConnectionService.fetchVcard4(account, account.getSelfContact()) { vcard4 ->
            if (vcard4 == null) return@fetchVcard4

            runOnUiThread {
                mIsLoadingVCard = true
                val rows = mutableListOf<VCardRowState>()
                for (el in vcard4.getChildren()) {
                    val type = el.getName()
                    var value: String? = null
                    if (el.findChildEnsureSingle("uri", Namespace.VCARD4) != null) {
                        value = el.findChildContent("uri", Namespace.VCARD4)
                    } else if (el.findChildEnsureSingle("text", Namespace.VCARD4) != null) {
                        value = el.findChildContent("text", Namespace.VCARD4)
                    }

                    if (value != null) {
                        rows.add(VCardRowState(mNextVCardRowId++, type, value))
                    }
                }
                state = state.copy(vcardRows = rows)
                mIsLoadingVCard = false
            }
        }
    }

    /**
     * One dynamic vCard row, appended. The old `addVCardRow` inflated a view into the container; the
     * row is a [VCardRowState] now, and the file's `item_edit_vcard_entry.xml` is deleted.
     */
    private fun addVCardRow(type: String, value: String?) {
        state =
            state.copy(
                vcardRows = state.vcardRows + VCardRowState(mNextVCardRowId++, type, value ?: "")
            )
    }

    private fun applyVCardValue(id: Long, value: String) {
        state =
            state.copy(
                vcardRows =
                    state.vcardRows.map { if (it.id == id) it.copy(value = value) else it }
            )
        if (!mIsLoadingVCard) {
            mVCardModified = true
            refreshUi()
        }
    }

    private fun applyVCardType(id: Long, type: String) {
        state =
            state.copy(
                vcardRows = state.vcardRows.map { if (it.id == id) it.copy(type = type) else it }
            )
        if (!mIsLoadingVCard) {
            mVCardModified = true
            refreshUi()
        }
    }

    private fun removeVCardRow(id: Long) {
        state = state.copy(vcardRows = state.vcardRows.filterNot { it.id == id })
        mVCardModified = true
        refreshUi()
    }

    /** The drag handle's reorder: the row moves one place, exactly the old dragged-view move. */
    private fun moveVCardRow(from: Int, to: Int) {
        val rows = state.vcardRows
        if (from == to || from < 0 || to < 0 || from >= rows.size || to >= rows.size) {
            return
        }
        val mutable = rows.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        state = state.copy(vcardRows = mutable)
        mVCardModified = true
        refreshUi()
    }

    private fun saveVCardData() {
        if (mAccount == null) return
        val account = mAccount ?: return

        // XEP-0292: The inner element is <vcard xmlns='urn:ietf:params:xml:ns:vcard-4.0'>
        val vcardElement = Element("vcard")
        vcardElement.setAttribute("xmlns", Namespace.VCARD4)

        for (row in state.vcardRows) {
            val type = row.type
            var value = row.value.trim { it <= ' ' }

            if (value.isEmpty()) continue

            val item = Element(type)

            // Strict XEP-0292 mapping
            if (type == "url" ||
                type == "impp" ||
                type == "geo" ||
                type == "photo" ||
                type == "logo" ||
                type == "sound"
            ) {
                val uri = Element("uri")
                uri.setContent(value)
                item.addChild(uri)
            } else if (type == "tel") {
                val uri = Element("uri")
                if (!value.startsWith("tel:")) value = "tel:" + value
                uri.setContent(value)
                item.addChild(uri)
            } else if (type == "xmpp") {
                val uri = Element("uri")
                if (!value.startsWith("xmpp:")) value = "xmpp:" + value
                uri.setContent(value)
                item.addChild(uri)
            } else if (type == "mailto") {
                val uri = Element("mailto")
                if (!value.startsWith("mailto:")) value = "mailto:" + value
                uri.setContent(value)
                item.addChild(uri)
            } else if (type == "taler") {
                val uri = Element("taler")
                if (!value.startsWith("taler:")) value = "taler:" + value
                uri.setContent(value)
                item.addChild(uri)
            } else if (type == "fn") {
                // Specific handling for Formatted Name (FN)
                val text = Element("text")
                text.setContent(value)
                item.addChild(text)
            } else {
                // other, nickname, note, org, title, role, etc use <text>
                val text = Element("text")
                text.setContent(value)
                item.addChild(text)
            }

            vcardElement.addChild(item)
        }

        mVCardModified = false
        refreshUi()

        xmppConnectionService.publishVCard4(account, vcardElement)
    }

    private fun generateVCardString(): String? {
        val account = mAccount ?: throw NullPointerException()
        if (state.vcardRows.isEmpty() && account.getDisplayName() == null) return null

        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\n")
        sb.append("VERSION:4.0\n")

        for (row in state.vcardRows) {
            val type = row.type
            val value = row.value.trim { it <= ' ' }

            if (value.isEmpty()) continue

            // Simple mapping from internal types to VCard properties
            when (type) {
                "fn" -> sb.append("FN:").append(value).append("\n")
                "tel" ->
                    sb.append("TEL;VALUE=uri:")
                        .append(if (value.startsWith("tel:")) value else "tel:" + value)
                        .append("\n")
                "mailto" ->
                    sb.append("EMAIL:")
                        .append(if (value.startsWith("mailto:")) value.substring(7) else value)
                        .append("\n")
                "xmpp" ->
                    sb.append("IMPP:")
                        .append(if (value.startsWith("xmpp:")) value else "xmpp:" + value)
                        .append("\n")
                "url" -> sb.append("URL:").append(value).append("\n")
                "nickname" -> sb.append("NICKNAME:").append(value).append("\n")
                "note" -> sb.append("NOTE:").append(value).append("\n")
                "org" -> sb.append("ORG:").append(value).append("\n")
                "title" -> sb.append("TITLE:").append(value).append("\n")
                "role" -> sb.append("ROLE:").append(value).append("\n")
                "bday" -> sb.append("BDAY:").append(value).append("\n")
                // Add other cases as needed based on VCARD_TYPES
                else ->
                    // Fallback for generic text
                    sb.append("X-").append(type.uppercase()).append(":").append(value).append("\n")
            }
        }

        sb.append("END:VCARD")
        return sb.toString()
    }

    private fun showQrCodeDialog(content: String) {
        try {
            // Calculate dimensions
            val width = (resources.displayMetrics.widthPixels * 0.8).toInt()

            // Generate QR Bitmap
            val writer = com.google.zxing.MultiFormatWriter()
            val result =
                writer.encode(
                    content,
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    width,
                    width,
                )

            // Convert BitMatrix to Bitmap
            val w = result.getWidth()
            val h = result.getHeight()
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] =
                        if (result.get(x, y)) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.WHITE
                        }
                }
            }
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    w,
                    h,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            // Show in Dialog
            val imageView = android.widget.ImageView(this)
            imageView.setImageBitmap(bitmap)
            val padding = (resources.displayMetrics.density * 32).toInt()
            imageView.setPadding(padding, padding, padding, padding)

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editaccount_vcard_qr_title)
                .setView(imageView)
                .setPositiveButton(R.string.ok, null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.editaccount_vcard_qr_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_OPENED_FROM_NOTIFICATION = "opened_from_notification"
        const val EXTRA_FORCE_REGISTER = "force_register"

        private const val REQUEST_IMPORT_BACKUP = 0x63fb
        private const val REQUEST_DATA_SAVER = 0xf244
        private const val REQUEST_CHANGE_STATUS = 0xee11
        private const val REQUEST_ORBOT = 0xff22
        private const val REQUEST_UNLOCK = 0xff23

        private const val QUIET_HOURS_START = "quiet_hours_start:"
        private const val QUIET_HOURS_END = "quiet_hours_end:"

        private val AT_PATTERN = Pattern.compile("@")
        private val E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$")

        // Supported VCard4 fields based on what ContactDetailsActivity supports
        private val VCARD_TYPES =
            arrayOf(
                "fn",
                "xmpp",
                "org",
                "title",
                "role",
                "url",
                "note",
                "tel",
                "email",
                "taler",
                "other",
            )
    }
}

/**
 * `Presence.Status` and the dialog's own four choices, which cannot name each other's type: the
 * dialog is a Compose file and the enum is the model's.
 */
private fun Presence.Status?.toChoice(): PresenceStatusChoice =
    when (this) {
        Presence.Status.DND -> PresenceStatusChoice.DND
        Presence.Status.XA -> PresenceStatusChoice.XA
        Presence.Status.AWAY -> PresenceStatusChoice.AWAY
        else -> PresenceStatusChoice.ONLINE
    }

private fun PresenceStatusChoice.toStatus(): Presence.Status =
    when (this) {
        PresenceStatusChoice.DND -> Presence.Status.DND
        PresenceStatusChoice.XA -> Presence.Status.XA
        PresenceStatusChoice.AWAY -> Presence.Status.AWAY
        PresenceStatusChoice.ONLINE -> Presence.Status.ONLINE
    }
