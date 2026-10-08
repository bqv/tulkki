package uk.xa0.tulkki.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.OutgoingTranslation
import uk.xa0.tulkki.translation.TranslationActivity
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.translation.TranslationStore
import uk.xa0.tulkki.ui.activity.SettingsActivity
import uk.xa0.tulkki.ui.failures.FailuresActions
import uk.xa0.tulkki.ui.failures.FailuresHost
import uk.xa0.tulkki.ui.failures.FailuresState
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The translation failures this phone is still carrying: the <em>host</em>. It reads, and
 * [uk.xa0.tulkki.ui.failures.FailuresScreen] draws.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.2 rewrites this screen; §7.4 says why the two
 * halves are split this way - "**Composables stay dumb**: take a `Ui*` state, emit ids. No
 * `ViewModel` that constructs an entity." So this class keeps only the reading it always had: the rows
 * from the translation queue, the blocker from the same [HeldSend.localReason] question the send path
 * asks before it holds a message, the owed count from the queue's own counter, and the per-message
 * reason from [TranslationActivity] - which is what a covered bubble quotes, so the two cannot
 * disagree about the same message. None of it is a second tally.
 *
 * <p><strong>The screen shows the message's own text, and that is a deliberate reversal.</strong> An
 * original is concealed wherever a bubble would draw it, and this screen is the one owner-approved
 * exception: a failure the owner cannot recognise is one they cannot act on (see
 * [uk.xa0.tulkki.ui.failures.FailuresScreen]). The rows carry the words because `:data`'s two
 * projections select their table's `body` column and [TranslationFailures.Failure] carries it; nothing
 * here reads a body a second time.
 *
 * <p><strong>A row also acts, through [FailuresActions], and every route here is one the conversation
 * already owns.</strong> A received failure's translate-now is [UiHost.requestTranslation] - the
 * covered bubble's and the notification's own tap - and a held send's retry and "send as written" are
 * [OutgoingTranslation.sendHeldNow] and [OutgoingTranslation.sendAsWritten], the two actions the
 * composer's bar carries. The `Sender` the bar passes is this screen's to omit: there is no
 * conversation open, so the row uses the app's own no-screen send route, exactly as the interface
 * documents it. Nothing here re-attempts a send on its own - the retry is the owner's tap - and a
 * refresh after an action is only a re-read of the same database query, so it spends nothing.
 *
 * <p>The read is a database query, so it runs on an executor and the screen is drawn twice: once with
 * the live lines and `null` rows - §3.2's loading state, which is why an empty list would be the wrong
 * thing to hand over - and once when the rows arrive.
 *
 * <p>`fragment_translation_failures.xml` and `item_translation_failure.xml` are deleted with this
 * commit: every view this class used to find by id is a Composable, and the two layouts had no other
 * reader.
 */
class TranslationFailuresFragment : Fragment(), FailuresActions {

    /** The read is a database query; it must not run on the thread that draws the screen. */
    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var appContext: Context
    private lateinit var settings: TranslationSettings
    private var compose: ComposeView? = null

    /** Whether the interpreter is on: §3.2's off state is the screen's, and the predicate is the engine's. */
    private var enabled = true

    /** Why nothing is being translated at all right now, or null - §3.2's live blocker line. */
    private var blocker: HeldSend.HoldReason? = null

    /** How many messages the queue still owes a translation - §3.2's second live line. */
    private var waiting = 0

    /** The rows, or null while the read is in flight: the screen's third value (§3.2). */
    private var rows: List<TranslationFailures.Failure>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        settings = TranslationSettings.get(appContext)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View {
        val view = ComposeView(requireContext())
        // Dispose with the fragment's view, not with the window: the settings hierarchy detaches and
        // re-attaches this screen, and the default strategy would drop the composition with it.
        view.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose = view
        return view
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tulkki_failures_title)
        enabled = settings.interpreter().enabled()
        showWhatIsHappeningNow()
        // The live lines first, with no rows: the read below is a database query and the screen must
        // not claim the list is empty while it is still in flight.
        rows = null
        render()
        readFailures()
    }

    override fun onDestroy() {
        super.onDestroy()
        readExecutor.shutdownNow()
    }

    /**
     * Why translation is not happening at all right now, and how many messages are owed one.
     *
     * <p>The blocker is the same question [HeldSend.localReason] answers for the send path - no key, or
     * the day's cap spent - asked about the app language, because this is about everything received and
     * its target is always known. It is shown so that an empty list cannot read as "nothing is wrong"
     * while every received message is covered. The count is the queue's, the same one the usage screen
     * shows.
     */
    private fun showWhatIsHappeningNow() {
        blocker =
                HeldSend.localReason(
                        settings.hasApiKey(),
                        OutgoingTranslation.capReached(settings),
                        settings.appLanguage())
        waiting = maxOf(UiHost.installed().pendingTranslationCount(appContext), 0)
    }

    private fun readFailures() {
        val activity = getActivity() ?: return
        val recorded = settings.activity()
        readExecutor.execute {
            val failures = TranslationStore(appContext).recentTranslationFailures(recorded)
            activity.runOnUiThread {
                if (isAdded) {
                    rows = failures
                    render()
                }
            }
        }
    }

    /** Assembles the state from the readings and hands it to the screen. */
    private fun render() {
        val view = compose ?: return
        FailuresHost.show(
                view,
                // Item 17's decision four: the conversation's banner opens this same screen with the
                // conversation's uuid, so the list is filtered to it; the settings row passes none and
                // shows every conversation. One screen, one read, one set of row actions - only the
                // argument differs.
                FailuresState(blocker, waiting, rows, conversationFilter()),
                enabled,
                // The bar's own retry wording, so the row's retry is not a second button that says
                // something else (`TranslationSettings.retryWording` is already the value in force).
                settings.retryWording(),
                darkTheme(),
                this::conversationLabel,
                this,
                this::openSettings)
    }

    // -- FailuresActions ---------------------------------------------------------------------------

    /**
     * The covered bubble's own tap, offered from the list: translate this one received message now,
     * against the same cap. The listener is the adapter's - the row is re-read when the answer lands,
     * because the queue writes the outcome on the message and this list is a reading of it.
     */
    override fun translateNow(failure: TranslationFailures.Failure) {
        val service = service()
        val message = loadedMessage(failure)
        if (service == null || message == null) {
            return
        }
        UiHost.installed().requestTranslation(service, message, Consumer { refresh() })
    }

    /**
     * The composer's bar's retry, offered from the list: translate and send this one held row. The
     * `Sender` is deliberately absent - this screen is not a conversation, so the interface's own
     * no-screen send route applies, and nothing here re-attempts the row by itself.
     */
    override fun retry(failure: TranslationFailures.Failure) {
        val service = service()
        val message = loadedMessage(failure)
        if (service == null || message == null) {
            return
        }
        OutgoingTranslation.sendHeldNow(
                service,
                message,
                object : OutgoingTranslation.Listener {
                    override fun onHeld(reason: HeldSend.HoldReason?) {
                        refresh()
                    }
                },
                null)
    }

    /**
     * The bar's one exception to "nothing is sent untranslated", offered from the list: send this one
     * held row as the owner wrote it. It is never automatic and never a setting - the same rule the bar
     * states - and the row leaves the send-failure state before the send, so a second tap is not a
     * second send.
     */
    override fun sendAsWritten(failure: TranslationFailures.Failure) {
        val service = service()
        val message = loadedMessage(failure)
        if (service == null || message == null) {
            return
        }
        OutgoingTranslation.sendAsWritten(service, message, null)
        refresh()
    }

    /**
     * Re-read the list after an action. It spends nothing: the read is the same database query the
     * screen already runs, so an automatic retry - which is `:translation`'s rule and not this
     * screen's - cannot be reached from here.
     */
    private fun refresh() {
        if (!isAdded || readExecutor.isShutdown) {
            return
        }
        readFailures()
    }

    /**
     * Which conversation, in the app's own naming: the name the conversation list shows when the
     * service has it loaded, and the address the database kept when it does not - falling back to
     * "a conversation that is no longer here" rather than to an empty line. The message's own text is
     * drawn by the screen beside this label and not through it.
     */
    private fun conversationLabel(failure: TranslationFailures.Failure): String {
        val conversation = loadedConversation(failure.conversationUuid)
        if (conversation != null) {
            return conversation.getName().toString()
        }
        val jid = failure.conversationJid
        if (jid != null && !jid.isEmpty()) {
            return jid
        }
        return getString(R.string.tulkki_failures_unknown_conversation)
    }

    private fun loadedConversation(conversationUuid: String?): Conversation? {
        if (conversationUuid == null) {
            return null
        }
        val service = service() ?: return null
        return service.findConversationByUuid(conversationUuid) as Conversation?
    }

    /**
     * The message a row is about, from the conversation the service has loaded. A row whose
     * conversation or message is no longer in memory has nothing to act on: the row is still drawn -
     * the diagnostic list is where a failure is recognisable at all - but its buttons cannot reach a
     * message, and the failure is that they do not pretend to.
     */
    private fun loadedMessage(failure: TranslationFailures.Failure): Message? {
        val conversation = loadedConversation(failure.conversationUuid)
        val uuid = failure.messageUuid
        if (conversation == null || uuid == null) {
            return null
        }
        return conversation.findMessageWithUuid(uuid)
    }

    /** The connection service, or null when this fragment is not hosted by an [XmppActivity]. */
    private fun service(): XmppConnectionService? {
        val activity = getActivity()
        if (activity !is XmppActivity) {
            return null
        }
        return activity.xmppConnectionService
    }

    /**
     * The off card's control. This screen is reached from Tulkki's settings page, which pushed it, so
     * the way back to that page is the back stack; a screen reached on its own opens the settings
     * screen instead, the way the ledger's does.
     */
    private fun openSettings() {
        val manager: FragmentManager = requireActivity().supportFragmentManager
        if (manager.backStackEntryCount > 0) {
            manager.popBackStack()
            return
        }
        val intent = Intent(requireActivity(), SettingsActivity::class.java)
        intent.putExtra(
                SettingsActivity.EXTRA_SETTINGS_FRAGMENT,
                TulkkiSettingsFragment::class.java.name)
        startActivity(intent)
    }

    /**
     * Whether the Compose tree is the dark one. The stored preference is what decides it - "a stored
     * preference always wins over the system" - and it reaches the resources through
     * `AppCompatDelegate.setDefaultNightMode`, applied at start-up and in `BaseActivity`, so the
     * Activity's own configuration is the answer and the screen consults no setting.
     */
    private fun darkTheme(): Boolean {
        val mode =
                requireContext().resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /** The conversation to filter to, or null for all of them. */
    private fun conversationFilter(): String? {
        val args = getArguments()
        return if (args == null) null else args.getString(ARG_CONVERSATION)
    }

    companion object {

        /**
         * The conversation this screen is opened for, item 17's decision four: the argument the
         * conversation's own banner sets, so the screen the banner reaches is the settings row's screen
         * filtered to one conversation rather than a second list.
         *
         * <p>Absent means every conversation, which is the settings row's entry point and stays it.
         */
        const val ARG_CONVERSATION = "tulkki_failures_conversation"

        /**
         * The failures screen, filtered to the conversation the banner named.
         *
         * <p>It is the same fragment the settings row opens ([TulkkiSettingsFragment.onOpenScreen]);
         * only the argument differs, so there is one list and one set of routes behind it. The argument
         * is the conversation's local uuid, never its name: two conversations may share a name.
         */
        @JvmStatic
        fun forConversation(conversationUuid: String): TranslationFailuresFragment {
            val fragment = TranslationFailuresFragment()
            val args = Bundle()
            args.putString(ARG_CONVERSATION, conversationUuid)
            fragment.arguments = args
            return fragment
        }
    }
}
