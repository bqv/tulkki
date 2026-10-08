package uk.xa0.tulkki.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.settings.SettingsActions
import uk.xa0.tulkki.ui.settings.SettingsHost
import uk.xa0.tulkki.ui.settings.SettingsPage
import uk.xa0.tulkki.ui.settings.SettingsRoute
import uk.xa0.tulkki.ui.settings.SettingsRow
import uk.xa0.tulkki.ui.settings.TulkkiSettingsState

import java.text.NumberFormat
import java.util.EnumMap

/**
 * Tulkki's settings: the <em>host</em>. It reads, and `SettingsScreen` draws.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.1 rewrites this screen; §7.4 says why the two
 * halves are split this way - "**Composables stay dumb**: take a `Ui*` state, emit ids. No
 * `ViewModel` that constructs an entity." So this class keeps only the reading and the writing: it
 * assembles a [TulkkiSettingsState] from [TranslationSettings], hands it to the screen with the route
 * and the row whose editor is open, and implements [SettingsActions] - every write goes through
 * [TranslationSettingsStore], which is the raw-key contract the rest of the app reads.
 *
 * <p><strong>There is no preference tree.</strong> The class is a plain [Fragment] whose
 * `onCreateView` returns a [ComposeView], and the data store is still the one the writes go through,
 * so a raw preference key cannot drift from what the rest of the app reads. It was a
 * `PreferenceFragmentCompat` while it was an `app:fragment` destination in `preferences_main.xml`;
 * that resource is deleted and the main list opens this screen by class name, so the preference
 * machinery is gone with it. `preferences_tulkki.xml` was deleted when this screen became Compose.
 *
 * <p>**Two modes, one page.** `SettingsPage.rows` is a function of the state, so the off state is the
 * two language rows plus one line and the on state is the whole page; nothing is added to or removed
 * from a tree, and there is no rebind to go stale. The two rows that are greyed rather than removed -
 * "tap to unblur" while the English master is off, and the received original's switch while "Show the
 * second half" is off - carry their reason in the state, so the screen cannot show a row that can do
 * nothing without saying so.
 *
 * <p>The key is the one secret: the field masks it, the summary only ever says whether one is stored,
 * and nothing here logs it.
 */
class TulkkiSettingsFragment : Fragment(), SettingsActions {

    private lateinit var appContext: Context
    private lateinit var store: TranslationSettingsStore
    private var compose: ComposeView? = null

    /** The page in view; the prompts are the one nested route. */
    private var route = SettingsRoute.PAGE

    /** The row whose editor is open, or null. The editor itself is shown by [SettingsHost]. */
    private var editing: SettingsRow? = null

    /** True while the prompts sub-screen is open, so the system back returns to the page. */
    private var backToPage: OnBackPressedCallback? = null

    /**
     * The store the writes go through. It is built here rather than in a preference screen, because
     * there is no preference screen: this class draws Compose and stores through
     * [TranslationSettingsStore]'s raw keys.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        store = TranslationSettingsStore(appContext)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View {
        val view = ComposeView(requireContext())
        // Dispose with the fragment's view, not with the window: going into the Ledger and back
        // replaces this fragment, and the default strategy would drop the composition with it.
        view.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose = view
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val callback =
                object : OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() {
                        onRoute(SettingsRoute.PAGE)
                    }
                }
        backToPage = callback
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        render()
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tulkki_settings_title)
        // Whatever changed while another screen was in front - a language set from a conversation, a
        // prompt written elsewhere - is on screen again at once, because the state is read per render
        // rather than held in a tree.
        render()
    }

    override fun onDestroyView() {
        compose = null
        editing = null
        backToPage = null
        super.onDestroyView()
    }

    /**
     * The toolbar's back arrow, asked before the activity finishes or pops.
     *
     * <p>The system's back is handled by the dispatcher callback above; the toolbar is the activity's
     * own listener and would otherwise leave the settings screen from the prompts sub-screen, which is
     * not what the arrow means there. Returns true when it consumed the press.
     */
    fun onToolbarBack(): Boolean {
        if (route == SettingsRoute.PROMPTS) {
            onRoute(SettingsRoute.PAGE)
            return true
        }
        return false
    }

    // -- SettingsActions ---------------------------------------------------------------------------

    override fun onRoute(next: SettingsRoute) {
        route = next
        render()
    }

    /**
     * A switch. The write is the store's, so the two language rows - which are the switch itself - run
     * the on&rarr;off clear and drop the state that only meant something while interpreting.
     */
    override fun onToggle(key: String, value: Boolean) {
        store.putBoolean(key, value)
        render()
    }

    override fun onEdit(row: SettingsRow) {
        editing = row
        render()
    }

    override fun onOpenScreen(key: String) {
        if (TulkkiSettingsRows.KEY_TOP_UP == key) {
            // The one row that opens an activity rather than a fragment: the platform's own top-up
            // page, in a WebView, which a fragment host cannot be.
            startActivity(Intent(requireContext(), TopUpActivity::class.java))
            return
        }
        if (TulkkiSettingsRows.KEY_PROMPTS == key) {
            onRoute(SettingsRoute.PROMPTS)
            return
        }
        val target: Fragment
        if (SettingsPage.KEY_USAGE == key) {
            target = UsageFragment()
        } else if (SettingsPage.KEY_FAILURES == key) {
            target = TranslationFailuresFragment()
        } else {
            return
        }
        // The two screens that host Compose already, opened over this one and kept on the back stack
        // so the toolbar's arrow - and the system's back - return here rather than to the list. The
        // container is this fragment's own parent, not a named id: `activity_settings.xml` is deleted
        // and the screen the settings tree lives in is the one that knows which container it is.
        val containerId = (view?.parent as? ViewGroup)?.id ?: R.id.settings_fragment_container
        requireActivity()
                .supportFragmentManager
                .beginTransaction()
                .replace(containerId, target)
                .addToBackStack(null)
                .commit()
    }

    override fun onDismissEditor() {
        editing = null
        render()
    }

    override fun commitText(key: String, value: String) {
        store.putString(key, value)
        editing = null
        render()
    }

    override fun commitNumber(key: String, value: Int) {
        store.putInt(key, value)
        editing = null
        render()
    }

    override fun commitLanguage(key: String, code: String) {
        store.putString(key, code)
        editing = null
        render()
    }

    // -- the reading -------------------------------------------------------------------------------

    /** The page's state, read fresh: nothing here is held across a render. */
    private fun state(): TulkkiSettingsState {
        val settings = settings()
        val edited: MutableMap<PromptBook.Kind, Boolean> =
                EnumMap(PromptBook.Kind::class.java)
        val lengths: MutableMap<PromptBook.Kind, Int> =
                EnumMap(PromptBook.Kind::class.java)
        for (kind in PromptBook.Kind.values()) {
            edited[kind] = PromptBook.edited(kind)
            lengths[kind] = PromptBook.template(kind).length
        }
        return TulkkiSettingsState(
                settings.interpreter().enabled(),
                settings.appLanguage(),
                settings.studyLanguage(),
                settings.hasApiKey(),
                settings.apiKeyStorageAvailable(),
                settings.apiBaseUrl(),
                settings.dailyTokenCap(),
                settings.revealSuggestionFirst(),
                settings.showSecondHalf(),
                settings.showConcealedOriginal(),
                settings.concealOwnSecondHalf(),
                settings.showBlurredEnglish(),
                settings.unblurEnglishOnTap(),
                settings.showEnglishRetranslation(),
                edited,
                lengths,
                settings.retryWording())
    }

    /** Assembles the state from the readings and hands it to the screen. */
    private fun render() {
        backToPage?.isEnabled = route == SettingsRoute.PROMPTS
        val view = compose ?: return
        SettingsHost.show(view, state(), route, editing, darkTheme(), this)
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

    private fun settings(): TranslationSettings {
        return TranslationSettings.get(appContext)
    }

    companion object {
        /** Grouped in the phone's own convention, because this is a number a person reads. */
        @JvmStatic
        fun formatTokens(tokens: Int): String {
            return NumberFormat.getIntegerInstance().format(tokens)
        }
    }
}
