package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The add-reaction screen's screenshot cells - the screen [AddReactionActivity] composes, in both
 * themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**The grid itself is in neither of them, and cannot be.** The screen is one platform
 * `EmojiPickerView` at the picker slot, and its rendering is the platform's - the same argument the
 * top-up screen's cells make about its WebView. What the cells do pin is everything around it and
 * everything this screen owns: the bar with this screen's own title and its up arrow, and the body
 * that fills the content area the chrome leaves (the fixture's picker slot is empty), which is what a
 * substitution (a second bar, an inset, an invented widget) would break.
 *
 * <p>**Why the chrome is in them.** These three screens draw no background of their own: the
 * `Scaffold` inside [TulkkiChrome] paints it, so composing the body alone would render a transparent
 * picture. The cell therefore composes the screen where production composes it, and the title is read
 * from the same resource the Activity reads.
 */
@PreviewTest
@Preview(
    name = "add-reaction-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun AddReactionDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "add-reaction-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun AddReactionLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.add_reaction_title), onUp = {}) {
            AddReactionScreen(picker = {})
        }
    }
}
