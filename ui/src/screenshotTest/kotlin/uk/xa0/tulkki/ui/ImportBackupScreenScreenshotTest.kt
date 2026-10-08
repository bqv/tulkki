package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The restore-backup screen's screenshot cells - the screen [ImportBackupActivity] composes and the
 * password dialog it launches, in both themes (docs/MIGRATION.md "Design: the Compose UI"
 * §7.1/§7.2).
 *
 * <p>**What the cells pin.** The deleted `activity_import_backup.xml` drew a bar with this screen's
 * title, the up arrow and an "Open backup" item, and a `RecyclerView` of `item_account` rows; the
 * first two cells are that screen with two rows and the chrome's overflow item, which is where the
 * menu file's one live item went. The last two are the deleted `dialog_enter_password.xml` over it:
 * the address, the filled card with the OMEMO switch and its warning, the password field with its
 * eye, the second warning and the cancel/restore pair. The fixture rows carry fixed `app · date`
 * text, because the real status line is `DateUtils`' and its wording is the device's locale.
 *
 * <p>The avatars are the host's, so the cells pass none: the rows draw the coloured plate the
 * deleted adapter set before its worker landed, which is what the screen draws until `textAvatar`
 * answers.
 */
@PreviewTest
@Preview(
    name = "import-backup-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ImportBackupDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "import-backup-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ImportBackupLightScreenshot() = Fixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "import-backup-password-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ImportBackupPasswordDarkScreenshot() =
    Fixture(darkTheme = true, dialog = ImportBackupPasswordState(jid = Rows.first().jid))

@PreviewTest
@Preview(
    name = "import-backup-password-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ImportBackupPasswordLightScreenshot() =
    Fixture(darkTheme = false, dialog = ImportBackupPasswordState(jid = Rows.first().jid))

/** Two backup files, as `BackupFileAdapter` bound them. */
private val Rows =
    listOf(
        ImportBackupRow(key = "content://backups/juliet", jid = "juliet@example.com", status = "Tulkki · 12 August 2026"),
        ImportBackupRow(key = "content://backups/romeo", jid = "romeo@example.org", status = "Tulkki · 3 March 2025"),
    )

@Composable
private fun Fixture(darkTheme: Boolean, dialog: ImportBackupPasswordState? = null) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.restore_backup),
            onUp = {},
            menu = listOf(ChromeMenuItem(stringResource(R.string.open_backup)) {}),
        ) {
            ImportBackupScreen(
                state = ImportBackupState(rows = Rows),
                onOpen = {},
                onMessageShown = {},
                passwordDialog = dialog,
                avatar = { null },
            )
        }
    }
}
