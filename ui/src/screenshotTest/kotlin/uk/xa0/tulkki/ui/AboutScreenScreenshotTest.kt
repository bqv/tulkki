package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The about screen's screenshot cells - the screen [AboutActivity] composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome, the welcome mark and
 * the conversation screen use).
 *
 * <p>**What the two cells pin.** The whole visible screen, because that is what
 * [AboutActivity] hands the chrome: the bar with this screen's own title and its up arrow, and the
 * body - the real `R.string.pref_about_message` drawn scrollable and monospaced in
 * `MaterialTheme.typography.bodyMedium`, its 16 dp padding, and the web addresses in it as links in
 * the theme's primary colour. A cell that loses the monospace, the padding or the link styling moves
 * a pixel.
 *
 * <p>**Why the chrome is in them.** These three screens draw no background of their own: the
 * `Scaffold` inside [TulkkiChrome] paints it, so composing the body alone would render a transparent
 * picture. The cell therefore composes the screen where production composes it, and the title is read
 * from the same resource the Activity reads.
 */
@PreviewTest
@Preview(name = "about-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun AboutDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(name = "about-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun AboutLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.title_activity_about_x, BuildConfig.APP_NAME),
            onUp = {},
        ) {
            AboutScreen(text = stringResource(R.string.pref_about_message))
        }
    }
}
