package uk.xa0.tulkki.ui.activity

import android.app.Notification
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout

import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView

import com.google.common.collect.ImmutableSet

import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationFailuresFragment
import uk.xa0.tulkki.ui.TulkkiSettingsFragment
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.fragment.settings.MainSettingsFragment
import uk.xa0.tulkki.ui.fragment.settings.NotificationsSettingsFragment
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.ui.preferences.SettingsTopBar

/**
 * The settings container: one bar, and the screen the entry names.
 *
 * <p>**The layout is gone.** `activity_settings.xml` was a `CoordinatorLayout`, an `AppBarLayout` and a
 * `MaterialToolbar` over a `FragmentContainerView`, and
 * [uk.xa0.tulkki.ui.preferences.SettingsTopBar] plus a `FragmentContainerView` built in code are the
 * same two pieces now. `DataBindingUtil.setContentView`, `setSupportActionBar`,
 * `Activities.setStatusAndNavigationBarColors` and `setContentView` went with it, and the window is
 * `enableEdgeToEdge`'s: the bar draws behind the status bar and insets its own content, and the
 * container is padded for the navigation bar and the cutout so a row is never under either.
 *
 * <p>**The screens stay fragments, and that is deliberate.** `TranslationFailuresFragment` is one and
 * is not this lane's, and [TulkkiSettingsFragment] is addressed by class name through
 * [EXTRA_SETTINGS_FRAGMENT] - so a fragment's view has to be created into a container that is already
 * attached, which is why the container is a `View` beside the bar rather than a `Composable` inside a
 * `Scaffold`. The bar is one bar, drawn once, and every screen in the container keeps calling
 * `setTitle`, which is what this class now feeds the bar; a screen therefore says what it already said
 * and draws no bar of its own.
 *
 * <p>The up arrow is the same decision it was: a nested route inside the front screen takes the press
 * first ([TulkkiSettingsFragment.onToolbarBack]), then the back stack, then this screen's own exit.
 */
class SettingsActivity : XmppActivity() {

    companion object {
        /**
         * Tulkki: the class name of a settings screen to open directly instead of the settings list.
         *
         * The list reaches [TulkkiSettingsFragment] through its own row, and nothing outside this
         * activity can address it, so a caller that already knows which screen the owner wants names it
         * here. Absent, or naming a screen this activity does not know, is the usual entry screen. The
         * named screen is opened as the only fragment, so the toolbar's back arrow finishes the
         * activity and returns to whatever opened it.
         */
        const val EXTRA_SETTINGS_FRAGMENT = "tulkki_settings_fragment"

        /**
         * Tulkki: the conversation a failures screen opened through this activity is filtered to.
         *
         * Item 17's decision four: the conversation's banner reaches the same
         * [TranslationFailuresFragment] the settings row does, but for one conversation. It cannot
         * replace a fragment in the conversation's own activity - that activity's panes are the list
         * and the conversation - so it starts this screen's container with the conversation named,
         * and the fragment filters its read to it.
         */
        const val EXTRA_FAILURES_CONVERSATION = "tulkki_failures_conversation"
    }

    /** The bar's title, set by the screen in the container through the same `setTitle` it always used. */
    private var barTitle by mutableStateOf<CharSequence?>(null)

    override fun refreshUiReal() {}

    /**
     * The title every screen in the container already set, now the bar's. `super` keeps the window's own
     * title current, which is what the recents entry reads.
     */
    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        barTitle = title
    }

    override fun setTitle(titleId: Int) {
        super.setTitle(titleId)
        barTitle = getText(titleId)
    }

    override fun onBackendConnected() {
        val fragmentManager = supportFragmentManager
        val currentFragment = fragmentManager.findFragmentById(R.id.settings_fragment_container)
        if (currentFragment is PreferenceScreenFragment) {
            currentFragment.onBackendConnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bar owns the system bars now, so the window must not inset itself for them;
        // `enableEdgeToEdge()` is what makes the bar's own window insets mean anything on every API
        // level, exactly as the Compose chrome's pattern describes.
        enableEdgeToEdge()

        val bar =
            ComposeView(this).apply {
                setTulkkiContent(darkTheme = isDark()) {
                    SettingsTopBar(title = barTitle, onUp = { onUpPressed() })
                }
            }
        val container = FragmentContainerView(this).apply { id = R.id.settings_fragment_container }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    container,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        setContentView(root)

        val currentIntent = intent
        val categories: Set<String>? = currentIntent?.categories
        val failuresConversation = currentIntent?.getStringExtra(EXTRA_FAILURES_CONVERSATION)
        val screen: Fragment
        if (failuresConversation != null) {
            // Tulkki: item 17's decision four. The conversation's banner names the conversation it
            // came from, and this opens the settings row's own failures screen filtered to it.
            screen = TranslationFailuresFragment.forConversation(failuresConversation)
        } else if (TulkkiSettingsFragment::class.java.name ==
            currentIntent?.getStringExtra(EXTRA_SETTINGS_FRAGMENT)
        ) {
            // Tulkki: an explicit request for one screen beats the defaults below.
            screen = TulkkiSettingsFragment()
        } else if (ImmutableSet.of(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES) ==
            categories
        ) {
            screen = NotificationsSettingsFragment()
        } else {
            screen = MainSettingsFragment()
        }

        val fragmentManager = supportFragmentManager
        val currentFragment = fragmentManager.findFragmentById(R.id.settings_fragment_container)
        if (currentFragment == null) {
            fragmentManager
                .beginTransaction()
                .replace(R.id.settings_fragment_container, screen)
                .commit()
        }
    }

    /** The bar's one action: the same decision the XML toolbar's navigation click made. */
    private fun onUpPressed() {
        // A screen that has somewhere of its own to go back to takes the press first: the
        // settings page's prompts sub-screen is a route, not a fragment entry, so without this
        // the arrow would leave the whole screen from inside it.
        val fragmentManager = supportFragmentManager
        val front = fragmentManager.findFragmentById(R.id.settings_fragment_container)
        if (front is TulkkiSettingsFragment && front.onToolbarBack()) {
            return
        }
        if (fragmentManager.backStackEntryCount == 0) {
            finish()
        } else {
            fragmentManager.popBackStack()
        }
    }
}
