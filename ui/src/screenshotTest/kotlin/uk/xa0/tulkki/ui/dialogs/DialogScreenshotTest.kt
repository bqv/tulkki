package uk.xa0.tulkki.ui.dialogs

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
import uk.xa0.tulkki.ui.BlockContactDialogBody
import uk.xa0.tulkki.ui.CreatePrivateGroupChatDialogScreen
import uk.xa0.tulkki.ui.CreatePublicChannelDialogScreen
import uk.xa0.tulkki.ui.JoinConferenceDialogScreen
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The four converted dialogs' screenshot cells, in both themes (docs/MIGRATION.md "Design: the
 * Compose UI" §7.1/§7.2).
 *
 * <p>Each cell composes the dialog where the host composes it - over a filled background, centered,
 * because a dialog over a host is centered over the host's screen - and pins the face the deleted
 * `dialog_*.xml` drew: the strings, the fields, and the buttons the builder carried. The click
 * handlers and the side effects are the host's and are no-ops here.
 *
 * <p>The dialogs are `AlertDialog`s, so the cell draws the dialog in its own window above the
 * background it is given. A cell that comes back as the bare background is the harness not drawing
 * a Dialog window, not a missing dialog.
 */
@PreviewTest
@Preview(
    name = "block-contact-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun BlockContactDarkScreenshot() = Fixture(darkTheme = true) { BlockContactCell() }

@PreviewTest
@Preview(
    name = "block-contact-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun BlockContactLightScreenshot() = Fixture(darkTheme = false) { BlockContactCell() }

@PreviewTest
@Preview(
    name = "create-private-group-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CreatePrivateGroupDarkScreenshot() = Fixture(darkTheme = true) { CreatePrivateGroupCell() }

@PreviewTest
@Preview(
    name = "create-private-group-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CreatePrivateGroupLightScreenshot() = Fixture(darkTheme = false) { CreatePrivateGroupCell() }

@PreviewTest
@Preview(
    name = "create-public-channel-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CreatePublicChannelDarkScreenshot() = Fixture(darkTheme = true) { CreatePublicChannelCell() }

@PreviewTest
@Preview(
    name = "create-public-channel-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CreatePublicChannelLightScreenshot() = Fixture(darkTheme = false) { CreatePublicChannelCell() }

@PreviewTest
@Preview(
    name = "join-conference-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun JoinConferenceDarkScreenshot() = Fixture(darkTheme = true) { JoinConferenceCell() }

@PreviewTest
@Preview(
    name = "join-conference-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun JoinConferenceLightScreenshot() = Fixture(darkTheme = false) { JoinConferenceCell() }

@Composable
private fun Fixture(darkTheme: Boolean, dialog: @Composable () -> Unit) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                dialog()
            }
        }
    }
}

@Composable
private fun BlockContactCell() {
    BlockContactDialogBody(
        titleRes = R.string.action_block_contact,
        textRes = R.string.block_contact_text,
        value = "spammer@example.com",
        showReportSpam = true,
        reportSpamChecked = false,
        reportSpamEnabled = true,
        confirmRes = R.string.block,
        onDismiss = {},
        onConfirm = {},
    )
}

@Composable
private fun CreatePrivateGroupCell() {
    CreatePrivateGroupChatDialogScreen(
        accounts = listOf("owner@example.com"),
        onDismiss = {},
        onChoose = { _, _ -> },
    )
}

@Composable
private fun CreatePublicChannelCell() {
    CreatePublicChannelDialogScreen(
        accounts = listOf("owner@example.com"),
        domains = listOf("conference.example.com"),
        onDismiss = {},
        onCreate = { _, _, _ -> },
    )
}

@Composable
private fun JoinConferenceCell() {
    JoinConferenceDialogScreen(
        accounts = listOf("owner@example.com"),
        domains = listOf("conference.example.com"),
        prefilledJid = "room@conference.example.com",
        prefilledPassword = null,
        onDismiss = {},
        onJoin = { _, _, _ -> },
    )
}
