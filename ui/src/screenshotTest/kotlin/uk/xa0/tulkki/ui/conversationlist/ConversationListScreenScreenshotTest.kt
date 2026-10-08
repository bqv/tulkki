package uk.xa0.tulkki.ui.conversationlist

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.CallKind
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.ConversationKind
import uk.xa0.tulkki.ui.projection.EncryptionKind
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.UiConversation
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiNotification
import uk.xa0.tulkki.ui.projection.UiPresence
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTranslationMode
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The conversation list's screenshot cells - `ui-8`'s gate, on the harness `ui-11` landed
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>§7.2 wants "**both themes for every screen** ... because light is the map nobody looks at", and
 * §3.5's own four states are the rest: the rows themselves (with a covered line, a typed encryption
 * line, a call, an unread group and a muted row), the first emission's skeleton, the explainer an empty
 * read draws, the interpreter-off rows that name no language, and the status line a reconnect adds.
 *
 * <p>The fixture is built from `UiConversation` values, which is what the screen is handed - the
 * projection that produces them is part 1's and part 2's, with their own cells - so a reference here is
 * a picture of the state and not of a hand-written row that could drift from it.
 */
@PreviewTest
@Preview(name = "list-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun ConversationListDarkScreenshot() = Fixture(darkTheme = true, state = fullState())

@PreviewTest
@Preview(name = "list-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun ConversationListLightScreenshot() = Fixture(darkTheme = false, state = fullState())

/** §3.5's loading state: three skeleton rows and no spinner. */
@PreviewTest
@Preview(name = "list-loading", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 520)
@Composable
fun ConversationListLoadingScreenshot() =
    Fixture(darkTheme = true, state = ConversationListState(null, ConversationFilter.ALL, UiConnection.CONNECTED, false))

/** §3.5's empty state: one explainer card, and no fake row. */
@PreviewTest
@Preview(name = "list-empty", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 520)
@Composable
fun ConversationListEmptyScreenshot() =
    Fixture(
        darkTheme = true,
        state = ConversationListState(emptyList(), ConversationFilter.ALL, UiConnection.CONNECTED, false),
    )

/** §3.5's interpreter-off state: ordinary one-liners, and no language named on a row. */
@PreviewTest
@Preview(name = "list-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 700)
@Composable
fun ConversationListOffScreenshot() =
    Fixture(
        darkTheme = true,
        state =
            ConversationListState(
                rows = listOf(row("a", preview = UiPreview.Visible("Hei, mitä kuuluu?"), translation = UiTranslationMode.OFF)),
                filter = ConversationFilter.ALL,
                connection = UiConnection.CONNECTED,
                archivedVisible = false,
            ),
    )

/** §3.5's error state: a status line above the rows that are still there, never an empty list. */
@PreviewTest
@Preview(name = "list-disconnected", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 700)
@Composable
fun ConversationListDisconnectedScreenshot() =
    Fixture(
        darkTheme = true,
        state =
            ConversationListState(
                rows = listOf(row("a", preview = UiPreview.Visible("Hei, mitä kuuluu?"))),
                filter = ConversationFilter.ALL,
                connection = UiConnection.DISCONNECTED,
                archivedVisible = false,
            ),
    )

@Composable
private fun Fixture(darkTheme: Boolean, state: ConversationListState) {
    TulkkiTheme(darkTheme = darkTheme) {
        ConversationListScreen(state = state, events = NoEvents)
    }
}

/** A host that does nothing: a reference is a picture of the rows, not of an effect being performed. */
private val NoEvents =
    object : ConversationListEvents {
        override fun onFilter(filter: ConversationFilter) = Unit

        override fun onOpen(conversationUuid: String) = Unit

        override fun onAction(action: ConversationAction, conversationUuid: String) = Unit
    }

/**
 * Rows with one line of every kind §3.5 draws, and every mark the deleted row carried - so a reference here
 * is evidence for the avatar, the clock, the sender, the attachment icon, the strip and the badge, and not
 * only for the words. Each row is chosen to make one of them visible.
 */
private fun fullState(): ConversationListState =
    ConversationListState(
        rows =
            listOf(
                row(
                    "a",
                    name = "Mikko",
                    preview = UiPreview.Visible("Hei! Oletko tulossa huomenna?"),
                    time = "23:42",
                    presence = UiPresence.ONLINE,
                    tick = R.drawable.ic_done_all_24dp,
                ),
                row(
                    "b",
                    name = "Study group",
                    preview = UiPreview.Visible("Kuva", icon = R.drawable.ic_image_48dp),
                    sender = "sari@example.org:",
                    unread = 3,
                    kind = ConversationKind.GROUP,
                    time = "12/11/2023",
                    presence = UiPresence.AWAY,
                    notification = UiNotification.SILENT,
                    account = "mikko@example.org",
                ),
                row(
                    "c",
                    name = "Sari",
                    preview = UiPreview.Encryption(EncryptionKind.OMEMO_DECRYPTION_FAILED),
                    time = "eilen",
                    presence = UiPresence.DND,
                    tick = R.drawable.ic_error_24dp,
                    pinned = true,
                ),
                row(
                    "d",
                    name = "Anna",
                    preview = UiPreview.Call(CallKind.MISSED),
                    muted = true,
                    time = "ma",
                    presence = UiPresence.OFFLINE,
                    notification = UiNotification.CALL,
                    unread = 1,
                ),
                row("e", name = "Old room", preview = UiPreview.Absent, archived = true),
            ),
        filter = ConversationFilter.ALL,
        connection = UiConnection.CONNECTED,
        archivedVisible = false,
    )

private fun row(
    id: String,
    name: String = id.uppercase(),
    preview: UiPreview = UiPreview.Absent,
    unread: Int = 0,
    muted: Boolean = false,
    archived: Boolean = false,
    kind: ConversationKind = ConversationKind.ONE_TO_ONE,
    pinned: Boolean = false,
    withSelf: Boolean = false,
    ongoingCall: Boolean = false,
    translation: UiTranslationMode = UiTranslationMode.ON,
    time: String = "",
    presence: UiPresence = UiPresence.UNKNOWN,
    sender: String? = null,
    tick: Int? = null,
    notification: UiNotification = UiNotification.NONE,
    account: String? = null,
): UiConversation =
    UiConversation(
        id = ConversationId(id),
        name = name,
        jid = "$id@example.org",
        lastMessageId = MessageId("m-$id"),
        lastMessageAt = 0L,
        preview = preview,
        unread = unread,
        muted = muted,
        archived = archived,
        kind = kind,
        pinned = pinned,
        withSelf = withSelf,
        ongoingCall = ongoingCall,
        time = time,
        presence = presence,
        sender = sender,
        tick = tick,
        notification = notification,
        account = account,
        language = UiLanguagePair(conversationLanguage = "de", appLanguage = "fi", overridden = false),
        translation = translation,
    )
