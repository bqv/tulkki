package uk.xa0.tulkki.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.SecureRandom
import java.util.Comparator
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.utils.InstallReferrerUtils
import uk.xa0.tulkki.ui.welcome.MagicCreateField
import uk.xa0.tulkki.ui.welcome.MagicCreateScreen
import uk.xa0.tulkki.ui.welcome.MagicCreateState
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * The create-account form behind the welcome screen's "I need to sign up": a username, an XMPP
 * provider (or the owner's own server), the full address being built, and the account that is made
 * when they are accepted.
 *
 * <p>The screen is [MagicCreateScreen], composed straight into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome]; `activity_magic_create.xml` is deleted with the swap. The
 * data-binding fields the class used to poke - the spinner's adapter, the two error labels, the
 * `View.GONE`s - are Compose state now, and the class reads and writes them exactly as it did the
 * views: [updateFullJidInformation] is the same derivation of the domain in force, and
 * [reportSignupProblem] the same two-field blame.
 *
 * <p>What is gone is only the machinery: the `TextWatcher`, the `OnItemSelectedListener` and the
 * `OnCheckedChangeListener` this class implemented are the callbacks [MagicCreateScreen] emits.
 */
class MagicCreateActivity : XmppActivity() {

    private var useOwnProvider = false
    private var registerFromUri = false

    private var domain: String? = null

    /** The invitation's fixed username, when the intent carried one. */
    private var username: String? = null
    private var preAuth: String? = null

    private var instructions by mutableStateOf("")
    private var titleRes by mutableStateOf(R.string.pick_your_username)
    private var usernameText by mutableStateOf("")
    private var usernameEnabled by mutableStateOf(true)
    private var usernameError by mutableStateOf<String?>(null)
    private var providers by mutableStateOf<List<String>>(emptyList())
    private var selectedProvider by mutableStateOf<String?>(null)
    private var serverEnabled by mutableStateOf(true)
    private var serverVisible by mutableStateOf(true)
    private var providersLabel by mutableStateOf(R.string.error_loading_chat_providers)
    private var providersLabelVisible by mutableStateOf(true)
    private var serverTitle by mutableStateOf(R.string.choose_your_server)
    private var serverTitleVisible by mutableStateOf(true)
    private var fixedServer by mutableStateOf<String?>(null)
    private var fixedServerVisible by mutableStateOf(false)
    private var useOwnVisible by mutableStateOf(true)
    private var useOwn by mutableStateOf(false)
    private var ownServerVisible by mutableStateOf(false)
    private var ownServerText by mutableStateOf("")
    private var ownServerError by mutableStateOf<String?>(null)
    private var fullJid by mutableStateOf<String?>(null)
    private var fullJidVisible by mutableStateOf(false)
    private var focus by mutableStateOf<MagicCreateField?>(null)

    protected override fun refreshUiReal() {}

    protected override fun onBackendConnected() {}

    protected override fun onCreate(savedInstanceState: Bundle?) {
        val data: Intent? = getIntent()
        this.domain = data?.getStringExtra(EXTRA_DOMAIN)
        this.preAuth = data?.getStringExtra(EXTRA_PRE_AUTH)
        this.username = data?.getStringExtra(EXTRA_USERNAME)
        this.registerFromUri = data != null && data.getBooleanExtra(EXTRA_REGISTER, false)
        if (resources.getBoolean(R.bool.portrait_only)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The XML toolbar showed its arrow iff the intent carried no domain, and it never
        // re-evaluated that as `this.domain` moved; so this is read once, here.
        val upVisible = this.domain == null
        instructions = getString(R.string.magic_create_text)
        val loadExternalList =
            getBooleanPreference(
                AppSettings.LOAD_PROVIDERS_EXTERNAL,
                R.bool.load_providers_list_external,
            )
        if (!loadExternalList) {
            providersLabel = R.string.local_providers_list
        }

        // Ask for the current provider list first and read what came back: the catalogue is the
        // answer to the fetch, and an empty one is a fact the owner has to act on.
        var refreshed = false
        try {
            refreshed = UiHost.installed().refreshProviders()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        val domains = UiHost.installed().providers().toMutableList()
        domains.sortWith(Comparator { a: String, b: String -> a.compareTo(b, ignoreCase = true) })
        if (refreshed && loadExternalList &&
            XmppActivity.staticXmppConnectionService.hasInternetConnection()
        ) {
            providersLabel = R.string.external_providers_list
        }
        val randomProvider = UiHost.installed().randomProviderDomain()
        val defaultServer = if (randomProvider == null) -1 else domains.indexOf(randomProvider)
        providers = domains
        // A Spinner with no explicit selection stands on its first item; the layout selected the
        // random provider when there was one.
        selectedProvider = domains.getOrNull(defaultServer) ?: domains.firstOrNull()
        if (registerFromUri && !useOwnProvider && (this.preAuth != null || domain != null)) {
            serverEnabled = false
            serverVisible = false
            useOwn = true
            useOwnVisible = false
            serverTitle = R.string.your_server
            serverTitleVisible = true
            fixedServer = this.domain
            fixedServerVisible = true
        } else {
            fixedServerVisible = false
        }
        if (domains.isEmpty()) {
            // Nothing came back: say the list is unavailable and open the field the owner types
            // their own server into, rather than substituting a domain for them.
            providersLabel = R.string.provider_list_unavailable
            // The layout set `useOwn` here with its listener already attached, so this branch ran
            // the switch's own side effects; the invitation branch above set it with none.
            if (!useOwn) {
                applyUseOwn(true)
            }
        }
        if (username != null && domain != null) {
            titleRes = R.string.your_server_invitation
            instructions = getString(R.string.magic_create_text_fixed, domain)
            usernameEnabled = false
            usernameText = username!!
            providers = listOf(domain!!)
            selectedProvider = domain
            serverTitleVisible = false
            serverVisible = false
            useOwnVisible = false
            providersLabelVisible = false
            updateFullJidInformation(username!!)
        } else if (domain != null) {
            instructions = getString(R.string.magic_create_text_on_x, domain)
            providers = listOf(domain!!)
            selectedProvider = domain
            serverTitleVisible = false
            serverVisible = false
            useOwnVisible = false
            providersLabelVisible = false
        }
        // The Spinner's own selection listener ran once the view was laid out, deriving the domain
        // and the preview from whatever stood selected; this is that reading, once, up front.
        updateFullJidInformation(usernameText)
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // `@string/get_jabber_id`, from the manifest, as the XML toolbar's own label was.
                title = getTitle().toString(),
                onUp = if (upVisible) ({ finish() }) else null,
            ) {
                MagicCreateScreen(
                    state =
                        MagicCreateState(
                            instructions = instructions,
                            title = titleRes,
                            username = usernameText,
                            usernameEnabled = usernameEnabled,
                            usernameError = usernameError,
                            providers = providers,
                            selectedProvider = selectedProvider,
                            serverEnabled = serverEnabled,
                            serverVisible = serverVisible,
                            providersLabel = providersLabel,
                            providersLabelVisible = providersLabelVisible,
                            serverTitle = serverTitle,
                            serverTitleVisible = serverTitleVisible,
                            fixedServer = fixedServer,
                            fixedServerVisible = fixedServerVisible,
                            useOwnVisible = useOwnVisible,
                            useOwn = useOwn,
                            ownServerVisible = ownServerVisible,
                            ownServer = ownServerText,
                            ownServerError = ownServerError,
                            fullJid = fullJid,
                            fullJidVisible = fullJidVisible,
                            focus = focus,
                        ),
                    onUsernameChange = { text ->
                        usernameText = text
                        updateFullJidInformation(text)
                    },
                    onOwnServerChange = { text ->
                        ownServerText = text
                        updateFullJidInformation(usernameText)
                    },
                    onProviderSelected = { provider ->
                        selectedProvider = provider
                        updateFullJidInformation(usernameText)
                    },
                    onUseOwnChange = { checked -> applyUseOwn(checked) },
                    onCreate = { createAccount() },
                )
            }
        }
    }

    /**
     * Puts a create-account problem on the field it belongs to. A server that is missing or cannot
     * be a domain is reported on the server field, not as the bad username it used to be.
     */
    private fun reportSignupProblem(problem: SignupIdentity.Problem) {
        if (problem == SignupIdentity.Problem.SERVER) {
            ownServerError = getString(R.string.enter_domain)
            focus = MagicCreateField.OWN_SERVER
        } else {
            usernameError = getString(R.string.invalid_username)
            focus = MagicCreateField.USERNAME
        }
    }

    private fun updateDomain(): String? {
        // Tulkki: there is no built-in default domain any more - Config.MAGIC_CREATE_DOMAIN is
        // deleted - so the chosen provider or the owner's own server is the only source of one.
        if (useOwnProvider) {
            return ownServerText
        }
        return this.domain
    }

    /** The switch's own side effects, which the listener used to run on `onCheckedChanged`. */
    private fun applyUseOwn(checked: Boolean) {
        useOwn = checked
        if (checked) {
            serverEnabled = false
            fullJidVisible = false
            useOwnProvider = true
            ownServerVisible = true
        } else {
            serverEnabled = true
            fullJidVisible = true
            useOwnProvider = false
            ownServerVisible = false
        }
        registerFromUri = false
        updateFullJidInformation(usernameText)
    }

    /**
     * The domain in force and the address preview, exactly as the two text watchers and the
     * spinner's selection listener derived them: the owner's own server wins when they asked for it,
     * otherwise the selected provider does.
     */
    private fun updateFullJidInformation(username: String) {
        val selected = selectedProvider
        if (useOwnProvider && !registerFromUri) {
            this.domain = updateDomain()
        } else if (!registerFromUri && selected != null && !selected.isEmpty()) {
            this.domain = selected
        }
        if (username.trim { it <= ' ' }.isEmpty()) {
            fullJidVisible = false
        } else if (this.domain == null) {
            // Tulkki: no provider is selected and the default domain this used to preview against
            // (Config.MAGIC_CREATE_DOMAIN) is deleted, so there is no jid to show.
            fullJidVisible = false
        } else {
            try {
                val jid = Jid.ofLocalAndDomain(username, this.domain!!)
                fullJidVisible = true
                fullJid = getString(R.string.your_full_jid_will_be, jid.toString())
            } catch (e: IllegalArgumentException) {
                fullJidVisible = false
            }
        }
    }

    private fun createAccount() {
        try {
            val enteredUsername = usernameText
            val fixedUsername: Boolean
            val jid: Jid
            val fieldDomain = this.domain
            val fieldUsername = this.username
            if (fieldDomain != null && fieldUsername != null) {
                fixedUsername = true
                jid = Jid.ofLocalAndDomain(fieldUsername, fieldDomain)
            } else if (fieldDomain != null) {
                fixedUsername = false
                jid = Jid.ofLocalAndDomain(enteredUsername, fieldDomain)
            } else {
                fixedUsername = false
                val updatedDomain = updateDomain()
                this.domain = updatedDomain
                jid =
                    Jid.ofLocalAndDomain(
                        enteredUsername,
                        updatedDomain ?: throw NullPointerException(),
                    )
            }
            val problem = SignupIdentity.problem(jid, enteredUsername, this.domain)
            if (problem != SignupIdentity.Problem.NONE) {
                reportSignupProblem(problem)
            } else {
                usernameError = null
                ownServerError = null
                val existing = AccountRegistry.get().findAccountByJid(jid)
                val password = CryptoHelper.createPassword(SecureRandom())
                val account: Account
                if (existing == null) {
                    account = Account(jid, password)
                    account.setOption(Account.OPTION_REGISTER, true)
                    account.setOption(Account.OPTION_DISABLED, true)
                    account.setOption(Account.OPTION_MAGIC_CREATE, true)
                    account.setOption(Account.OPTION_FIXED_USERNAME, fixedUsername)
                    val preAuth = this.preAuth
                    if (preAuth != null) {
                        account.setKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN, preAuth)
                    }
                    xmppConnectionService.createAccount(account)
                } else {
                    account = existing
                }
                val intent = Intent(this, EditAccountActivity::class.java)
                intent.putExtra("jid", account.getJid().asBareJid().toString())
                intent.putExtra("init", true)
                intent.putExtra("existing", false)
                intent.putExtra("useownprovider", useOwnProvider)
                intent.putExtra("register", registerFromUri)
                intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
                val builder = MaterialAlertDialogBuilder(this)
                builder.setTitle(getString(R.string.create_account))
                builder.setCancelable(false)
                val message = StringBuilder()
                message.append(getString(R.string.secure_password_generated))
                message.append("\n\n")
                message.append(getString(R.string.password))
                message.append(": ")
                message.append(password)
                message.append("\n\n")
                message.append(getString(R.string.change_password_in_next_step))
                builder.setMessage(message)
                builder.setPositiveButton(getString(R.string.copy_to_clipboard)) { _, _ ->
                    if (copyTextToClipboard(password, R.string.create_account)) {
                        StartConversationActivity.addInviteUri(intent, getIntent())
                        startActivity(intent)
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
                        finish()
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
                    }
                }
                builder.create().show()
            }
        } catch (e: IllegalArgumentException) {
            // The jid was never built, so there is no local part to check: say which of the two
            // fields it failed on. An empty "own provider" field used to read as a bad username.
            reportSignupProblem(
                SignupIdentity.problem(
                    null,
                    usernameText,
                    this.domain,
                ),
            )
        }
    }

    public override fun onDestroy() {
        InstallReferrerUtils.markInstallReferrerExecuted(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PRE_AUTH = "pre_auth"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_REGISTER = "register"
    }
}
