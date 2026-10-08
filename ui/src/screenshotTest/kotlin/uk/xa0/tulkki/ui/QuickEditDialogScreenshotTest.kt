package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The one-field editor's screenshot cells, in both themes (`docs/MIGRATION.md` "Design: the Compose
 * UI" §7.1/§7.2).
 *
 * <p>Each cell composes the dialog over a filled background, centred, because a dialog over a host
 * is centred, and pins the face `:data`'s deleted `dialog_quickedit.xml` drew: a labelled single-line
 * field with the Accept and the Cancel, and - for the moderator's dialog - the question above the
 * field prefilled with "spam" and the Yes/No pair. The callback is the screen's and is a no-op here,
 * so no cell answers with an error and none presses a button.
 *
 * <p>Both are `AlertDialog`s, so a cell draws the dialog in its own window above the background it
 * is given. A cell that comes back as the bare background is the harness not drawing a `Dialog`
 * window, not a missing dialog.
 */
@PreviewTest
@Preview(
    name = "quick-edit-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun QuickEditDarkScreenshot() = Fixture(darkTheme = true) { QuickEditCell() }

@PreviewTest
@Preview(
    name = "quick-edit-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun QuickEditLightScreenshot() = Fixture(darkTheme = false) { QuickEditCell() }

@PreviewTest
@Preview(
    name = "moderate-recent-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ModerateRecentDarkScreenshot() = Fixture(darkTheme = true) { ModerateRecentCell() }

@PreviewTest
@Preview(
    name = "moderate-recent-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ModerateRecentLightScreenshot() = Fixture(darkTheme = false) { ModerateRecentCell() }

@Composable
private fun QuickEditCell() {
    QuickEditDialog(
        previousValue = "Tulkki",
        hintRes = R.string.your_name,
        password = false,
        permitEmpty = false,
        alwaysCallback = true,
        startSelected = true,
        onDismiss = {},
        onValueEdited = { null },
    )
}

@Composable
private fun ModerateRecentCell() {
    ModerateRecentDialog(onDismiss = {}, onModerate = {})
}

@Composable
private fun Fixture(darkTheme: Boolean, dialog: @Composable () -> Unit) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { dialog() }
        }
    }
}
