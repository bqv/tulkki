package uk.xa0.tulkki.ui.media

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The media viewer's screenshot cells - the whole screen
 * `uk.xa0.tulkki.ui.media.MediaViewerActivity` composes, in both themes.
 *
 * <p>**What the two cells pin.** The black fullscreen surface, the `black87` scrim an open dial puts
 * over it, and the action stack: Delete, Open with, Share and Save to Downloads with their labels, and
 * the main button in its cancel state. That is everything the screen owns; the page between them is the
 * Activity's own `PhotoView`/`PlayerView`, which are Android views and cannot be drawn here, so the
 * fixture hands an empty page in - the same split `TopUpScreen`'s `page` slot makes.
 *
 * <p>The viewer is dark in production and takes the stored theme like the rest of the app; both cells
 * are here because the stack's own colours (`?attr/colorAccent` and white) and the theme's surfaces
 * around it differ between them.
 */
@PreviewTest
@Preview(
    name = "media-viewer-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MediaViewerDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "media-viewer-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MediaViewerLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        MediaViewerScreen(
            pageCount = 1,
            initialPage = 0,
            actionsVisible = true,
            canDelete = true,
            onPageChange = {},
            onToggleActions = {},
            onDelete = {},
            onOpen = {},
            onShare = {},
            onSave = {},
            // The dial is closed in the running app until the main button is tapped; the cell opens it
            // so the four items and their labels are pinned.
            initiallyExpanded = true,
        ) { _, _ ->
            Box(Modifier.fillMaxSize())
        }
    }
}
