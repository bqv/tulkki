package uk.xa0.tulkki.ui.startchat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.EnterJidDialogScreen
import uk.xa0.tulkki.ui.EnterJidState
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The JID dialog's screenshot cells, in both themes (docs/MIGRATION.md "Design: the Compose UI"
 * §7.1/§7.2).
 *
 * <p>Each cell composes the dialog where its host composes it - over a filled background, centered,
 * because a dialog over a host is centered over the host's screen - and pins the face the deleted
 * `dialog_enter_jid.xml` drew for `StartConversationActivity`: the account dropdown, the gateway row
 * the async list fills (`Jabber ID`, a phone gateway and a typed one), the address field with its
 * label, the save-as-contact checkbox, and the three buttons the builder carried - `Write`,
 * `Call`/`Browse` and `Cancel`, the dynamic set the fragment relabelled on a warning.
 *
 * <p>**The dialog is an `AlertDialog`**, so the cell draws it in its own window above the background
 * it is given. A cell that comes back as the bare background is the harness not drawing a Dialog
 * window, not a missing dialog.
 */
@PreviewTest
@Preview(
    name = "enter-jid-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun EnterJidDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "enter-jid-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun EnterJidLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EnterJidDialogScreen(
                    state =
                        EnterJidState(
                            title = stringResource(R.string.start_chat),
                            jid = "juliet@capulet.example",
                            jidLabel = stringResource(R.string.account_settings_jabber_id),
                            jidPlaceholder =
                                stringResource(R.string.account_settings_example_jabber_id),
                            domains = emptyList(),
                            readOnly = false,
                            keyboardType = KeyboardType.Email,
                            accounts = listOf("owner@example.com"),
                            selectedAccount = "owner@example.com",
                            gatewayChips =
                                listOf(
                                    stringResource(R.string.account_settings_jabber_id),
                                    "📞",
                                    "SMS",
                                ),
                            selectedGateway = 0,
                            error = null,
                            helper = null,
                            primaryLabel = stringResource(R.string.write),
                            secondaryLabel = stringResource(R.string.call),
                            hasSecondary = true,
                            neutralLabel = stringResource(uk.xa0.tulkki.data.R.string.cancel),
                            showBookmark = true,
                            bookmark = true,
                            dismissRequested = false,
                        ),
                    onDismiss = {},
                    onAccountSelected = {},
                    onJidChange = {},
                    onGatewaySelected = {},
                    onBookmarkChange = {},
                    onSubmitPrimary = {},
                    onSubmitSecondary = {},
                )
            }
        }
    }
}
