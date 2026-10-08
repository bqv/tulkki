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
 * The account screens' two Compose dialogs' screenshot cells, in both themes (`docs/MIGRATION.md`
 * "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>Each cell composes the dialog over a filled background, centred, because a dialog over a host
 * is centred, and pins the face its deleted layout drew: the confirmation paragraph with the
 * "remove account from server" box, and the warning sentence whose first argument - the contact's
 * address - `JidDialog.style` drew monospaced. The click handlers belong to the screens and are
 * no-ops here, which is also why the delete dialog's in-flight state is the dialog's own.
 *
 * <p>Both are `AlertDialog`s, so a cell draws the dialog in its own window above the background it
 * is given. A cell that comes back as the bare background is the harness not drawing a `Dialog`
 * window, not a missing dialog.
 */
@PreviewTest
@Preview(
    name = "delete-account-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun DeleteAccountDarkScreenshot() = Fixture(darkTheme = true) { DeleteAccountCell() }

@PreviewTest
@Preview(
    name = "delete-account-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun DeleteAccountLightScreenshot() = Fixture(darkTheme = false) { DeleteAccountCell() }

@PreviewTest
@Preview(
    name = "verify-fingerprints-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun VerifyFingerprintsDarkScreenshot() = Fixture(darkTheme = true) { VerifyFingerprintsCell() }

@PreviewTest
@Preview(
    name = "verify-fingerprints-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun VerifyFingerprintsLightScreenshot() = Fixture(darkTheme = false) { VerifyFingerprintsCell() }

@Composable
private fun DeleteAccountCell() {
    DeleteAccountDialog(
        titleRes = R.string.mgmt_account_delete,
        messageRes = R.string.mgmt_account_delete_confirm_text,
        checkboxRes = R.string.delete_from_server,
        confirmRes = R.string.delete,
        waitingRes = R.string.please_wait,
        onDismiss = {},
        onConfirm = {},
    )
}

@Composable
private fun VerifyFingerprintsCell() {
    VerifyFingerprintsDialog(
        warningRes = R.string.verifying_omemo_keys_trusted_source,
        confirmRes = R.string.confirm,
        warningArgs = listOf("juliet@example.com", "Juliet"),
        onDismiss = {},
        onConfirm = {},
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
