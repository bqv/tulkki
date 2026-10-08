package uk.xa0.tulkki.ui.stories

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The story viewer's screenshot cells - the screen `StoryViewActivity` composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the conversation
 * screen use).
 *
 * <p>**What the two cells pin.** The whole visible screen: the bar with the author's name and the
 * reply/delete overflow items, the black body, the progress row (one page, its bar at zero), the
 * still's spinner - the fixture's `loadImage` answers `null`, which is exactly the state the deleted
 * `ImageView` showed while Glide fetched - and the panel with the published time over the story
 * title. The story's own pixels are the image library's and are never in the cell, the same argument
 * the top-up screen's cells make about its WebView.
 *
 * <p>**Why the timer is not in them.** The still's six seconds start only when the image is in hand,
 * which the fixture never hands over, so the cell is a single frame and not a running animation; a
 * reference taken from it cannot depend on when the harness ran.
 */
@PreviewTest
@Preview(
    name = "story-view-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StoryViewDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "story-view-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StoryViewLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val session = remember { StoryViewerSession() }
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = "Mika",
            onUp = {},
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.reply)) {},
                    ChromeMenuItem(stringResource(R.string.delete_story)) {},
                ),
        ) {
            StoryViewerScreen(
                pages =
                    listOf(
                        StoryPage(
                            url = "https://example.invalid/story.jpg",
                            mimeType = "image/jpeg",
                            title = "A walk by the lake",
                        )
                    ),
                session = session,
                subtitle = "2 hours ago",
                loadImage = { null },
                video = {},
                onFinish = {},
            )
        }
    }
}
