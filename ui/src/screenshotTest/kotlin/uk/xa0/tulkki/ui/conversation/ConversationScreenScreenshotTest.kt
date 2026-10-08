package uk.xa0.tulkki.ui.conversation

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.LanguageCheck
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.conversationlist.UiConnection
import uk.xa0.tulkki.ui.encryption.EncryptionChoice
import uk.xa0.tulkki.ui.encryption.EncryptionOption
import uk.xa0.tulkki.ui.encryption.EncryptionSelectionState
import uk.xa0.tulkki.ui.projection.AttachmentKind
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.MessageType
import uk.xa0.tulkki.ui.projection.PlaceholderShape
import uk.xa0.tulkki.ui.projection.UiAttachment
import uk.xa0.tulkki.ui.projection.UiBody
import uk.xa0.tulkki.ui.projection.UiConcealment
import uk.xa0.tulkki.ui.projection.UiDeliveryState
import uk.xa0.tulkki.ui.projection.UiDoubtHold
import uk.xa0.tulkki.ui.projection.UiEncryption
import uk.xa0.tulkki.ui.projection.UiEnglishRow
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiMessage
import uk.xa0.tulkki.ui.projection.UiOriginalRow
import uk.xa0.tulkki.ui.projection.UiQuote
import uk.xa0.tulkki.ui.projection.UiReaction
import uk.xa0.tulkki.ui.projection.UiRunFlags
import uk.xa0.tulkki.ui.projection.UiTransferState
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The conversation view's screenshot cells - `ui-9` slice 2c's gate on the harness `ui-11` landed
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>§7.2 wants "**both themes for every screen** ... because light is the map nobody looks at", so
 * the first two cells are the same conversation in dark and light. The rest are §3.6's own states and
 * the concealment exceptions: a row covered for want of a key, a row covered because the cap is
 * spent, the interpreter-off shape (single halves, no cover, no English row), §4.6's explainer and
 * §3.6's loading row.
 *
 * <p>The fixture is built from `UiMessage` values, which is what the screen is handed - the projector
 * that produces them has its own cells - so a reference here is a picture of the state and not of a
 * hand-written row that could drift from it. The clock is the fixture's ([NOW]) for §4.3's reason:
 * a screen that read a clock of its own could not be asked what it says, and these references would
 * drift with the calendar.
 */
@PreviewTest
@Preview(name = "chat-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun ConversationDarkScreenshot() = Fixture(darkTheme = true, state = fullState())

@PreviewTest
@Preview(name = "chat-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun ConversationLightScreenshot() = Fixture(darkTheme = false, state = fullState())

/**
 * The conversation's own lines and its resting bar, in the dark theme: the room's subject - with a web
 * address in it, which is the `autoLink="web"` the surface keeps - the tune the peer is listening to,
 * the ephemeral hint, and the resting bar's sentence with its one action.
 *
 * <p>Every one of those is absent from an ordinary conversation, so a cell that never draws them
 * cannot tell whether they are drawn at all: this is the picture of the states the deleted
 * `muc_subject`/`tune_subject`/`ephemeral_hint` rows and the `snackbar` `RelativeLayout` reached. The
 * icon tint is the presence colour the host resolves; a cell has no presence to read, so it pins one.
 */
@PreviewTest
@Preview(name = "chat-bars", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 720)
@Composable
fun ConversationBarsScreenshot() =
    Fixture(darkTheme = true, state = fullState(), header = BARS, snackbar = BAR)

/** The same picture in the light theme: the theme's own `onSurface` and inverse plate. */
@PreviewTest
@Preview(name = "chat-bars-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 720)
@Composable
fun ConversationBarsLightScreenshot() =
    Fixture(darkTheme = false, state = fullState(), header = BARS, snackbar = BAR)

/** §6's cover with a reason that is not a tap: the shimmer stays, and the reason is said under it. */
@PreviewTest
@Preview(name = "chat-covered", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationCoveredScreenshot() = Fixture(darkTheme = true, state = coveredState())

/**
 * The composer in full: a draft, the two-tier chip, the reason a send is held and the reply preview -
 * with the account disconnected, so §3.6's status line is in the same picture.
 */
@PreviewTest
@Preview(name = "chat-composer", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationComposerScreenshot() = Fixture(darkTheme = true, state = composerState())

/**
 * The regression's own cell, and the one width in this file that is chosen rather than inherited: the
 * composer on a 360 dp window - the owner's "mate. where do i type" - with item 16's hold in force,
 * the two-tier pair drawn and the lock offered, so every fixed child the input row can hold is
 * present at once. The draft is empty, so the hint is the evidence: it is drawn on one line, in a
 * field that is a field and not a sliver.
 *
 * <p>The narrow width is the point, because a `Row` measures its fixed children first and hands the
 * weighted draft what is left, so the field's room is a function of the window and not of the words.
 * The language block and the hold chip are the two widest fixed children the bar has; in the input row
 * at this width they left the draft 2 dp, which is why they now draw in the composer's own control row
 * above the field - and this is the cell that pins it, since a source cell can pin the placement and
 * never the dp.
 */
@PreviewTest
@Preview(
    name = "chat-composer-narrow",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 360,
    heightDp = 620,
)
@Composable
fun ConversationComposerNarrowScreenshot() = Fixture(darkTheme = true, state = narrowComposerState())

/**
 * The text-formatting bar, drawn from `UiComposer.formatting`: the four markers and the two Java
 * draft verbs (`/me`, `> `) under the field, with a selection in the draft so the bar is shown over
 * something a marker can act on. It is the surface that was written and callerless until the wiring,
 * so the reference is what "wired" looks like.
 */
@PreviewTest
@Preview(name = "chat-formatting", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationFormattingScreenshot() = Fixture(darkTheme = true, state = formattingState())

/**
 * The composer with two files staged: `UiComposer.attachments`, drawn as the pending strip, is the
 * Java `media_preview` list and the half of the attachment surface the composer owns. One is an image
 * with no pixels - a kind's plate, which is exactly what an un-hosted build draws - and one is a
 * file, so the cell pins both branches of the one decision the strip makes. The draft is empty on
 * purpose: the send button is live because a staged file is something to send on its own, caption or
 * not.
 */
@PreviewTest
@Preview(
    name = "chat-composer-attachments",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 480,
)
@Composable
fun ConversationComposerAttachmentsScreenshot() =
    Fixture(darkTheme = true, state = attachmentComposerState())

/**
 * Item 16's switch in its override position, and the doubt hold it governs: the owner turned the hold
 * off for this room, and the held send's bar carries the doubtful-language kind's own sentence.
 */
@PreviewTest
@Preview(name = "chat-doubt-hold", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationDoubtHoldScreenshot() = Fixture(darkTheme = true, state = doubtHoldState())

/**
 * Item 17's banner: the conversation has no language yet and a key is set, so the line offers the
 * picker. It is drawn in the composer's own area, which is where the tree's bar lived.
 */
@PreviewTest
@Preview(name = "chat-banner", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationBannerScreenshot() = Fixture(darkTheme = true, state = bannerState())

/**
 * Item 17's decision five, after the owner's tap: the translation genuinely failed, so the cover still
 * shimmers over it and the original underneath is the row's **own** readable half - the one path that
 * makes somebody else's original readable, and only because this row's failure is terminal.
 */
@PreviewTest
@Preview(name = "chat-revealed", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationRevealedScreenshot() = Fixture(darkTheme = true, state = revealedState())

/**
 * §3.6's reaction row, which `UiReaction` carried and nothing drew: an incoming row with two chips -
 * one of the owner's own, filled, and one only others' - and an own row whose chip is a single
 * reaction. The cell exists because the validator proves a *drawn* surface: a chip the projector
 * produces and the screen drops is exactly the shape this reference pins.
 */
@PreviewTest
@Preview(name = "chat-reactions", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationReactionsScreenshot() = Fixture(darkTheme = true, state = reactionsState())

/**
 * §3.6's attachment cell, which `UiMessage` had no field for: a file downloading (in flight), a file
 * and an image ready, and a transfer that failed. The ready image draws the named plate because no
 * host has built its pixels in this fixture - which is exactly what an un-hosted build draws, so the
 * reference is honest about the host half still being a seam.
 */
@PreviewTest
@Preview(name = "chat-attachment", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 720)
@Composable
fun ConversationAttachmentScreenshot() = Fixture(darkTheme = true, state = attachmentState())

/** The interpreter off: one half per message, no cover, no shimmer and no English row. */
@PreviewTest
@Preview(name = "chat-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun ConversationOffScreenshot() = Fixture(darkTheme = true, state = offState())

/** §4.6: the read landed and there is nothing, so the explainer is drawn and no row is invented. */
@PreviewTest
@Preview(name = "chat-empty", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 520)
@Composable
fun ConversationEmptyScreenshot() =
    Fixture(darkTheme = true, state = ChatState(emptyList(), emptySet(), emptySet(), null, true))

/** §3.6: history is still being read, so a top-anchored progress row rather than a blank list. */
@PreviewTest
@Preview(name = "chat-loading", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 520)
@Composable
fun ConversationLoadingScreenshot() =
    Fixture(darkTheme = true, state = ChatState(null, emptySet(), emptySet(), null, true))

@Composable
private fun Fixture(
    darkTheme: Boolean,
    state: ChatState,
    header: UiConversationHeader = UiConversationHeader(),
    snackbar: UiSnackbar? = null,
) {
    TulkkiTheme(darkTheme = darkTheme) {
        ConversationScreen(
            state = state,
            events = NoEvents,
            now = NOW,
            avatarsOn = true,
            header = header,
            snackbar = snackbar,
        )
    }
}

/**
 * The three lines and the resting bar the `chat-bars` cells draw: a room's subject with a web address
 * in it, the peer's tune, the ephemeral hint, and the bar's own sentence with the one action that
 * fixes it. The values are the surfaces' inputs, not a rule - the decisions that produce them are
 * `ConversationFragment.refreshTulkkiHeader`'s and `updateSnackBar`'s, which a JVM cell cannot reach.
 */
private val BARS =
    UiConversationHeader(
        subject = UiSubjectLine("Weekly call - notes at https://example.org/notes", room = true),
        // The sentences `user_tune_listening_to` and `ephemeral_messages_active_hint` render to, which
        // the host builds with `getString`: a cell hands the surface the wording it draws, exactly as
        // the fragment does.
        tune = UiTuneLine("Listening to Nightfall - Aurelia"),
        ephemeral = UiEphemeralLine("Ephemeral messages are active (1 day)"),
        iconTint = Color(0xFF2E7D32),
    )

/** The resting bar with an action: a blocked contact, which is the one sentence with a fix inline. */
private val BAR =
    UiSnackbar(
        words = R.string.contact_blocked,
        actionLabel = R.string.unblock,
        onAction = View.OnClickListener { },
    )

/** A host that does nothing: a reference is a picture of the rows, not of an effect being performed. */
private val NoEvents =
    object : ConversationEvents {
        override fun onBodyTap(messageUuid: String) = Unit

        override fun onEnglishTap(messageUuid: String) = Unit

        override fun onRevealOriginal(messageUuid: String) = Unit

        override fun onLongPress(messageUuid: String) = Unit

        override fun onReply(messageUuid: String) = Unit

        override fun onAvatarTap(messageUuid: String) = Unit

        override fun onAvatarLongPress(messageUuid: String) = Unit

        override fun onViewport(lastVisibleUuid: String?, atBottom: Boolean, atStart: Boolean) = Unit

        override fun onDraftChanged(draft: TextFieldValue) = Unit

        override fun onSend() = Unit

        override fun onDoubtHoldChanged(hold: Boolean) = Unit

        override fun onNoticeAction(action: NoticeAction) = Unit

        override fun onReaction(messageUuid: String, emoji: String) = Unit

        override fun onReactionPicker(messageUuid: String) = Unit
    }

/** A fixed instant, so every label in a reference is the same on every run. */
private const val NOW = 1791000000000L

/** §6.1's shape: two lines, the projection's own envelopes, and one message's seed. */
private fun shape(seed: Long = 7L): PlaceholderShape =
    PlaceholderShape(2, floatArrayOf(0.75f, 0.5f), seed)

private fun covered(reason: DisplayedBody.Cover?): UiBody =
    UiBody.Concealed(UiConcealment.Placeholder(shape(), reason))

/**
 * The conversation both theme cells draw: a merged incoming pair with the German original concealed
 * under each Finnish translation, an own row whose own second half is readable, a reply that carries
 * a quote of that own row, and a received row whose English is not bought yet.
 */
private fun fullState(): ChatState {
    val rows =
        listOf(
            row(
                "m1",
                time = NOW - 26 * 3_600_000,
                top = UiBody.Visible("Hei! Oletko tulossa huomenna?"),
                bottom = covered(null),
                divider = true,
                run = UiRunFlags(firstOfRun = true, lastOfRun = false, firstOfDay = true, showAvatar = true, showName = true),
            ),
            row(
                "m2",
                time = NOW - 26 * 3_600_000 + 10_000,
                top = UiBody.Visible("Otetaan mukaan se uusi peli, jonka mainitsit."),
                bottom = covered(null),
                divider = true,
                run = UiRunFlags(firstOfRun = false, lastOfRun = true, firstOfDay = false, showAvatar = false, showName = false),
            ),
            row(
                "m3",
                direction = Direction.OUTGOING,
                time = NOW - 420_000,
                top = UiBody.Visible("Kyllä, nähdään huomenna."),
                bottom = UiBody.Visible("Ja, bis morgen."),
                divider = true,
                delivery = UiDeliveryState.Delivered,
                run = UiRunFlags(firstOfRun = true, lastOfRun = true, firstOfDay = true, showAvatar = true, showName = false),
            ),
            row(
                "m4",
                time = NOW - 300_000,
                top = UiBody.Visible("Hyvä!"),
                quote =
                    UiQuote(
                        messageId = MessageId("m3"),
                        top = UiBody.Visible("Kyllä, nähdään huomenna."),
                        bottom = UiBody.Concealed(UiConcealment.Placeholder(shape(11L), null)),
                        divider = true,
                    ),
                run = UiRunFlags(firstOfRun = true, lastOfRun = false, firstOfDay = false, showAvatar = true, showName = true),
            ),
            row(
                "m5",
                time = NOW - 290_000,
                top = UiBody.Visible("Ja sitten vielä yksi asia."),
                english = UiEnglishRow.Pending,
                run = UiRunFlags(firstOfRun = false, lastOfRun = true, firstOfDay = false, showAvatar = false, showName = false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = 3,
        atBottom = true,
        composer = UiComposer(draft = TextFieldValue("Nähdään huomenna kello kuusi."), language = UiLanguagePair("de", "fi", false)),
    )
}

/** §17's two failure covers: no key and a spent cap. Both keep the shimmer and say why. */
private fun coveredState(): ChatState {
    val rows =
        listOf(
            row(
                "c1",
                time = NOW - 300_000,
                top = covered(DisplayedBody.Cover.NO_KEY),
                run = UiRunFlags(true, true, true, true, true),
            ),
            row(
                "c2",
                time = NOW - 120_000,
                top = covered(DisplayedBody.Cover.CAP_REACHED),
                canTranslateNow = false,
                run = UiRunFlags(true, true, false, true, true),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                draft = TextFieldValue(""),
                language = UiLanguagePair("de", "fi", false),
                held = UiHold.Reason(HeldSend.HoldReason.NO_KEY),
            ),
    )
}

private fun offState(): ChatState {
    val rows =
        listOf(
            row("o1", time = NOW - 300_000, top = UiBody.Visible("Hei!"), run = UiRunFlags(true, false, true, true, true)),
            row(
                "o2",
                time = NOW - 200_000,
                top = UiBody.Visible("Nähdään huomenna."),
                run = UiRunFlags(false, true, false, false, false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        // No language, so no chip at all: §2.12's interpreter-off composer.
        composer = UiComposer(draft = TextFieldValue("Moikka!")),
    )
}

/**
 * The composer's own cell: a reply preview above the field, a held send's reason in the bar, and the
 * account disconnected so §3.6's status line is drawn too.
 *
 * <p>The doubt-hold switch is drawn **on**, built the way a host builds it - `UiDoubtHold.of(null)` -
 * because this is a room the owner has never touched and the shipped default holds: the picture is of
 * a default, not of a stored `1`.
 */
private fun composerState(): ChatState {
    val reply =
        UiQuote(
            messageId = MessageId("m3"),
            top = UiBody.Visible("Kyllä, nähdään huomenna."),
            bottom = UiBody.Concealed(UiConcealment.Placeholder(shape(11L), null)),
            divider = true,
        )
    val rows =
        listOf(
            row(
                "m3",
                direction = Direction.OUTGOING,
                time = NOW - 420_000,
                top = UiBody.Visible("Kyllä, nähdään huomenna."),
                bottom = UiBody.Visible("Ja, bis morgen."),
                divider = true,
                delivery = UiDeliveryState.Delivered,
                run = UiRunFlags(true, true, true, true, false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                draft = TextFieldValue("Nähdään kello kuusi."),
                language = UiLanguagePair("de", "fi", false),
                doubtHold = UiDoubtHold.of(null),
                held = UiHold.Reason(HeldSend.HoldReason.NO_KEY),
                reply = reply,
            ),
        connection = UiConnection.DISCONNECTED,
    )
}

/**
 * [ConversationComposerNarrowScreenshot]'s composer: §3.6's full house in the bar - the two-tier pair,
 * the doubt hold in force and the lock an encrypted conversation offers - with an **empty** draft, so
 * what the cell shows is the hint the owner reported. `UiDoubtHold.of(null)` is a room the owner never
 * touched, which is the shipped default and therefore on; the lock is the one fixed child the 420 dp
 * composer cells never draw, and it is 48 dp the narrow row has to find room for.
 *
 * <p>No rows: the cell is the bar, and an empty list keeps the picture from arguing about bubbles.
 */
private fun narrowComposerState(): ChatState =
    ChatState(
        rows = emptyList(),
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                draft = TextFieldValue(""),
                language = UiLanguagePair("de", "fi", false),
                doubtHold = UiDoubtHold.of(null),
                encryption =
                    EncryptionSelectionState(
                        current = EncryptionChoice.OMEMO,
                        options =
                            listOf(
                                EncryptionOption(EncryptionChoice.NONE),
                                EncryptionOption(EncryptionChoice.OMEMO),
                            ),
                    ),
            ),
    )

/**
 * The formatting bar's own cell: a selection inside the draft, so the bar is drawn over words a
 * marker can wrap - the state `showTextFormat` turns on when the IME is up and the preference is set.
 */
private fun formattingState(): ChatState =
    ChatState(
        rows = emptyList(),
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                draft = TextFieldValue("Nähdään huomenna kello kuusi."),
                language = UiLanguagePair("de", "fi", false),
                formatting = true,
            ),
    )

/**
 * The staged-attachment composer: an image the host has no pixels for and a file, and no draft at
 * all, so the strip alone is what the send would carry. Nothing here names the files - a staged
 * attachment has no name to draw - so the picture is the two kind plates and their remove corners.
 */
private fun attachmentComposerState(): ChatState =
    ChatState(
        rows = emptyList(),
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                attachments =
                    listOf(
                        UiPendingAttachment(id = "a1", kind = AttachmentKind.IMAGE),
                        UiPendingAttachment(id = "a2", kind = AttachmentKind.FILE),
                    ),
            ),
    )

/**
 * Item 16's switch in its override position, with the hold it governs in the same picture: the owner
 * turned the doubt hold off for this room, and the send that was already held on doubt is still in the
 * bar with **the kind's own sentence** - the doubtful-language kind, whose tap sends the stored answer
 * as it stands. It is the one picture that shows both halves of the surface at once: the switch the
 * owner moved, and the sentence the send path produced rather than the screen re-deciding.
 */
private fun doubtHoldState(): ChatState {
    val rows =
        listOf(
            row(
                "d1",
                direction = Direction.OUTGOING,
                time = NOW - 300_000,
                top = UiBody.Visible("Nähdään huomenna kello kuusi."),
                run = UiRunFlags(true, true, true, true, false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer =
            UiComposer(
                draft = TextFieldValue(""),
                language = UiLanguagePair("de", "fi", false),
                doubtHold = UiDoubtHold.of(false),
                held = UiHold.Doubt(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE),
            ),
    )
}


/**
 * A received row whose translation genuinely failed and whose original the owner has revealed: the
 * shape `MessageProjection` answers once the gate offers the strip and the tap takes it.
 */
private fun revealedState(): ChatState {
    val rows =
        listOf(
            row(
                "r1",
                time = NOW - 120_000,
                top = covered(DisplayedBody.Cover.FAILED),
                original = UiOriginalRow.Concealed(UiConcealment.Placeholder(shape(), null, revealable = true)),
                run = UiRunFlags(true, false, true, true, true),
            ),
            row(
                "r2",
                time = NOW - 60_000,
                top = covered(DisplayedBody.Cover.FAILED),
                original = UiOriginalRow.Visible("Die Antwort ist zweiundvierzig."),
                run = UiRunFlags(false, true, false, false, false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        revealedOriginals = setOf(MessageId("r1")),
        composer = UiComposer(draft = TextFieldValue(""), language = UiLanguagePair("de", "fi", false)),
    )
}

/**
 * A conversation with a known key and no detected language: [ConversationNotice]'s one banner that has
 * something to offer, so the cell is the banner and the action together.
 */
private fun bannerState(): ChatState {
    val rows =
        listOf(
            row("b1", time = NOW - 120_000, top = UiBody.Visible("Hei!"), run = UiRunFlags(true, true, true, true, true)),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        notices = listOfNotNull(ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = true)),
        composer = UiComposer(draft = TextFieldValue(""), language = UiLanguagePair(null, "fi", false)),
    )
}

/**
 * Two rows with chips: the incoming one carries a group of two the owner is part of and a single
 * group only others reacted with, so both chip fills are in the picture; the own one carries a
 * single chip for the count-suppression branch. Nothing else is on the rows, so the cell is the row.
 */
private fun reactionsState(): ChatState {
    val rows =
        listOf(
            row(
                "x1",
                time = NOW - 120_000,
                top = UiBody.Visible("Hyvä idea!"),
                reactions =
                    listOf(
                        UiReaction("\uD83D\uDC4D", 2, true),
                        UiReaction("\uD83C\uDF89", 1, false),
                    ),
                run = UiRunFlags(true, true, true, true, true),
            ),
            row(
                "x2",
                direction = Direction.OUTGOING,
                time = NOW - 60_000,
                top = UiBody.Visible("Sovittu."),
                delivery = UiDeliveryState.Delivered,
                reactions = listOf(UiReaction("\u2764\uFE0F", 1, true)),
                run = UiRunFlags(true, true, false, true, false),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer = UiComposer(draft = TextFieldValue(""), language = UiLanguagePair("de", "fi", false)),
    )
}

/**
 * The cell's four states, on five rows because ready is shown twice (a file and an image whose host
 * built no pixels): one file downloading (in flight), a file and an image ready, a transfer that
 * failed, and a transfer with nothing on the phone. That last row is the fourth state and the one the
 * defect hid best - `None` inside this cell means "received but not downloaded", never "not a transfer"
 * - so a reference without it would not pin it apart from the empty bubble it replaced.
 */
private fun attachmentState(): ChatState {
    val rows =
        listOf(
            row(
                "t1",
                time = NOW - 300_000,
                type = MessageType.TRANSFER,
                top = UiBody.Absent,
                attachment =
                    UiAttachment(
                        kind = AttachmentKind.FILE,
                        name = "receipt.pdf",
                        sizeBytes = 204_800L,
                        localUri = null,
                        remoteUri = "https://example.org/receipt.pdf",
                        thumbnail = null,
                        state = UiTransferState.Downloading(37, 204_800L),
                    ),
                run = UiRunFlags(true, false, true, true, true),
            ),
            row(
                "t2",
                time = NOW - 200_000,
                type = MessageType.TRANSFER,
                top = UiBody.Absent,
                attachment =
                    UiAttachment(
                        kind = AttachmentKind.FILE,
                        name = "notes.txt",
                        sizeBytes = 4_096L,
                        localUri = "file:///phone/notes.txt",
                        remoteUri = null,
                        thumbnail = null,
                        state = UiTransferState.Ready("file:///phone/notes.txt"),
                    ),
                run = UiRunFlags(false, false, false, false, false),
            ),
            row(
                "t3",
                time = NOW - 100_000,
                type = MessageType.TRANSFER,
                top = UiBody.Absent,
                attachment =
                    UiAttachment(
                        kind = AttachmentKind.IMAGE,
                        name = "holiday.jpg",
                        sizeBytes = 2_000_000L,
                        localUri = "file:///phone/holiday.jpg",
                        remoteUri = null,
                        thumbnail = null,
                        state = UiTransferState.Ready("file:///phone/holiday.jpg"),
                    ),
                run = UiRunFlags(false, false, false, false, false),
            ),
            row(
                "t4",
                time = NOW - 50_000,
                type = MessageType.TRANSFER,
                top = UiBody.Absent,
                attachment =
                    UiAttachment(
                        kind = AttachmentKind.FILE,
                        name = "archive.zip",
                        sizeBytes = null,
                        localUri = null,
                        remoteUri = null,
                        thumbnail = null,
                        state = UiTransferState.Failed,
                    ),
                run = UiRunFlags(false, true, false, false, false),
            ),
            row(
                "t5",
                time = NOW - 20_000,
                type = MessageType.TRANSFER,
                top = UiBody.Absent,
                attachment =
                    UiAttachment(
                        kind = AttachmentKind.IMAGE,
                        name = "harbour.jpg",
                        sizeBytes = 1_400_000L,
                        localUri = null,
                        remoteUri = "https://example.org/harbour.jpg",
                        thumbnail = null,
                        state = UiTransferState.None,
                    ),
                run = UiRunFlags(true, true, false, true, true),
            ),
        )
    return ChatState(
        rows = rows,
        revealed = emptySet(),
        selected = emptySet(),
        unreadAnchor = null,
        atBottom = true,
        composer = UiComposer(draft = TextFieldValue(""), language = UiLanguagePair("de", "fi", false)),
    )
}

private fun row(
    id: String,
    direction: Direction = Direction.INCOMING,
    time: Long = NOW,
    type: MessageType = MessageType.TEXT,
    top: UiBody = UiBody.Visible("Hei!"),
    bottom: UiBody? = null,
    divider: Boolean = false,
    quote: UiQuote? = null,
    english: UiEnglishRow = UiEnglishRow.Absent,
    original: UiOriginalRow = UiOriginalRow.Absent,
    transfer: UiTransferState = UiTransferState.None,
    attachment: UiAttachment? = null,
    delivery: UiDeliveryState = UiDeliveryState.Sent,
    run: UiRunFlags = UiRunFlags(true, true, true, true, false),
    selected: Boolean = false,
    canTranslateNow: Boolean = true,
    reactions: List<UiReaction> = emptyList(),
): UiMessage =
    UiMessage(
        id = MessageId(id),
        conversationId = ConversationId("c1"),
        direction = direction,
        time = time,
        type = type,
        top = top,
        bottom = bottom,
        divider = divider,
        quote = quote,
        english = english,
        original = original,
        review = null,
        transfer = transfer,
        attachment = attachment,
        encryption = UiEncryption.None,
        delivery = delivery,
        reactions = reactions,
        gloss = emptyList(),
        run = run,
        selected = selected,
        canTranslateNow = canTranslateNow,
    )
