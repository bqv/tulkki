package uk.xa0.tulkki.ui.details

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.omemo.ContactKeyRow
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The one-to-one contact details screen's screenshot cells - the screen `ContactDetailsActivity`
 * composes, in both themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the
 * chrome and the other converted screens use).
 *
 * <p>**What the display cells pin.** The whole visible body of the deleted
 * `activity_contact_details.xml`, drawn from a fixture state: the title bar with this screen's own
 * title and its up arrow, the name and the JID, the tag pills, the last-seen line, the status
 * message and the client list, the two action buttons, the switches with the ephemeral duration
 * picker visible, the account line, and the ruled media and keys sections with their store-securely
 * switch, view-media button, unverified warning and scan/inactive-device buttons.
 *
 * <p>**What the editing cells pin.** The editor state, and with it the tag editor the screen draws
 * itself now: the deleted `TagEditorView`'s tokens are a `FlowRow` of pills and its field is
 * Compose, so the pills, their harmonised tints, the hint and the name field are all in the picture.
 *
 * <p>**What the cells deliberately leave out.** The two surfaces the screen keeps as views - the
 * avatar and its presence dot and the media grid - are passed as `null`, so the cell holds no
 * `AndroidView` (the `PostsAvatar` precedent: a picture of a hosted view pins the host, not this
 * file). The vCard profile rows are Compose now but the state leaves them empty, so the cell pins
 * the section rule without an icon; the OMEMO key rows are the Compose [ContactKeyRow]s now and are
 * left empty here, so the section's rule, warning and buttons are pinned but no reference image has
 * to be re-taken for the rows. The recent-thread rows are left empty for the same reason: their
 * identicon is a `GithubIdenticonView`. Everything else in the state is the real thing, so a colour,
 * a size, a section rule or a string that moved moves a pixel here.
 */
@PreviewTest
@Preview(
    name = "contact-details-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 1400,
)
@Composable
fun ContactDetailsDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "contact-details-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 1400,
)
@Composable
fun ContactDetailsLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.action_contact_details),
            onUp = {},
            menu = listOf(ChromeMenuItem("Share as URI") {}),
        ) {
            ContactDetailsScreen(
                state = sampleState(),
                events = noopEvents(),
                avatar = null,
                presence = null,
                tagEditor = null,
                keyRows = emptyList(),
                mediaGrid = null,
                onEditNameChanged = {},
                onEditDone = {},
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "contact-details-editing-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ContactDetailsEditingDarkScreenshot() = EditingFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "contact-details-editing-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ContactDetailsEditingLightScreenshot() = EditingFixture(darkTheme = false)

/**
 * The tag editor's own cells: the editing state, where the Compose chips and field stand in for the
 * deleted `TagEditorView`. The tags are the ones its `addObjectSync` loop seeded, so the pills, the
 * harmonised tints and the hint the old `edit_tags` carried are pinned in both themes.
 */
@Composable
private fun EditingFixture(darkTheme: Boolean) {
    val tagEditor = remember { TagEditorState() }
    tagEditor.hint = stringResource(R.string.details_tags_hint)
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.action_contact_details), onUp = {}) {
            ContactDetailsScreen(
                state =
                    ContactDetailsState(
                        editing = true,
                        tagsEditable = true,
                        name = "Example Contact",
                        jid = AnnotatedString("contact@example.org"),
                    ),
                events = noopEvents(),
                avatar = null,
                presence = null,
                tagEditor = tagEditor.apply {
                    addObjectSync(ListItem.Tag("Friends"))
                    addObjectSync(ListItem.Tag("Work"))
                },
                keyRows = emptyList(),
                mediaGrid = null,
                onEditNameChanged = {},
                onEditDone = {},
            )
        }
    }
}

/** The room the deleted `populateView` would have filled: real resources, real container colours. */
@Composable
private fun sampleState(): ContactDetailsState =
    ContactDetailsState(
        tags =
            listOf(
                TagChip("Friends", Color(0xFF2E7D32).toArgb()),
                TagChip("Work", Color(0xFF1565C0).toArgb()),
            ),
        name = "Example Contact",
        jid = AnnotatedString("contact@example.org"),
        accountLine = stringResource(R.string.using_account, "owner@example.org"),
        lastSeen = stringResource(R.string.just_now),
        statusMessage = "Hey there, this is a status message",
        clients = "Conversations\nDino\n",
        archiveLabelRes = R.string.action_archive_chat,
        archiveContainerColor = Color(0xFFB3261E).toArgb(),
        extraLabelRes = R.string.action_delete_contact,
        extraContainerColor = Color(0xFFB3261E).toArgb(),
        followFeedVisible = true,
        followFeedChecked = true,
        sendPresenceVisible = true,
        sendPresenceLabelRes = R.string.send_presence_updates,
        sendPresenceChecked = true,
        receivePresenceVisible = true,
        receivePresenceLabelRes = R.string.receive_presence_updates,
        receivePresenceChecked = true,
        presenceEnabled = true,
        callsDisabled = false,
        ephemeral =
            EphemeralRow(
                visible = true,
                enabled = true,
                durationVisible = true,
                entries = listOf("1 minute", "5 minutes", "1 hour", "1 day"),
                selectedIndex = 1,
            ),
        mediaVisible = true,
        storeSecurely = true,
        showMediaVisible = true,
        keysVisible = true,
        unverifiedVisible = true,
        scanVisible = true,
        inactiveLabelRes = R.string.show_inactive_devices,
        editVisible = true,
        unblockVisible = true,
        customNotificationsVisible = true,
    )

private fun noopEvents(): ContactDetailsEvents =
    ContactDetailsEvents(
        onArchive = {},
        onExtraButton = {},
        onFollowFeedChanged = {},
        onSendPresenceChanged = {},
        onReceivePresenceChanged = {},
        onCallsDisabledChanged = {},
        onEphemeralToggled = {},
        onEphemeralDurationSelected = {},
        onThread = {},
        onProfileRowClick = {},
        onProfileRowLongClick = {},
        onStoreSecurelyChanged = {},
        onShowMedia = {},
        onScan = {},
        onToggleInactiveDevices = {},
    )
