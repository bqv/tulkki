package uk.xa0.tulkki.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.base.Strings
import java.util.HashMap
import java.util.concurrent.atomic.AtomicReference
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.channel.ChannelDiscoveryScreen
import uk.xa0.tulkki.ui.channel.ChannelRow
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.AccountUtils

/**
 * The channel directory: one search against this server or the Jabber network, the results, and the
 * tap that joins one.
 *
 * <p>**The layout is gone.** `activity_channel_discovery.xml` held a toolbar, a `ProgressBar` and a
 * `RecyclerView`, and `item_channel_discovery.xml` held one result; both files, the menu that
 * inflated the search action view (`channel_discovery_activity.xml`), the context menu
 * (`channel_item_context.xml`) and their adapter
 * (`uk.xa0.tulkki.ui.adapter.ChannelSearchResultAdapter`) are deleted. The bar is the shared chrome
 * ([TulkkiChrome]) now: `setSupportActionBar`, `configureActionBar` and
 * `Activities.setStatusAndNavigationBarColors` went with it, the XML menu's two live items are the
 * chrome's overflow, and the action view is the screen's own search field. The body is
 * [ChannelDiscoveryScreen], which draws the rows in a `LazyColumn`.
 *
 * <p>**The search has no action view any more.** `actionview_search.xml` is deleted with the
 * other four screens that shared it, and this one never counted: the old
 * `mMenuSearchView`/`mSearchEditText` pair is [searchOpen] and [searchQuery], and the restore path
 * (`onSaveInstanceState`'s `"search"`) lands in them rather than in a `PendingItem`. The two
 * `onMenuItemActionExpand`/`Collapse` callbacks are [applySearchOpen] and [applySearchClose]; the
 * IME hook is [applySearchSubmit]. All of this is the same three calls the old callbacks made:
 * `toggleLoadingScreen`, `SoftKeyboardUtils.hideSoftKeyboard` (the field's own IME action hides it
 * now) and `discoverChannels`.
 *
 * <p>**State that used to be a view.** `binding.progressBar.visibility` is [loading] and
 * `binding.list`'s background is [noResults], both read by the screen. The spinner starts visible,
 * as the XML `ProgressBar` did. The adapter's `submitList` is [rows], a list of [ChannelRow]; the
 * activity maps each [Room] to its own row and hands it its own avatar, which is the only thing a
 * screenshot cell cannot build.
 *
 * <p>The dialogs are unchanged and stay view-built: the opt-in notice is the same
 * `MaterialAlertDialogBuilder` with the same clickable `Html` message, and the account chooser is
 * the same single-choice list, because neither inflated a layout of ours.
 */
class ChannelDiscoveryActivity : XmppActivity(), UiHost.ChannelSearchHook {

    // The four pieces of screen state the XML views held; this class owns them now.
    private var rows by mutableStateOf<List<ChannelRow>>(emptyList())

    // The XML `ProgressBar` had no `visibility`, so it was visible until the first answer.
    private var loading by mutableStateOf(true)
    private var noResults by mutableStateOf(false)
    private var searchOpen by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")

    /**
     * The search field's collapse: while it is open the system back button closes it instead of the
     * screen, which is what the framework's action view did for the menu item.
     */
    private val mSearchBackCallback =
        object : OnBackPressedCallback(false) {
            public override fun handleOnBackPressed() {
                applySearchClose()
            }
        }

    private var pendingServices: Array<String>? = null
    private var method: UiHost.ChannelMethod = UiHost.ChannelMethod.LOCAL_SERVER
    private var mucServices: HashMap<String, Account>? = null

    private var optedIn = false

    protected override fun refreshUiReal() {}

    protected override fun onBackendConnected() {
        val pending = pendingServices
        if (pending != null) {
            val services = HashMap<String, Account>()
            for (i in pending.indices step 2) {
                services[pending[i]] =
                    AccountRegistry.get().findAccountByJid(Jid.of(pending[i + 1]))
                        ?: throw NullPointerException()
            }
            mucServices = services
        }

        this.method = getMethod(this)

        if (optedIn || method == UiHost.ChannelMethod.LOCAL_SERVER) {
            // The old code read the action view's text while it was expanded and the pending
            // restored search otherwise; the field is that state now.
            val query = if (searchOpen) searchQuery else null
            toggleLoadingScreen()
            UiHost.installed().discoverChannels(
                xmppConnectionService,
                query,
                this.method,
                this.mucServices,
                this,
            )
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.optedIn = getPreferences().getBoolean(CHANNEL_DISCOVERY_OPT_IN, false)

        val search = savedInstanceState?.getString("search")
        if (search != null) {
            searchOpen = true
            searchQuery = search
        }

        pendingServices = getIntent().getStringArrayExtra("services")

        // The old action view collapsed on back; the chrome has no action view, so the dispatcher
        // does it. Registered before the composition so the state it toggles is never stale.
        mSearchBackCallback.isEnabled = searchOpen
        onBackPressedDispatcher.addCallback(this, mSearchBackCallback)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which
        // is what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.discover_channels),
                onUp = { finish() },
                // The XML menu's two live items: `action_accounts` and `action_settings` went to
                // `XmppActivity.onOptionsItemSelected`, and `showHideMenuItems` showed the first
                // only when the platform has a manage-account screen.
                menu =
                    listOfNotNull(
                        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
                            ChromeMenuItem(stringResource(R.string.action_accounts)) {
                                AccountUtils.launchManageAccounts(this)
                            }
                        } else {
                            null
                        },
                        ChromeMenuItem(stringResource(R.string.action_settings)) {
                            startActivity(
                                Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)
                            )
                        },
                    ),
                actions = {
                    if (!searchOpen) {
                        IconButton(onClick = { applySearchOpen() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search_24dp),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    }
                },
            ) {
                ChannelDiscoveryScreen(
                    rows = rows,
                    loading = loading,
                    noResults = noResults,
                    searchOpen = searchOpen,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearchClose = { applySearchClose() },
                    onSearchSubmit = { applySearchSubmit() },
                    onChannelClick = { onChannelSearchResult(it) },
                    onShare = { shareChannel(it) },
                    onOpenJoinDialog = { openJoinDialog(it) },
                )
            }
        }
    }

    private fun getMethod(c: Context): UiHost.ChannelMethod {
        if (this.mucServices != null) return UiHost.ChannelMethod.LOCAL_SERVER
        if (Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY)) {
            return UiHost.ChannelMethod.LOCAL_SERVER
        }
        if (UiHost.installed().quicksy()) {
            return UiHost.ChannelMethod.JABBER_NETWORK
        }
        val p = PreferenceManager.getDefaultSharedPreferences(c)
        val m = p.getString(
            "channel_discovery_method",
            c.getString(R.string.default_channel_discovery),
        )
        return try {
            UiHost.ChannelMethod.valueOf(m ?: throw NullPointerException())
        } catch (e: IllegalArgumentException) {
            UiHost.ChannelMethod.JABBER_NETWORK
        }
    }

    /** The chrome's search icon: the old `action_search` action view, expanded. */
    private fun applySearchOpen() {
        searchOpen = true
        mSearchBackCallback.isEnabled = true
    }

    /** The old `onMenuItemActionCollapse`: the field goes, its text clears and the plain search runs. */
    private fun applySearchClose() {
        searchOpen = false
        searchQuery = ""
        mSearchBackCallback.isEnabled = false
        toggleLoadingScreen()
        if (optedIn || method == UiHost.ChannelMethod.LOCAL_SERVER) {
            UiHost.installed().discoverChannels(
                xmppConnectionService,
                null,
                this.method,
                this.mucServices,
                this,
            )
        }
    }

    /** The IME's search action, the old `OnEditorActionListener` hook. */
    private fun applySearchSubmit() {
        if (optedIn || method == UiHost.ChannelMethod.LOCAL_SERVER) {
            toggleLoadingScreen()
            UiHost.installed().discoverChannels(
                xmppConnectionService,
                searchQuery,
                this.method,
                this.mucServices,
                this,
            )
        }
    }

    private fun toggleLoadingScreen() {
        rows = emptyList()
        loading = true
        noResults = false
    }

    public override fun onStart() {
        super.onStart()
        this.method = getMethod(this)
        if (pendingServices == null && !optedIn &&
            method == UiHost.ChannelMethod.JABBER_NETWORK
        ) {
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.channel_discovery_opt_in_title)
            builder.setMessage(Html.fromHtml(getString(R.string.channel_discover_opt_in_message)))
            builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel) { _, _ -> finish() }
            builder.setPositiveButton(R.string.confirm) { _, _ -> optIn() }
            builder.setOnCancelListener { finish() }
            val dialog = builder.create()
            dialog.setOnShowListener {
                val textView = dialog.findViewById<TextView>(android.R.id.message)
                if (textView == null) {
                    return@setOnShowListener
                }
                textView.movementMethod = LinkMovementMethod.getInstance()
            }
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            holdLoading()
        }
    }

    private fun holdLoading() {
        rows = emptyList()
        loading = false
        noResults = false
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        if (searchOpen) {
            savedInstanceState.putString("search", searchQuery)
        }
        super.onSaveInstanceState(savedInstanceState)
    }

    private fun optIn() {
        val preferences = getPreferences()
        preferences.edit().putBoolean(CHANNEL_DISCOVERY_OPT_IN, true).apply()
        optedIn = true
        toggleLoadingScreen()
        UiHost.installed().discoverChannels(
            xmppConnectionService,
            null,
            this.method,
            this.mucServices,
            this,
        )
    }

    public override fun onChannelSearchResultsFound(results: List<Room>) {
        runOnUiThread {
            rows = results.map { ChannelRow(it, it) }
            loading = false
            noResults = results.isEmpty()
        }
    }

    private fun onChannelSearchResult(result: Room) {
        val accounts = AccountUtils.getEnabledAccounts(AccountRegistry.get().getAccounts())
        if (accounts.size == 1) {
            joinChannelSearchResult(accounts[0], result)
        } else if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.please_enable_an_account, Toast.LENGTH_LONG).show()
        } else {
            val account = AtomicReference(accounts[0])
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.choose_account)
            builder.setSingleChoiceItems(accounts.toTypedArray(), 0) { _, which ->
                account.set(accounts[which])
            }
            builder.setPositiveButton(R.string.join) { _, _ ->
                joinChannelSearchResult(account.get(), result)
            }
            builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            builder.create().show()
        }
    }

    /** The context menu's `share_with`. */
    private fun shareChannel(room: Room) {
        StartConversationActivity.shareAsChannel(this, room.address ?: throw NullPointerException())
    }

    /** The context menu's `open_join_dialog`. */
    private fun openJoinDialog(room: Room) {
        val intent = Intent(this, StartConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.putExtra("force_dialog", true)
        intent.data = Uri.parse("xmpp:${room.address}?join")
        startActivity(intent)
    }

    public fun joinChannelSearchResult(selectedAccount: String, result: Room) {
        val jid = Jid.of(selectedAccount)
        val account = AccountRegistry.get().findAccountByJid(jid)
        val conversation = xmppConnectionService.findOrCreateConversation(
            account ?: throw NullPointerException(),
            result.getRoom() ?: throw NullPointerException(),
            true,
            true,
            true,
        ) as Conversation
        xmppConnectionService.ensureBookmarkIsAutoJoin(conversation)
        switchToConversation(conversation)
    }

    companion object {
        private const val CHANNEL_DISCOVERY_OPT_IN = "channel_discovery_opt_in"
    }
}
