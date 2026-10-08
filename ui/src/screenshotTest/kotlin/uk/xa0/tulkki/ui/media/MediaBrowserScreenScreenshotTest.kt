package uk.xa0.tulkki.ui.media

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The media browser's screenshot cells - the whole screen [uk.xa0.tulkki.ui.MediaBrowserActivity]
 * composes, in both themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the two cells pin.** The chrome with a selection's title and its overflow, the five tabs,
 * the All page's grid - seven rows across two days, so both a day heading and the 4-column arithmetic
 * from `R.dimen.browser_media_size` are visible - the mime icons on the theme's
 * `surfaceContainerHighest`, the `#333333` box a row draws while its decode is out (the fixture has no
 * bitmap), and the selection overlay and its trailing check on the fourth row. A cell that loses the
 * spanning heading, the icon tint or the overlay moves a pixel.
 *
 * <p>**Why the chrome is in them.** The screen draws no bar and no background of its own - the
 * `Scaffold` inside [TulkkiChrome] paints it - so composing the body alone would render a transparent
 * picture. The `previews` stub is what keeps the cell off `FileBackends`.
 */
@PreviewTest
@Preview(
    name = "media-browser-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MediaBrowserDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "media-browser-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MediaBrowserLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val attachments =
        remember {
            listOf(
                FixtureAttachment("image/png", 1, thumbnail = true),
                FixtureAttachment("image/jpeg", 2, thumbnail = true),
                FixtureAttachment("application/pdf", 3),
                FixtureAttachment("video/mp4", 4, FIXTURE_DAY + 3_600_000L),
                FixtureAttachment("audio/mpeg", 5, FIXTURE_NEXT_DAY),
                FixtureAttachment("application/zip", 6, FIXTURE_NEXT_DAY),
                FixtureAttachment("text/plain", 7, FIXTURE_NEXT_DAY),
            )
        }
    // One selected row, which is the state the chrome's title and overflow are chosen for.
    val selected = remember(attachments) { setOf(attachments[3]) }
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = selected.size.toString(),
            onUp = {},
            menu = listOf(ChromeMenuItem(stringResource(R.string.search_by_date)) {}),
        ) {
            MediaBrowserScreen(
                attachments = attachments,
                selected = selected,
                onOpen = {},
                onToggleSelection = {},
                onDeleteFile = {},
                deleteFileLabel = stringResource(R.string.delete_file),
                previews = { _: AttachmentRef, _: Int -> null },
            )
        }
    }
}
