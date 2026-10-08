package uk.xa0.tulkki.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.conversationlist.ConversationAction
import uk.xa0.tulkki.ui.conversationlist.ConversationFilter
import uk.xa0.tulkki.ui.conversationlist.ConversationListEvents
import uk.xa0.tulkki.ui.conversationlist.ConversationListFab
import uk.xa0.tulkki.ui.conversationlist.ConversationListHost
import uk.xa0.tulkki.ui.conversationlist.ConversationListPane
import uk.xa0.tulkki.ui.conversationlist.ConversationListPaneSession
import uk.xa0.tulkki.ui.conversationlist.ConversationListPaneState
import uk.xa0.tulkki.ui.conversationlist.ConversationListRead
import uk.xa0.tulkki.ui.conversationlist.ConversationListState
import uk.xa0.tulkki.ui.conversationlist.ConversationOrder
import uk.xa0.tulkki.ui.interfaces.OnConversationArchived
import uk.xa0.tulkki.ui.interfaces.OnConversationSelected
import uk.xa0.tulkki.ui.projection.ConversationProjection
import uk.xa0.tulkki.ui.projection.PreviewWords
import uk.xa0.tulkki.ui.util.MenuDoubleTabUtil
import uk.xa0.tulkki.ui.util.PendingActionHelper
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.ScrollState
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The conversation list: the <em>host</em>. It reads, and
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListScreen] draws.
 *
 * docs/MIGRATION.md "Design: the Compose UI" §3.5 rewrites this screen, and §7.4 says why the two
 * halves are split here: "**Composables stay dumb**: take a `Ui*` state, emit ids." So the fragment keeps
 * every reading and every effect - the account's conversations, the message each row's pointer names, the
 * live facts a snapshot cannot carry, the two FAB flows, the MAM-preference strip, the onboarding
 * auto-select - and the screen keeps the drawing.
 *
 * **The layout is gone.** `fragment_conversation_list.xml` and the two menus it inflated
 * (`fragment_conversation_list.xml`'s ten items and `mam_pref_fix.xml`'s `Ignore`) are deleted: the
 * toolbar items moved to the activity's own chrome, because the toolbar was always the activity's, and
 * the FABs and the MAM strip are
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListPane]. The fragment builds its view in code - a
 * `ComposeView`, since it is Kotlin and can call the pane directly - and the swipe's Material `Snackbar`
 * hangs on that view, exactly as its five-second window and its `DismissEvent` callback did before.
 *
 * **The read is the host's, and it is two reads.** `ConversationListRead.rows` runs one list query per
 * account with each row's pointer already resolved, and one more for the single message each pointer
 * names (§2.3 invariant 4: the preview is the row the pointer names, never a body copied onto the list,
 * so concealing the row conceals the preview by construction). Both are database queries and run on
 * [readExecutor]; only the read comes back to the main thread, which is where the projection's live facts
 * belong. The read is shared with `ShareWithActivity`, which asks the same two questions of the same file.
 *
 * **The activity's own filter is kept, and it is kept as a filter.**
 * `ConversationListActivity.populateWithOrderedConversationList` answers the drawer's question - the
 * selected account, the selected tags, the channels/direct/unread/requests item - out of the service's
 * loaded conversations, and its `mainFilter`/`selectedTag` are private and `selectedAccount()` protected,
 * so a snapshot read alone would silently drop that question. This class therefore still asks the activity
 * which rows it keeps, takes their uuids as membership, and reads the snapshots for the accounts those
 * rows name. No text is drawn from an entity: the name, the preview, the badge and the language pair are
 * all the projection's. The order is **not** the service's - `ConversationOrder.sort` re-derives
 * `Conversation.compareTo`'s three keys over the snapshots, which is what part 6(i) landed it for.
 *
 * **The swipe and the menu's archive are one action, and both may be undone.**
 * `ConversationListEvents.onAction` is emitted with `ConversationMenu.archive(kind)` by the swipe and by
 * the menu's own last entry, so the host cannot tell them apart - the shared value is deliberate - and
 * both take the tree's swipe path: `markRead`, the activity's `OnConversationArchived`, the five-second
 * undo [Snackbar], and a `PendingActionHelper` action that performs the archive when the window closes,
 * when another action starts, or when the fragment pauses. A swipe with no undo would be a regression,
 * not a port.
 *
 * **One behaviour difference, recorded rather than smuggled.** The old adapter attached
 * `ExtendedFabSizeChanger` to the `RecyclerView` so the extended FAB shrank once the list was scrolled; a
 * `ComposeView` has no `RecyclerView.OnScrollListener` and this class has no seam to observe the Compose
 * list state's first visible index, so that one animation is gone. It is navigation polish, not content,
 * and the two FABs themselves are unchanged.
 *
 * **And the old row is gone.** `adapter/ConversationAdapter.java` and `res/layout/item_conversation.xml`
 * were deferred by this rewrite, because `ShareWithActivity` - the share sheet, registered in the manifest
 * and in `shortcuts.xml` - still built the adapter over its own list. Part 6c moved that screen onto this
 * same Compose list, so the two files were deleted with it and the row has one drawing again.
 */
class ConversationListFragment : XmppFragment(), ConversationListEvents {

    /** The rows on screen, by local uuid, in the order they are drawn: what a suggestion is taken from. */
    private val shownUuids: MutableList<String> = ArrayList()

    /** The leaving row, until its undo window closes. */
    private val swipedUuid: PendingItem<String> = PendingItem()

    private val pendingScrollState: PendingItem<ScrollState> = PendingItem()
    private val pendingActionHelper = PendingActionHelper()

    /** The read is a database query; it must not run on the thread that draws the screen. */
    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The last read, kept so the swipe can take its row off the screen without a second query. */
    private var snapshots: List<ConversationSnapshot> = ArrayList()
    private val lastMessages: MutableMap<String, MessageSnapshot> = HashMap()

    /** Bumped on every read, so a query that lands late cannot overwrite a newer one. */
    private var readGeneration = 0

    /** Whether a read landed at all: no rows is §3.5's skeleton, an empty list is its explainer. */
    private var readLanded = false

    private var root: ComposeView? = null
    private var activity: XmppActivity? = null
    private lateinit var appContext: Context
    private lateinit var settings: TranslationSettings

    /** The one Compose state the screen reads; the host updates it after each read. */
    private var session: ConversationListHost.Session? = null

    /** The pane's own state: which FAB and whether the MAM strip is up. */
    private val paneSession = ConversationListPaneSession()

    /** The account the MAM strip is about, which is what the deleted listeners' closures captured. */
    private var pendingMamAccount: Account? = null

    /** The list's own scroll, so STATE_SCROLL_POSITION keeps its position and its offset. */
    private var listState: LazyListState? = null

    /** Which of §3.5's three filters is on. The screen draws the control; the host holds the value. */
    private var filter: ConversationFilter = ConversationFilter.ALL

    /** The projection's `Context::getString`, narrowed the way `PreviewWords` asks for it. */
    private val words: PreviewWords =
        PreviewWords { id, args -> if (args.isEmpty()) getString(id) else getString(id, *args) }

    public override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        if (activity is XmppActivity) {
            this.activity = activity
        } else {
            throw IllegalStateException(
                "Trying to attach fragment to activity that is not an XmppActivity"
            )
        }
        this.appContext = activity.applicationContext
        this.settings = TranslationSettings.get(this.appContext)
    }

    public override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // The layout is gone: `fragment_conversation_list.xml`'s `ComposeView` `list`, its two FABs
        // and its `overview_snackbar` strip are one Compose pane now, and the fragment builds the
        // view in code. The fragment is Kotlin, so it calls the pane directly rather than through
        // `ConversationListHost.show`, which exists for `ShareWithActivity`'s still-Java half.
        val session = ConversationListHost.Session()
        this.session = session
        // The avatars are the activity's: this screen asks it per row rather than loading an image inside a
        // composition, and the picker asks the same capability of the same base.
        session.setAvatar((this.activity ?: throw NullPointerException()).conversationAvatars())
        // The fragment owns the scroll because it is the side that saves it: a Java caller cannot
        // compose a list state of its own, so it makes one and hands it over (part 6a ii).
        val listState = LazyListState(0, 0)
        this.listState = listState
        val view =
            ComposeView(this.activity ?: throw NullPointerException()).apply {
                setTulkkiContent(darkTheme()) {
                    ConversationListPane(
                        session = session,
                        paneSession = paneSession,
                        listState = listState,
                        events = this@ConversationListFragment,
                        onFab = { StartConversationActivity.launch(this@ConversationListFragment.getActivity() ?: throw NullPointerException()) },
                        onWarningAction = { onMamPreferenceFix() },
                        onWarningIgnore = { onMamPreferenceIgnore() },
                    )
                }
            }
        this.root = view
        return view
    }

    public override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            return
        }
        pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION))
    }

    public override fun onStart() {
        super.onStart()
        Log.d(Config.LOGTAG, "ConversationListFragment.onStart()")
        val current = activity ?: throw NullPointerException()
        if (current.xmppConnectionService != null) {
            refresh()
        }
        if (current is ConversationListActivity) {
            current.showNavigationBar()
            applyFabVisibility()
        }
    }

    public override fun onResume() {
        super.onResume()
        Log.d(Config.LOGTAG, "ConversationListFragment.onResume()")
    }

    public override fun onPause() {
        Log.d(Config.LOGTAG, "ConversationListFragment.onPause()")
        // Leaving the screen commits a swipe whose undo window is still open, exactly as the tree did.
        pendingActionHelper.execute()
        super.onPause()
    }

    public override fun onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        val scrollState = getScrollState()
        if (scrollState != null) {
            bundle.putParcelable(STATE_SCROLL_POSITION, scrollState)
        }
    }

    public override fun onDestroyView() {
        Log.d(Config.LOGTAG, "ConversationListFragment.onDestroyView()")
        super.onDestroyView()
        this.root = null
        this.session = null
        this.listState = null
    }

    public override fun onDestroy() {
        Log.d(Config.LOGTAG, "ConversationListFragment.onDestroy()")
        readExecutor.shutdownNow()
        super.onDestroy()
    }

    public override fun onDetach() {
        super.onDetach()
        this.activity = null
    }

    public override fun onBackendConnected() {
        refresh()
    }


    // -- the screen's vocabulary -----------------------------------------------------------------

    /** A row was tapped: open that conversation, which is the service's own entity for the uuid. */
    public override fun onOpen(conversationUuid: String) {
        val conversation = conversationByUuid(conversationUuid)
        if (conversation == null) {
            Log.w(
                ConversationListFragment::class.java.canonicalName,
                "no loaded conversation for the tapped row",
            )
            return
        }
        val current = activity
        if (current is OnConversationSelected) {
            current.onConversationSelected(conversation)
        } else {
            Log.w(
                ConversationListFragment::class.java.canonicalName,
                "Activity does not implement OnConversationSelected",
            )
        }
    }

    /**
     * One of §3.5's filters was chosen. It is the host's value and the screen's control, and the rows are
     * re-drawn from the read that already landed - no query, because a filter is a predicate.
     */
    public override fun onFilter(chosen: ConversationFilter) {
        this.filter = chosen
        render()
    }

    /**
     * A row's action was chosen, from the long-press menu or from a swipe. The archive the swipe names is
     * also the menu's last entry, so both take the reversible path; every other entry is the tree's own
     * dispatch, unchanged.
     */
    public override fun onAction(action: ConversationAction, conversationUuid: String) {
        when (action) {
            ConversationAction.ARCHIVE_CHAT,
            ConversationAction.LEAVE_GROUP,
            ConversationAction.END_CHANNEL -> {
                archiveAndUndo(conversationUuid)
                return
            }
            else -> performRowAction(action, conversationUuid)
        }
    }

    /**
     * The tree's swipe: the row leaves the list at once, the conversation is marked read, the activity
     * hears about it, and a five-second Snackbar offers the undo. The archive itself is deferred -
     * [pendingActionHelper] performs it when the window closes, when another action starts, or when
     * the fragment pauses - so the undo has something to undo.
     */
    private fun archiveAndUndo(conversationUuid: String) {
        pendingActionHelper.execute()
        val current = this.activity
        val service = service()
        val conversation = conversationByUuid(conversationUuid)
        val root = this.root
        if (current == null || service == null || conversation == null || root == null) {
            return
        }
        swipedUuid.push(conversationUuid)
        service.markRead(conversation)
        render()

        if (shownUuids.isEmpty()) {
            // The only row left by the swipe goes without a Snackbar to undo it, as the tree's own
            // `position == 0 && getItemCount() == 0` branch did.
            val only = swipedUuid.pop()
            val last = if (only == null) null else conversationByUuid(only)
            if (last != null) {
                service.archiveConversation(last)
            }
            render()
            return
        }

        val formerlySelected = ConversationLookup.conversation(current) == conversation
        if (current is OnConversationArchived) {
            current.onConversationArchived(conversation)
        }
        val title =
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                if (conversation.getMucOptions().isPrivateAndNonAnonymous()) {
                    R.string.title_undo_swipe_out_group_chat
                } else {
                    R.string.title_undo_swipe_out_channel
                }
            } else {
                R.string.title_undo_swipe_out_chat
            }

        val snackbar =
            Snackbar.make(root, title, 5000)
                .setAction(R.string.undo) {
                    pendingActionHelper.undo()
                    val restored = swipedUuid.pop()
                    render()
                    if (restored != null &&
                        formerlySelected &&
                        current is OnConversationSelected
                    ) {
                        val conversationAgain = conversationByUuid(restored)
                        if (conversationAgain != null) {
                            current.onConversationSelected(conversationAgain)
                        }
                    }
                    scrollIntoView(restored)
                }
                .addCallback(
                    object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            when (event) {
                                Snackbar.Callback.DISMISS_EVENT_SWIPE,
                                Snackbar.Callback.DISMISS_EVENT_TIMEOUT ->
                                    pendingActionHelper.execute()
                            }
                        }
                    }
                )

        pendingActionHelper.push {
            if (snackbar.isShownOrQueued) {
                snackbar.dismiss()
            }
            val pending = swipedUuid.pop()
            if (pending != null) {
                val leaving = conversationByUuid(pending)
                if (leaving != null) {
                    if (!leaving.isRead(service) &&
                        leaving.getMode() == Conversation.MODE_SINGLE
                    ) {
                        return@push
                    }
                    service.archiveConversation(leaving)
                }
            }
            render()
        }
        snackbar.show()
    }

    /**
     * Every other entry of the row's menu. The tree already performs each one on a `MenuItem`, against a
     * `ConversationFragment` attached to this activity and re-initialised on the conversation, so the
     * dispatch is not re-implemented here: the fragment builds the item the id names and forwards it,
     * which is what `onContextItemSelected` did before the menu became a Composable.
     */
    private fun performRowAction(action: ConversationAction, conversationUuid: String) {
        val current = this.activity
        val conversation = conversationByUuid(conversationUuid)
        val root = this.root
        if (current == null || conversation == null || root == null) {
            return
        }
        val fragment = ConversationFragment()
        fragment.setHasOptionsMenu(false)
        fragment.onAttach(current)
        fragment.reInit(conversation, null)
        val menu = PopupMenu(current, root)
        val item = menu.getMenu().add(0, menuId(action), 0, "")
        fragment.onOptionsItemSelected(item)
        refresh()
    }

    // -- the read --------------------------------------------------------------------------------

    public override fun refresh() {
        val root = this.root
        val current = this.activity
        if (root == null || current == null) {
            Log.d(
                Config.LOGTAG,
                "ConversationListFragment.refresh() skipped updated because view binding or activity was null",
            )
            return
        }

        // The activity's own filter, on the thread that owns it. It is membership only: no name and no
        // preview is drawn from an entity here. The set is this read's own - the query below runs on
        // another thread, and a later refresh must not move the ground under it.
        val allowed = ArrayList<Conversation>()
        current.populateWithOrderedConversationList(allowed)
        val membership = LinkedHashSet<String>()
        val accounts = LinkedHashSet<String>()
        for (conversation in allowed) {
            // The model's uuid is nullable; a row without one can never match an allowed id.
            conversation.getUuid()?.let { membership.add(it) }
            val account = conversation.getAccount()
            if (account != null) {
                account.getUuid()?.let { accounts.add(it) }
            }
        }

        val removed = swipedUuid.peek()
        if (removed != null) {
            val conversation = conversationByUuid(removed)
            if (conversation == null || conversation.isRead(current.xmppConnectionService)) {
                swipedUuid.clear()
            } else {
                pendingActionHelper.execute()
            }
        }

        applySwipePreference()
        val listState = this.listState
        val scrollState = pendingScrollState.pop()
        if (scrollState != null && listState != null) {
            listState.requestScrollToItem(scrollState.position, scrollState.offset)
        }
        applyFabVisibility()
        showMamPreferenceWarning()

        val generation = ++readGeneration
        readExecutor.execute {
            // The avatars are resolved here rather than in the composition: it is a file read, and
            // the rows that need one are the rows this read is about.
            current.warmConversationAvatars(allowed)
            val read = ConversationListRead.rows(appContext, membership, accounts)
            current.runOnUiThread {
                if (generation != readGeneration || !isAdded) {
                    return@runOnUiThread
                }
                snapshots = read.snapshots
                lastMessages.clear()
                lastMessages.putAll(read.lastMessages)
                readLanded = true
                render()
                autoSelectDuringOnboarding()
            }
        }
    }

    /**
     * The state the screen draws, from the read that last landed. The swiped row is left out here rather
     * than removed from a list: the screen is a drawing of this state, so leaving it out here is what takes
     * it off the screen, and putting it back is the undo.
     */
    private fun render() {
        val session = this.session ?: return
        val current = this.activity ?: return
        if (!readLanded) {
            session.update(
                ConversationListState(null, filter, ConversationListRead.connection(), false),
            )
            return
        }
        val now = System.currentTimeMillis()
        val kept = ArrayList<ConversationSnapshot>()
        val sortableTimes = HashMap<String, Long>()
        val swiped = swipedUuid.peek()
        for (snapshot in snapshots) {
            if (snapshot.id == swiped) {
                continue
            }
            kept.add(snapshot)
            sortableTimes[snapshot.id] =
                ConversationOrder.sortableTime(
                    snapshot,
                    snapshot.lastMessageId?.let { lastMessages[it] },
                    ConversationProjection.lastClearHistoryAt(snapshot),
                    draftAt(snapshot.id),
                )
        }
        // `archivedVisible` stays false: the tree never carried an archived row in this list, because
        // `archiveConversation` takes the conversation out of the service's list, and the activity's own
        // filter - which is this read's membership - is built from that list.
        val assembled =
            ConversationListHost.assemble(
                kept,
                lastMessages,
                settings.appLanguage(),
                settings.interpreter(),
                words,
                now,
                current.conversationListFacts(),
                current.messageFacts(),
                filter,
                ConversationListRead.connection(),
                false,
                // The tree's `always_full_timestamps`, inverted: what the owner allows a row's
                // own clock to say.
                !current.getBooleanPreference(
                    "always_full_timestamps",
                    R.bool.always_full_timestamps,
                ),
            )
        val sorted =
            ConversationListState(
                ConversationOrder.sort(
                    assembled.rows ?: throw NullPointerException(),
                    sortableTimes,
                ),
                filter,
                assembled.connection,
                false,
            )
        shownUuids.clear()
        // The rows the screen actually draws, which is what a suggestion must be taken from: the state's
        // own filter and its archived gate are the screen's, and a row they hide is not on offer.
        shownUuids.addAll(ConversationOrder.uuids(sorted.visible))
        session.update(sorted)
    }

    /** The tree's onboarding rule: one conversation on screen, and the owner is taken into it. */
    private fun autoSelectDuringOnboarding() {
        val service = service() ?: return
        if (!service.isOnboarding() || shownUuids.size != 1) {
            return
        }
        val conversation = conversationByUuid(shownUuids[0]) ?: return
        val current = activity
        if (current is OnConversationSelected) {
            current.onConversationSelected(conversation)
        } else {
            Log.w(
                ConversationListFragment::class.java.canonicalName,
                "Activity does not implement OnConversationSelected",
            )
        }
    }

    /** The two FAB flows, in the order the tree asked: onboarding hides both, and the nav bar picks one. */
    private fun applyFabVisibility() {
        val current = this.activity
        if (this.root == null || current == null) {
            return
        }
        val service = current.xmppConnectionService
        if (service != null && service.isOnboarding()) {
            paneSession.update(paneSession.state.copy(fab = ConversationListFab.NONE))
            return
        }
        if (current is ConversationListActivity) {
            val showed = current.showNavigationBar()
            paneSession.update(
                paneSession.state.copy(
                    fab =
                        if (showed) {
                            ConversationListFab.START_CONVERSATION
                        } else {
                            ConversationListFab.EXTENDED
                        },
                ),
            )
        }
    }

    /**
     * The `swipe_to_archive` preference and the onboarding gate the tree applied beside it. It is set on
     * the session rather than handed to `show`, because the owner can change the preference while this
     * screen is alive and `show` is called once.
     */
    private fun applySwipePreference() {
        val session = this.session
        val current = this.activity
        if (session == null || current == null) {
            return
        }
        val service = service()
        session.setSwipeEnabled(
            PreferenceManager.getDefaultSharedPreferences(current)
                .getBoolean("swipe_to_archive", true) &&
                (service == null || !service.isOnboarding()),
        )
    }

    /**
     * The MAM-preference warning, unchanged: the first account whose server does not archive `always`
     * raises one strip, its action sets the preference and pushes it, and a long press offers to ignore
     * this account for good.
     *
     * <p>It is state now rather than three views, because the strip is the one surface the deleted
     * `mam_pref_fix.xml` belonged to: `Ignore` is the strip's own long-press menu in
     * [uk.xa0.tulkki.ui.conversationlist.ConversationListPane], and [pendingMamAccount] is what the
     * deleted `setOnClickListener`/`setOnLongClickListener` closures captured.
     */
    private fun showMamPreferenceWarning() {
        // The Java read the attached activity without a check; a null was its NPE.
        val current = activity ?: throw NullPointerException()
        val service = current.xmppConnectionService ?: return
        if (this.root == null) return
        var warning: String? = null
        var account: Account? = null
        for (candidate in AccountRegistry.get().getAccounts()) {
            if (PreferenceManager.getDefaultSharedPreferences(current)
                    .getBoolean("no_mam_pref_warn:" + candidate.getUuid(), false)
            ) {
                continue
            }
            val mamPrefs = candidate.mamPrefs()
            if (mamPrefs != null && "always" != mamPrefs.getAttribute("default")) {
                // The deleted strip's own wording, character for character - including its two
                // resource ids, which it concatenated as integers. Left as it is: it is the one
                // defect this conversion did not touch, because "the same rendering" is the port's
                // contract and the repair is separable.
                warning =
                    R.string.your_account.toString() +
                        " " +
                        candidate.getJid().asBareJid().toString() +
                        " " +
                        R.string.archiving_not_enabled_text.toString()
                account = candidate
                break
            }
        }
        pendingMamAccount = account
        paneSession.update(paneSession.state.copy(warning = warning))
    }

    /** The deleted strip action's own body: the account archives `always`, and the server is told. */
    private fun onMamPreferenceFix() {
        val current = activity ?: return
        val service = current.xmppConnectionService ?: return
        val account = pendingMamAccount ?: return
        val prefs = account.mamPrefs() ?: throw NullPointerException()
        prefs.setAttribute("default", "always")
        service.pushMamPreferences(account, prefs)
        refresh()
    }

    /** The deleted `mam_pref_fix.xml`'s `Ignore`: this account is never warned about again. */
    private fun onMamPreferenceIgnore() {
        val current = activity ?: return
        val account = pendingMamAccount ?: return
        PreferenceManager.getDefaultSharedPreferences(current)
            .edit()
            .putBoolean("no_mam_pref_warn:" + account.getUuid(), true)
            .apply()
        refresh()
    }

    // -- the small answers the read and the projection need --------------------------------------

    private fun getScrollState(): ScrollState? {
        val listState = this.listState ?: return null
        return ScrollState(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        )
    }

    /** The tree scrolled only when the restored row was below the last one on screen. */
    private fun scrollIntoView(uuid: String?) {
        if (uuid == null) {
            return
        }
        val listState = this.listState ?: return
        val position = shownUuids.indexOf(uuid)
        if (position < 0) {
            return
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            return
        }
        if (position > visible[visible.size - 1].index) {
            listState.requestScrollToItem(position, 0)
        }
    }

    private fun darkTheme(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun service(): XmppConnectionService? {
        return activity?.xmppConnectionService
    }

    private fun conversationByUuid(uuid: String?): Conversation? {
        val service = service()
        if (service == null || uuid == null) {
            return null
        }
        return service.findConversationByUuid(uuid) as Conversation?
    }

    /** The draft's own instant, or `null`: the snapshot read names no draft row. */
    private fun draftAt(uuid: String?): Long? {
        val conversation = conversationByUuid(uuid) ?: return null
        val draft = conversation.getDraft()
        return draft?.getTimestamp()
    }

    companion object {
        private val STATE_SCROLL_POSITION: String =
            ConversationListFragment::class.java.name + ".scroll_state"

        /**
         * The conversation a caller should offer next, by its local uuid - the fragment's own answer without
         * handing an entity out. The tree's two overloads are one rule, and the first excludes the row a swipe
         * is already taking away.
         */
        @JvmStatic
        fun suggestionUuid(activity: Activity): String? {
            val fragment = activity.fragmentManager.findFragmentById(R.id.main_fragment)
            val excluded =
                if (fragment is ConversationListFragment) {
                    fragment.swipedUuid.peek()
                } else {
                    null
                }
            return suggestionUuid(activity, excluded)
        }

        /** The same rule with the caller's own exclusion, which the archive path names as a uuid. */
        @JvmStatic
        fun suggestionUuid(activity: Activity, excluded: String?): String? {
            val fragment = activity.fragmentManager.findFragmentById(R.id.main_fragment)
            if (fragment is ConversationListFragment) {
                return ConversationOrder.suggestion(fragment.shownUuids, excluded)
            }
            return null
        }

        /** The `menu/conversation_context.xml` id each action is the tree's own name for. */
        private fun menuId(action: ConversationAction): Int =
            when (action) {
                ConversationAction.PIN,
                ConversationAction.UNPIN -> R.id.action_toggle_pinned
                ConversationAction.MUTE -> R.id.action_mute
                ConversationAction.UNMUTE -> R.id.action_unmute
                ConversationAction.ONGOING_CALL -> R.id.action_ongoing_call
                ConversationAction.CONTACT_DETAILS -> R.id.action_contact_details
                ConversationAction.MUC_DETAILS,
                ConversationAction.CHANNEL_DETAILS -> R.id.action_muc_details
                ConversationAction.BLOCK_AVATAR -> R.id.action_block_avatar
                else -> R.id.action_archive
            }
    }
}
