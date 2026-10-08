package uk.xa0.tulkki.ui.ledger

import androidx.compose.ui.platform.ComposeView
import java.util.function.Consumer
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The Java door to [LedgerScreen], for the one host that still is Java: `UsageFragment`.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §7.4's "the theme is a value passed in, so a screenshot
 * test can render any theme without an Activity" holds here too: the host reads the effective night mode
 * off the Activity's own resources - which follows the stored preference, because
 * `AppCompatDelegate.setDefaultNightMode` is applied at start-up and in `BaseActivity` - and hands it
 * in, rather than the screen consulting a setting.
 *
 * <p>**Each call replaces the composition.** That is the honest trade of a Java host with no observable
 * state holder: the host re-renders by re-setting the content after a reading arrives, so a refresh
 * resets the screen's scroll position. The fix is a `MutableState<LedgerState>` the screen reads (or a
 * ViewModel), which is a later refinement of the host and not of the screen - the screen itself already
 * takes one immutable state, so nothing here can drift from the screenshots.
 */
object LedgerHost {

    @JvmStatic
    fun show(
        view: ComposeView,
        state: LedgerState,
        prices: TokenPrices,
        enabled: Boolean,
        hasKey: Boolean,
        notices: LedgerNotices,
        drill: DayDrill?,
        darkTheme: Boolean,
        onEditPrice: Consumer<String>,
        onOpenDay: Consumer<String>,
        onRefresh: Runnable,
        onOpenSettings: Runnable,
    ) {
        view.setContent {
            TulkkiTheme(darkTheme = darkTheme) {
                LedgerScreen(
                    state = state,
                    prices = prices,
                    enabled = enabled,
                    hasKey = hasKey,
                    notices = notices,
                    drill = drill,
                    onOpenDay = { onOpenDay.accept(it) },
                    onEditPrice = { onEditPrice.accept(it.field) },
                    onRefresh = { onRefresh.run() },
                    onOpenSettings = { onOpenSettings.run() },
                )
            }
        }
    }
}
