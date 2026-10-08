package uk.xa0.tulkki.ui.conversation

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
 * The conversation's two Compose dialogs' screenshot cells, in both themes (`docs/MIGRATION.md`
 * "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>Each cell composes the dialog over a filled background, centered, because a dialog over a host
 * is centered - and pins the face the deleted `dialog_clear_history.xml` and
 * `dialog_tulkki_language_picker_header.xml` drew: the strings, the paragraph's warning run, the
 * checkbox, the two-ended header and the single-choice list. The click handlers and side effects are
 * the fragment's and are no-ops here.
 *
 * <p>The dialogs are `AlertDialog`s, so a cell draws the dialog in its own window above the
 * background it is given. A cell that comes back as the bare background is the harness not drawing a
 * Dialog window, not a missing dialog.
 */
@PreviewTest
@Preview(
    name = "clear-history-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ClearHistoryDarkScreenshot() = Fixture(darkTheme = true) {
    ClearHistoryDialog(onDismiss = {}, onConfirm = {})
}

@PreviewTest
@Preview(
    name = "clear-history-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ClearHistoryLightScreenshot() = Fixture(darkTheme = false) {
    ClearHistoryDialog(onDismiss = {}, onConfirm = {})
}

@PreviewTest
@Preview(
    name = "language-picker-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun LanguagePickerDarkScreenshot() = Fixture(darkTheme = true) { LanguagePickerCell() }

@PreviewTest
@Preview(
    name = "language-picker-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun LanguagePickerLightScreenshot() = Fixture(darkTheme = false) { LanguagePickerCell() }

@Composable
private fun LanguagePickerCell() {
    LanguagePickerDialog(
        appLanguage = "English",
        theyWrite = "Finnish (detected)",
        items = listOf("Detect automatically (now Finnish)", "Finnish", "Swedish", "German"),
        selectedIndex = 2,
        onDismiss = {},
        onChoose = {},
    )
}

@Composable
private fun Fixture(darkTheme: Boolean, dialog: @Composable () -> Unit) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { dialog() }
        }
    }
}
