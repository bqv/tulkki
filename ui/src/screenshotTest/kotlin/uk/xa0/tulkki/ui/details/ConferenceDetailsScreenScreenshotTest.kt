package uk.xa0.tulkki.ui.details

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The group-chat / channel details screen's screenshot cells - the screen
 * `ConferenceDetailsActivity` composes, in both themes (docs/MIGRATION.md "Design: the Compose UI"
 * §7.1/§7.2, the harness the chrome and the other converted screens use).
 *
 * <p>**What the display cells pin.** The whole visible body of the deleted `activity_muc_details.xml`,
 * drawn from a fixture state: the bar with this screen's own title and its up arrow, the room name
 * with its tag pills and its edit button, the leave/add/destroy buttons, the conference-type row
 * with its settings button, the server-info row, the browse button and the two JID lines, the
 * participants block with its "view n participants" button, the own-settings block with the nick,
 * the role, the notification row and the ephemeral duration picker, the account line, and the ruled
 * media block.
 *
 * <p>**What the editing cells pin.** The open group editor and with it the Compose tag editor the
 * screen draws itself now: the deleted `TagEditorView`'s tokens are a `FlowRow` of pills and its
 * field is Compose, so the pills, their harmonised tints and the hint are in the picture beside the
 * name and topic fields.
 *
 * <p>**What the cells deliberately leave out.** The two surfaces the screen keeps as views - the
 * room avatar and the media grid - are passed as `null`, and the subject line (a `TextView`
 * carrying `StylingHelper`'s decoration and `MyLinkify`'s own spans) likewise, so the cell holds no
 * `AndroidView` (the `PostsAvatar` precedent: a picture of a hosted view pins the host, not this
 * file). The subject is still non-`null` in the fixture, because that is what decides whether the
 * slot is drawn at all. The participants grid is Compose now and is left empty here, so the block's
 * buttons are pinned without an avatar in the picture. Everything else in the state is the real
 * thing, so a colour, a size, a section rule or a string that moved moves a pixel here.
 */
@PreviewTest
@Preview(
    name = "conference-details-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 1400,
)
@Composable
fun ConferenceDetailsDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "conference-details-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 1400,
)
@Composable
fun ConferenceDetailsLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.action_muc_details),
            onUp = {},
            menu = listOf(ChromeMenuItem("Advanced mode") {}),
        ) {
            ConferenceDetailsScreen(
                state = sampleState(),
                events = noopEvents(),
                avatar = null,
                subjectLine = null,
                tagEditor = null,
                users = emptyList(),
                userRow = { ConferenceUserRow("", null, null) },
                onUserOpen = {},
                onUserMenu = {},
                userMenu = null,
                onUserMenuDismiss = {},
                onUserMenuSelected = { _ -> },
                mediaGrid = null,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "conference-details-editing-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ConferenceDetailsEditingDarkScreenshot() = EditingFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "conference-details-editing-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ConferenceDetailsEditingLightScreenshot() = EditingFixture(darkTheme = false)

/**
 * The group editor's own cells, where the Compose tag editor stands in for the deleted
 * `TagEditorView` the old `addTextChangedListener` sat on: the name and topic fields and the group's
 * pills, in both themes.
 */
@Composable
private fun EditingFixture(darkTheme: Boolean) {
    val tagEditor = remember { TagEditorState() }
    tagEditor.hint = stringResource(R.string.details_tags_hint)
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.action_muc_details), onUp = {}) {
            ConferenceDetailsScreen(
                state =
                    ConferenceDetailsState(
                        editButtonVisible = true,
                        title = "Example Room",
                        editorVisible = true,
                        editName = "Example Room",
                        editSubject = "A topic the room carries",
                        editorTagsVisible = true,
                        editorButtonIconRes = R.drawable.ic_cancel_24dp,
                        editorButtonDescriptionRes = uk.xa0.tulkki.data.R.string.cancel,
                    ),
                events = noopEvents(),
                avatar = null,
                subjectLine = null,
                tagEditor = tagEditor.apply {
                    addObjectSync(ListItem.Tag("Friends"))
                    addObjectSync(ListItem.Tag("Work"))
                },
                users = emptyList(),
                userRow = { ConferenceUserRow("", null, null) },
                onUserOpen = {},
                onUserMenu = {},
                userMenu = null,
                onUserMenuDismiss = {},
                onUserMenuSelected = { _ -> },
                mediaGrid = null,
            )
        }
    }
}

/** The room the deleted `updateView` would have filled: real resources, real container colours. */
@Composable
private fun sampleState(): ConferenceDetailsState =
    ConferenceDetailsState(
        editButtonVisible = true,
        title = "Example Room",
        subject = "A topic the room carries",
        tags =
            listOf(
                TagChip("Friends", Color(0xFF2E7D32).toArgb()),
                TagChip("Work", Color(0xFF1565C0).toArgb()),
            ),
        leaveLabelRes = R.string.action_end_conversation_muc,
        leaveContainerColor = Color(0xFFB3261E).toArgb(),
        addLabelRes = R.string.save_as_bookmark,
        addContainerColor = Color(0xFFF5F5F5).toArgb(),
        destroyLabelRes = R.string.destroy_room,
        destroyContainerColor = Color(0xFFB3261E).toArgb(),
        settingsVisible = true,
        changeConferenceVisible = true,
        conferenceType = "Members-only, moderated",
        infoMoreVisible = true,
        mamTextRes = R.string.server_info_available,
        jid = "hosted on example.org",
        trueJid = "room@conference.example.org",
        usersVisible = true,
        inviteVisible = true,
        showUsersVisible = true,
        showUsersLabel = "View 3 participants",
        nick = "owner",
        role = "Owner",
        notificationTextRes = R.string.notify_on_all_messages,
        notificationIconRes = R.drawable.ic_notifications_24dp,
        ephemeral =
            EphemeralRow(
                visible = true,
                enabled = true,
                durationVisible = true,
                entries = listOf("1 minute", "5 minutes", "1 hour", "1 day"),
                selectedIndex = 2,
            ),
        accountLine = stringResource(R.string.using_account, "owner@example.org"),
        mediaVisible = true,
        storeSecurely = false,
        showMediaVisible = true,
    )

private fun noopEvents(): ConferenceDetailsEvents =
    ConferenceDetailsEvents(
        onEditNameOrTopic = {},
        onLeave = {},
        onAddToContacts = {},
        onDestroy = {},
        onChangeConference = {},
        onBrowseSpace = {},
        onInvite = {},
        onShowUsers = {},
        onEditNick = {},
        onNotifications = {},
        onEphemeralToggled = {},
        onEphemeralDurationSelected = {},
        onThread = {},
        onStoreSecurelyChanged = {},
        onShowMedia = {},
        onEditNameChanged = {},
        onEditSubjectChanged = {},
        onShowAvatar = {},
        onBlockAvatar = {},
        onPhotoMenuDismiss = {},
    )
