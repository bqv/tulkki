package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.text.input.TextFieldValue
import uk.xa0.tulkki.ui.encryption.EncryptionSelectionState
import uk.xa0.tulkki.ui.projection.UiDoubtHold
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiQuote

/**
 * The composer's own state, `Design: the Compose UI` §3.6's `composer` member, drawn by
 * `ConversationComposer`.
 *
 * <p>**It carries what the owner typed and what the host decided; it decides nothing itself.** The
 * two rules that govern a send are not here: the language gate is `:translation`'s (`ComposerGate`,
 * consulted where the send happens) and the hold is `OutgoingTranslation`'s, so this type holds the
 * draft the gate will read and the `HeldSend.HoldReason` the send path already produced. A screen
 * that recomputed either would be a second answer to a question that has one.
 *
 * <p>**Every field is the host's to fill, and each has an honest empty.** No [draft] is an empty
 * field; no [language] is no chip at all, which is exactly §2.12's interpreter-off shape ("off, there
 * is no conversation chip at all") rather than a chip that says nothing; no [held] is no bar, and the
 * ordinary send is not held. [reply] is a typed quote, never a `String`: the preview conceals what
 * `ReplyQuote` concealed, and §"Originals are hidden in the UI" says the reply preview is one of the
 * surfaces where the original must never appear.
 *
 * <p>**The held bar carries a [UiHold] and not a bare reason, because item 16's doubt is a third
 * kind.** A held send is either an ordinary `HeldSend.HoldReason` or an answer the check accepted *on
 * doubt*, and only the second's sentence is `:translation`'s own (`getBecause()`), so the type has to
 * be able to name it.
 *
 * <p>**The switch is the conversation's, and `null` is "the interpreter is off".** [doubtHold] is item
 * 16's per-conversation override, and it follows [language]'s convention exactly: off, the composer
 * draws neither, because there is nothing to hold and an affordance that would do nothing must not be
 * drawn. The value is the one **in force** (`UiDoubtHold`, which delegates to `DoubtHold.inForce`):
 * the screen never resolves the stored tri-state itself.
 *
 * <p>**A contract this type carries into the held-send surface (recorded 2026-10-04, measured by the
 * `port` lane, so it is never re-derived):** a held outgoing send may call
 * `TranslationSettings.heldDoubt(uuid)` - `null` means "not a doubt hold" - and, when non-null, the
 * kind's own `getBecause()` is the bar's sentence. The surface **must never re-decide**: sending the
 * held answer must not re-enter `HeldSend.decide` (that re-buys the answer the owner already has,
 * which is the bug the check exists for), and it must not read or write the notes -
 * `ReviewStore.of` already answers those for the bubble. The entry point is unchanged:
 * `translateHeldNow` -> `sendHeldNow`.
 *
 * <p>**What is deliberately not here.** The gate's *suggestion* - the app-language rendering the
 * owner is asked to produce themselves - is absent: the design makes its reveal order a setting
 * (`tulkki_reveal_first`), so the two orders are the surface's decision and the gate's answer is not
 * a field until that surface exists. A field now would be drawn in one of the two orders and would
 * silently make the setting do nothing.
 *
 * <p>**The staged attachments are the host's list, not a copy of the row's cell.** [attachments] is
 * the composer's pending half, typed by [UiPendingAttachment] rather than
 * [uk.xa0.tulkki.ui.projection.UiAttachment]: a staged file has not been transferred, and the row's
 * cell draws a transfer. The composer stages and previews; the row draws. See the type for why the
 * strip can carry no filename.
 */
data class UiComposer(
    /**
     * The owner's own words **and where their caret is**: a [TextFieldValue] rather than a `String`,
     * because a marker's position *is* a selection and the composer's own draft verbs - the
     * formatting row's four markers, `/me` at the head, `> ` at the caret - can only be expressed
     * against one. Theirs, so the one text on this screen that needs no concealing. The empty
     * `TextFieldValue` is an empty field with the caret at its head.
     */
    val draft: TextFieldValue = TextFieldValue(""),

    /** The pair the chip names, or `null` when the interpreter is off and there is no chip. */
    val language: UiLanguagePair? = null,

    /**
     * Item 16's per-conversation switch, holding the value in force, or `null` when the interpreter
     * is off and there is no switch to draw.
     */
    val doubtHold: UiDoubtHold? = null,

    /** Why the last send is held, in the send path's own vocabulary, or `null` when nothing is. */
    val held: UiHold? = null,

    /** The row being replied to, typed and concealed by `ReplyQuote`, or `null` when none is. */
    val reply: UiQuote? = null,

    /**
     * The files and images the owner has staged for the next send, in the order they will travel -
     * the Java `mediaPreviewAdapter`'s list, held by the host and drawn by the composer's strip. The
     * empty list is no strip at all, exactly as the field's empty string is no draft.
     */
    val attachments: List<UiPendingAttachment> = emptyList(),

    /**
     * Whether the draft's formatting bar is drawn, already resolved by the host: the Java
     * `updateinputfield` shows it while the IME is up *and* `showTextFormatting()` is on, and this
     * carries that one two-part answer rather than either half. `false` is no bar, which is also the
     * interpreter-off shape and the state of every JVM cell that does not set it.
     */
    val formatting: Boolean = false,

    /**
     * The field's hint while it is empty, or `null` for the shipped placeholder.
     *
     * <p>It is the Java `EditText`'s own `setHint` (and the `textInputHint` strip beside it),
     * carried because the Compose field is the one drawn: the sentence that says **why** this
     * conversation wants no message - "You are muted", "Send corrected message", "Send private
     * message to <nick>", or the encryption's own line - lived only on the hidden field and the
     * `GONE` row, so a state the owner is owed was invisible. `null` is not "no hint": it is the
     * honest ordinary case, and the field falls back to the shipped `tulkki_chat_hint`.
     */
    val hint: String? = null,

    /**
     * Whether this conversation accepts a message at all - the Java `canWrite()`: a one-to-one chat
     * always does, a room only while the owner participates or writes a private message to an
     * occupant.
     *
     * <p>It is here for the one control that has to know: the **request-to-speak** affordance, which
     * is drawn exactly when the request is possible and never otherwise. `true` is the ordinary case
     * and draws no such control, which is also every JVM cell that does not set it, so a plain
     * conversation is unchanged. The composer still decides nothing itself - the flag is the host's
     * reading of the conversation, handed over like every other field.
     */
    val canWrite: Boolean = true,

    /**
     * The thread marker's state, or `null` when the marker is not drawn: the thread feature is off,
     * the conversation cannot be written in, or the owner is not in a thread. `null` is no marker,
     * the same honest empty the chip and the switch use.
     *
     * <p>**The behaviour is the Java `threadIdenticonLayout`'s, moved onto a surface that is
     * drawn.** The tap switches thread or leaves it, the long press clears it and opens a new one,
     * and both are [ConversationEvents] verbs. The generated identicon graphic itself is not carried
     * across: a Compose field has no `GithubIdenticonView`, and the redesign licence lets the marker
     * be a plain disc in the thread's own colour with the lock badge it already had - the colour is
     * still what distinguishes one thread from the next, and the size and touch target are the
     * spacing scale's rather than the `26dp` box's.
     */
    val thread: UiThread? = null,

    /**
     * The encryption selector's state, or `null` when there is nothing to choose.
     *
     * <p>It is the Java `action_security` submenu typed: [EncryptionSelectionState.current] is
     * `conversation.getNextEncryption()`, its options are the configurator's own visibility chain
     * resolved by [uk.xa0.tulkki.ui.encryption.encryptionSelectionState], and a state whose options
     * are empty draws nothing at all - the Java's `menuSecure.setVisible(false)`. `null` is the same
     * honest empty for a host with no conversation to read, the convention the chip and the switch
     * already follow.
     */
    val encryption: EncryptionSelectionState? = null,

    /**
     * The recording session the composer's bar draws, or the inactive empty.
     *
     * <p>It is the Java `recordingVoiceActivity` block's three views typed: [VoiceRecordingState.active]
     * is the bar's visibility, [VoiceRecordingState.elapsedSeconds] the `timer`, and
     * [VoiceRecordingState.canShare] the share button's enabled state. The inactive empty draws
     * nothing at all, exactly as the Java `GONE` bar did, so a plain composer is unchanged - which is
     * also why it is not nullable: the state has an honest empty of its own.
     */
    val recording: VoiceRecordingState = VoiceRecordingState(),

    /**
     * The host's request that the field take the caret, as a counter: `0` is "no request", and a move
     * of the number asks the field to focus itself once.
     *
     * <p>It is the deleted `binding.textinput.requestFocus()` - `ConversationFragment.onResume` ran it
     * on every resume, so that opening a conversation offered the keyboard. A counter rather than a
     * boolean, because the host may ask again without an intervening "unfocus", and a field that had
     * lost the caret would otherwise never take it back. A value that never moves draws no request at
     * all, which is every cell and every host with no caret to ask for.
     */
    val focusRequest: Int = 0,
) {

    /**
     * Whether the send affordance is offered. A blank draft is nothing to send **unless a file is
     * staged**: the Java composer sends a caption-less attachment (`body.length() == 0 &&
     * !hasAttachments` is the branch that refuses the send), and the attachment travels with the
     * message's own caption. The gate's own question ("is this the app language?") is not one the
     * screen may answer, so the button's enabled state is only about there being something to send.
     */
    val canSend: Boolean
        get() = draft.text.isNotBlank() || attachments.isNotEmpty()
}

/**
 * The thread marker's state: whether the conversation's thread is locked and which thread the
 * marker's colour is derived from.
 *
 * <p>It is the state, not a drawn view: the fragment still owns `conversation.getThread()` and
 * `getLockThread()`, and the marker asks `UIHelper.getColorForName` for the colour the deleted Java
 * identicon was given. A `null` [threadId] is a conversation with no thread yet - the Java marker
 * drew an empty plate and the tap opened a new thread - so the composable falls back to the theme's
 * surface rather than inventing a colour.
 */
data class UiThread(
    /** Whether the conversation's thread is locked, which the marker badges with the lock icon. */
    val locked: Boolean,
    /** The thread's content, or `null` when there is no thread yet. */
    val threadId: String?,
)
