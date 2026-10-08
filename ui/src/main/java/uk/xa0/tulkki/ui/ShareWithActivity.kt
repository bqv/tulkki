package uk.xa0.tulkki.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutManagerCompat
import com.google.common.base.Splitter
import com.google.common.base.Strings
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.conversationlist.ConversationAction
import uk.xa0.tulkki.ui.conversationlist.ConversationFilter
import uk.xa0.tulkki.ui.conversationlist.ConversationListEvents
import uk.xa0.tulkki.ui.conversationlist.ConversationListHost
import uk.xa0.tulkki.ui.conversationlist.ConversationListRead
import uk.xa0.tulkki.ui.conversationlist.ConversationListScreen
import uk.xa0.tulkki.ui.conversationlist.ConversationListState
import uk.xa0.tulkki.ui.conversationlist.ConversationOrder
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.projection.ConversationProjection
import uk.xa0.tulkki.ui.projection.PreviewWords
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The share sheet's conversation picker: the <em>host</em>. It reads, and
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListScreen] draws.
 *
 * It is the second host of that screen, and the reason the read stopped being one host's private
 * business: both screens ask `:data` the same two questions, so both call
 * [ConversationListRead]. What is this screen's own is the *row set* - the service's capability
 * answer for this share, taken through `populateWithOrderedConversationList` with `includeNoFileUpload`
 * set from whether files ride along - and its one effect.
 *
 * **It is a picker, so its session turns two gestures off.** `setMenuEnabled(false)` because the
 * twelve entries the list's long press offers are effects on a conversation, and picking a conversation to
 * share into is not one of them; `setSwipeEnabled(false)` because a row has nothing to archive here. A tap
 * is the whole vocabulary: `onOpen` is the item click this screen always had, and it calls [share]. The row
 * itself is the list's own row, which is a deliberate simplification of what the old adapter drew in this
 * screen - the avatar, the presence dot, the timestamp and the account line are gone with it, and the
 * projection's row is what every list screen now shows.
 *
 * **The layout and the menu are gone.** `activity_share_with.xml` held a toolbar and the `ComposeView`
 * this class bound by id, and `menu/share_with.xml` held the toolbar's one item; the bar is the shared
 * chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, the item is in its overflow, and both files
 * were deleted. The chrome carries no up affordance here, which is what the XML bar drew too.
 */
class ShareWithActivity : XmppActivity(), uk.xa0.tulkki.xmpp.services.OnConversationUpdate {

    private class Share {
        var type: String? = null
        var uris: ArrayList<Uri> = ArrayList()
        var account: String? = null
        var contact: String? = null
        var text: String? = null
        var asQuote: Boolean = false
    }

    private var share: Share? = null
    private var mPendingConversation: Conversation? = null

    /** The rows this share may be pointed at: the service's own capability filter, in its own order. */
    private val conversationList: MutableList<Conversation> = ArrayList()

    /** The read is a database query; it must not run on the thread that draws the screen. */
    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The last read, in the shape the screen's state is assembled from. */
    private var snapshots: List<ConversationSnapshot> = ArrayList()
    private val lastMessages: MutableMap<String, MessageSnapshot> = HashMap()

    /** Bumped on every read, so a query that lands late cannot overwrite a newer one. */
    private var readGeneration = 0

    /** Whether a read landed at all: no rows is §3.5's skeleton, an empty list is its explainer. */
    private var readLanded = false

    private lateinit var settings: TranslationSettings

    /** The one Compose state the screen reads; the host updates it after each read. */
    private var session: ConversationListHost.Session? = null

    /** Which of §3.5's three filters is on. The screen draws the control; the host holds the value. */
    private var filter: ConversationFilter = ConversationFilter.ALL

    /** The projection's `Context::getString`, narrowed the way `PreviewWords` asks for it. */
    private val words: PreviewWords =
        PreviewWords { id, args -> if (args.isEmpty()) getString(id) else getString(id, *args) }

    private val events: ConversationListEvents = Picker()

    override fun onConversationUpdate() {
        refreshUi()
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val share = this.share
        if (requestCode == REQUEST_START_NEW_CONVERSATION && resultCode == RESULT_OK) {
            // The Java dereferenced both the field and the result data here; a null was its NPE.
            val current = share ?: throw NullPointerException()
            val result = data ?: throw NullPointerException()
            current.contact = result.getStringExtra("contact")
            current.account = result.getStringExtra(XmppActivity.EXTRA_ACCOUNT)
        }
        if (xmppConnectionServiceBound &&
            share != null &&
            share.contact != null &&
            share.account != null
        ) {
            share()
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_STORAGE_PERMISSION) {
                    val pending = mPendingConversation
                    if (pending != null) {
                        share(pending)
                    } else {
                        Log.d(Config.LOGTAG, "unable to find stored conversation")
                    }
                }
            } else {
                // The Java dereferenced the text it asked the resource for; a null is its NPE.
                val message = getString(R.string.no_storage_permission, BuildConfig.APP_NAME)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.settings = TranslationSettings.get(applicationContext)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        val session = ConversationListHost.Session()
        this.session = session
        // A picker: the tap is the whole gesture vocabulary. See the class comment.
        session.setMenuEnabled(false)
        session.setSwipeEnabled(false)
        // The avatars, like the list's, are the activity's: the screen asks per row and never loads.
        session.setAvatar(conversationAvatars())

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.title_activity_share_with),
                // No up affordance: a share target has no parent in the manifest and the XML bar
                // drew no arrow either - the system back button is the way out. The chrome carries
                // the slot; this screen deliberately leaves it empty.
                onUp = null,
                // The XML bar's only item, `action_add`, kept reachable in the overflow.
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.start_chat)) {
                            startNewConversation()
                        }
                    ),
            ) {
                ConversationListScreen(
                    state = session.state,
                    events = events,
                    swipeEnabled = session.swipeEnabled,
                    menuEnabled = session.menuEnabled,
                    avatar = session.avatar,
                )
            }
        }

        val intent = getIntent()
        val shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        this.share = Share()
        if (shortcutId != null) {
            val conversation = shortcutIdToConversation(shortcutId)
            if (conversation != null) {
                // we have everything we need. Jump into chat
                populateShare(intent)
                share(conversation)
            }
        }
    }

    public override fun onDestroy() {
        readExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun shortcutIdToConversation(shortcutId: String): String? {
        val shortcut =
            ShortcutManagerCompat.getDynamicShortcuts(this).firstOrNull { it.getId() == shortcutId }
        if (shortcut != null) {
            val extras = shortcut.getExtras()
            if (extras == null) {
                return shortcutIdToConversationFallback(shortcutId)
            } else {
                val conversation = extras.getString(ConversationListActivity.EXTRA_CONVERSATION)
                if (Strings.isNullOrEmpty(conversation)) {
                    return shortcutIdToConversationFallback(shortcutId)
                } else {
                    return conversation
                }
            }
        } else {
            return shortcutIdToConversationFallback(shortcutId)
        }
    }

    private fun shortcutIdToConversationFallback(shortcutId: String): String? {
        val parts =
            Splitter.on(UiHost.installed().shortcutIdSeparator()).limit(2).splitToList(shortcutId)
        if (parts.size == 2) {
            val account = Jid.of(parts[0])
            val jid = Jid.of(parts[1])
            val database = DatabaseBackend.getInstance(applicationContext)
            return database.findConversationUuid(account, jid)
        } else {
            return null
        }
    }

    /** The XML bar's one item, `action_add`: the same intent, started from the chrome's overflow. */
    private fun startNewConversation() {
        val intent = Intent(applicationContext, ChooseContactActivity::class.java)
        intent.putExtra("direct_search", true)
        startActivityForResult(intent, REQUEST_START_NEW_CONVERSATION)
    }

    public override fun onStart() {
        super.onStart()
        val intent = getIntent()
        if (intent == null) {
            return
        }
        populateShare(intent)
        if (xmppConnectionServiceBound) {
            populateConversationList()
            readRows()
        }
    }

    private fun populateShare(intent: Intent) {
        // The Java dereferenced the field before its first read.
        val share = this.share ?: throw NullPointerException()
        val type = intent.getType()
        val action = intent.getAction()
        val data = intent.getData()
        if (Intent.ACTION_SEND == action) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val asQuote = intent.getBooleanExtra(ConversationListActivity.EXTRA_AS_QUOTE, false)

            if (data != null && "geo" == data.getScheme()) {
                share.uris.clear()
                share.uris.add(data)
            } else if (type != null && uri != null) {
                share.uris.clear()
                share.uris.add(uri)
                share.type = type
            } else {
                share.text = text
                share.asQuote = asQuote
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            share.uris = uris ?: ArrayList()
        }
        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (shortcutId != null) {
            val index = shortcutId.indexOf('#')
            if (index >= 0) {
                share.account = shortcutId.substring(0, index)
                share.contact = shortcutId.substring(index + 1)
            }
        }
        if (xmppConnectionServiceBound) {
            populateConversationList()
            readRows()
        }
    }

    /**
     * What the service offers this share: a text share may be pointed anywhere, and one carrying files
     * only at a single chat or a room that can upload them. The list is left in the order the service
     * already has it, which is what the tree asked for with `sort = false`.
     */
    private fun populateConversationList() {
        val share = this.share
        @Suppress("UNCHECKED_CAST")
        xmppConnectionService.populateWithOrderedConversationList(
            conversationList as MutableList<uk.xa0.tulkki.xmpp.refs.ConversationRef>,
            share != null && share.uris.isEmpty(),
            false,
        )
    }

    protected override fun onBackendConnected() {
        val current = this.share
        if (xmppConnectionServiceBound &&
            current != null &&
            current.contact != null &&
            current.account != null
        ) {
            share()
            return
        }
        refreshUiReal()
    }

    /** The two rows a share can name directly go straight through; the picker is for the rest. */
    private fun share() {
        // The Java dereferenced the field here; a null was its NPE.
        val share = this.share ?: throw NullPointerException()
        val account =
            try {
                AccountRegistry.get()
                    .findAccountByJid(Jid.of(share.account ?: throw NullPointerException()))
            } catch (e: IllegalArgumentException) {
                null
            }
        if (account == null) {
            return
        }

        val conversation =
            try {
                xmppConnectionService.findOrCreateConversation(
                    account,
                    Jid.of(share.contact ?: throw NullPointerException()),
                    false,
                    true,
                ) as Conversation
            } catch (e: IllegalArgumentException) {
                return
            }
        share(conversation)
    }

    private fun share(conversation: Conversation) {
        val share = this.share ?: throw NullPointerException()
        if (!share.uris.isEmpty() && !hasStoragePermission(REQUEST_STORAGE_PERMISSION)) {
            mPendingConversation = conversation
            return
        }
        share(conversation.getUuid())
    }

    private fun share(conversationUuid: String?) {
        val share = this.share ?: throw NullPointerException()
        val intent = Intent(this, ConversationListActivity::class.java)
        intent.putExtra(ConversationListActivity.EXTRA_CONVERSATION, conversationUuid)
        if (!share.uris.isEmpty()) {
            intent.action = Intent.ACTION_SEND_MULTIPLE
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, share.uris)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (share.type != null) {
                intent.putExtra(ConversationListActivity.EXTRA_TYPE, share.type)
            }
        } else if (share.text != null) {
            intent.action = ConversationListActivity.ACTION_VIEW_CONVERSATION
            intent.putExtra(Intent.EXTRA_TEXT, share.text)
            intent.putExtra(ConversationListActivity.EXTRA_AS_QUOTE, share.asQuote)
        }
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(
                    this,
                    R.string.sharing_application_not_grant_permission,
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }
        finish()
    }

    public override fun refreshUiReal() {
        populateConversationList()
        readRows()
    }

    // -- the read --------------------------------------------------------------------------------

    /**
     * The picker's read: the same two queries `ConversationListFragment` runs, scoped to the rows this
     * share may be pointed at. The membership set is this read's own, because the query runs on another
     * thread and a later refresh must not move the ground under it.
     */
    private fun readRows() {
        // This read's own copy of the row set: the executor below runs on another thread and a later
        // refresh must not move the ground under it, nor under the avatar pass beside it.
        val rows = ArrayList(conversationList)
        val membership = LinkedHashSet<String>()
        val accounts = LinkedHashSet<String>()
        for (conversation in rows) {
            // The model's uuid is nullable; a row without one can never match an allowed id.
            conversation.getUuid()?.let { membership.add(it) }
            val account = conversation.getAccount()
            if (account != null) {
                account.getUuid()?.let { accounts.add(it) }
            }
        }
        val appContext = applicationContext
        val generation = ++readGeneration
        readExecutor.execute {
            warmConversationAvatars(rows)
            val read = ConversationListRead.rows(appContext, membership, accounts)
            runOnUiThread {
                if (generation != readGeneration || isFinishing) {
                    return@runOnUiThread
                }
                snapshots = read.snapshots
                lastMessages.clear()
                lastMessages.putAll(read.lastMessages)
                readLanded = true
                render()
            }
        }
    }

    /** The state the screen draws, from the read that last landed. */
    private fun render() {
        val session = this.session ?: return
        if (!readLanded) {
            session.update(
                ConversationListState(null, filter, ConversationListRead.connection(), false),
            )
            return
        }
        val now = System.currentTimeMillis()
        val kept = ArrayList(snapshots)
        val sortableTimes = HashMap<String, Long>()
        for (snapshot in kept) {
            sortableTimes[snapshot.id] =
                ConversationOrder.sortableTime(
                    snapshot,
                    snapshot.lastMessageId?.let { lastMessages[it] },
                    ConversationProjection.lastClearHistoryAt(snapshot),
                    draftAt(snapshot.id),
                )
        }
        val assembled =
            ConversationListHost.assemble(
                kept,
                lastMessages,
                settings.appLanguage(),
                settings.interpreter(),
                words,
                now,
                conversationListFacts(),
                messageFacts(),
                filter,
                ConversationListRead.connection(),
                false,
                // The tree's `always_full_timestamps`, inverted, exactly as the list reads it.
                !getBooleanPreference("always_full_timestamps", R.bool.always_full_timestamps),
            )
        // `archivedVisible` stays false here for the same reason it does on the list: the service's own
        // list, which is this read's membership, does not carry an archived conversation.
        session.update(
            ConversationListState(
                ConversationOrder.sort(
                    assembled.rows ?: throw NullPointerException(),
                    sortableTimes,
                ),
                filter,
                assembled.connection,
                false,
            ),
        )
    }

    /** The draft's own instant, or `null`: the read names no draft row, and the order wants it. */
    private fun draftAt(uuid: String): Long? {
        val service = xmppConnectionService ?: return null
        val conversation = service.findConversationByUuid(uuid) as Conversation?
        if (conversation == null) {
            return null
        }
        val draft = conversation.getDraft()
        return draft?.getTimestamp()
    }

    /** The picker's vocabulary: one gesture that acts, one predicate, and one nothing can emit. */
    private inner class Picker : ConversationListEvents {

        override fun onOpen(conversationUuid: String) {
            val service = xmppConnectionService ?: return
            val conversation = service.findConversationByUuid(conversationUuid) as Conversation?
            if (conversation != null) {
                share(conversation)
            }
        }

        override fun onFilter(chosen: ConversationFilter) {
            filter = chosen
            render()
        }

        override fun onAction(action: ConversationAction, conversationUuid: String) {
            // Nothing emits one: this session draws no menu and no swipe, so a row has no action to
            // offer and the picker has none to perform.
        }
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 0x733f32
        private const val REQUEST_START_NEW_CONVERSATION = 0x0501
    }
}
