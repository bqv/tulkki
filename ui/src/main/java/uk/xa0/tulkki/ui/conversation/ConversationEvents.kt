package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.text.input.TextFieldValue
import uk.xa0.tulkki.ui.encryption.EncryptionBlock
import uk.xa0.tulkki.ui.encryption.EncryptionChoice

/**
 * What the conversation view asks its host to do, so the screen emits and the host owns the effects.
 *
 * <p>It is an interface rather than a handful of lambdas for the reason
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListEvents] is: the host is one object, it already
 * holds the service and the activity's protocols, and the screen then has one collaborator instead
 * of a parameter list that changes whenever a row gains an action.
 *
 * <p>**The rows are named by their local uuid**, as the list's events are and for its reason: the
 * host implements this in Java, and the `MessageId` value class the screen types its rows with is
 * the screen's own typing. The three verbs are §3.6's and all three are the *row's*: the tap that
 * means "translate this one, now" (the same verb a covered body and a held own body do not share -
 * the host asks the row which it is), the tap that reveals a concealed English row, and the long
 * press that starts §4.5's selection - the screen reports the gesture and the host decides what a
 * long press means.
 */
interface ConversationEvents {

    /** A covered body was tapped: translate exactly this row, now, against the same cap. */
    fun onBodyTap(messageUuid: String)

    /** A concealed or pending English row was tapped: reveal, or buy, that row's English. */
    fun onEnglishTap(messageUuid: String)

    /**
     * A concealed strip the failure gate offered was tapped: show that row's original. It is the
     * strip's own tap, never the bubble's - the bubble's means "translate this one, now", and item 17's
     * decision five is explicit that the two gestures do not merge.
     */
    fun onRevealOriginal(messageUuid: String)

    /** A body was long-pressed: §4.5's selection, whose mode is the host's state. */
    fun onLongPress(messageUuid: String)

    /** A body was swiped right far enough: §4.4's reply, opened on that row. */
    fun onReply(messageUuid: String)

    /**
     * The reply preview's own dismiss was tapped: the reply is abandoned before it is sent.
     *
     * <p>It is the preview's gesture, not the row's - the same control the Java preview carried as its
     * cancel button, and the one way back out of a reply the owner opened by mistake. The default
     * answers nothing, so a host with no reply state to clear is not forced to write an empty member.
     */
    fun onReplyCancel() {}

    /**
     * The row's avatar was tapped: the tree's `onContactPictureClicked`, which switches the thread,
     * opens a private chat with a room's occupant, or highlights them in the room. The row travels as
     * its uuid like every other verb here, and the host resolves it again when it answers.
     *
     * <p>It is the avatar's *own* gesture, never the bubble's: the bubble's tap means "translate this
     * one, now" and an offered strip's means "show that original", so the three do not merge.
     */
    fun onAvatarTap(messageUuid: String)

    /**
     * The row's avatar was long-pressed: the tree's `onContactPictureLongClicked`, the context menu
     * (contact or account details, the QR code, the room occupant's moderation rows, the account
     * menu). It is a separate verb from [onAvatarTap] because the gesture is separate, and the host
     * owns which menu it is.
     */
    fun onAvatarLongPress(messageUuid: String)

    /**
     * The list's viewport moved. It is a **fact**, not a request, and the host owns every effect: the
     * read receipt is fired up to [lastVisibleUuid], the jump-to-latest control follows [atBottom],
     * the older page is fetched at [atStart], and the anchored card's scroll-dismiss is the screen's
     * own.
     *
     * <p>It replaces the deleted Java `AbsListView`'s `OnScrollListener`, whose three jobs these
     * three facts are: that arithmetic ran through a list view which is `GONE` and draws nothing, so
     * it now runs through the Compose list that is actually on screen.
     *
     * @param lastVisibleUuid the uuid of the bottom-most **drawn** row, or `null` while the read has
     *     not landed, the list is empty, or only a day header is on screen
     * @param atBottom whether the list's last row is drawn
     * @param atStart whether its first item is drawn - the back-pagination trigger
     */
    fun onViewport(lastVisibleUuid: String?, atBottom: Boolean, atStart: Boolean)

    /**
     * The draft changed, by the owner's own hand - the words the gate will read, and **where their
     * caret is**. It is a [TextFieldValue] and not a `String` for the draft verbs' sake: a marker's
     * position *is* a selection, so `/me`, `> ` and the four markup markers are only expressible
     * against one.
     */
    fun onDraftChanged(draft: TextFieldValue)

    /**
     * The send affordance was used. Nothing here decides anything: the gate, the hold and the
     * translation are the send path's, and this is only the gesture's name.
     */
    fun onSend()

    /**
     * The formatting bar's close control was taken, and its own confirmation accepted: the host
     * turns `showtextformatting` off and the bar goes. It is the Java `closeFormatting`'s write and
     * nothing else - the confirmation dialog is the bar's own, so a host that answers this has
     * already been confirmed with.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and the member cannot
     * be added to it without an edit, and a Kotlin interface's default is a real JVM default.
     */
    fun onFormattingClose() {}

    /**
     * The language chip was tapped: the conversation's own end of the pair is the owner's to change,
     * and the host opens the picker that chooses it.
     *
     * <p>It is the whole of the Java chip's tap, moved onto the chip that is actually drawn: the
     * picker's row writes the conversation's language override, and the host re-reads the pair so the
     * chip beside the field names the language the picker just set. Nothing here chooses a language,
     * and nothing here is a second route to the send path.
     *
     * <p>The Java chip's *long* press - Tulkki's settings - is deliberately not carried here: it is
     * one gesture of a `GONE` view, `docs/MIGRATION.md` §2.12 keeps that path reachable from the
     * settings entry only, and a composer chip is not the plain client's affordance for it.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and a Kotlin
     * interface's default is a real JVM default, so a host with no picker is silent rather than
     * forced to write an empty member.
     */
    fun onLanguageChipTap() {}

    /**
     * The emoji affordance was used: open the picker, or put it away. The verb carries no direction -
     * the panel's own controller is the host's, and one affordance toggles where the Java row drew
     * two buttons that swapped with each other.
     *
     * <p>It is the way into a panel that already existed and could not be opened: the two Java
     * triggers (`emojiButton`, `keyboardButton`) sat in the composer row the Compose composer hid, so
     * the surface `EmojiPanelHost` installs had no affordance anywhere the owner could reach - the
     * same shape as the language chip, whose only tap was on a hidden view. The panel is the
     * platform's `EmojiPickerView`, the fragment inserts a picked emoji at the Compose draft's own
     * caret, and nothing here decides either.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and a Kotlin
     * interface's default is a real JVM default, so a host with no panel is silent rather than forced
     * to write an empty member.
     */
    fun onEmojiTap() {}

    /**
     * The attach affordance was used: the owner asked to stage a file, an image, a recording or a
     * place for the next send. It carries no kind on purpose - the Java attach menu
     * (`ConversationMenuConfigurator.configureAttachmentMenu`) is the host's, because the choices
     * depend on the conversation's encryption, on permissions and on what the account supports - so
     * the composer draws one affordance and the host answers with whatever menu it has.
     *
     * <p>**It has a default body, and the JVM shape is why.** The live host is
     * `ConversationFragment`, a Java class that cannot gain the member without being edited (lane
     * `F` owns it), and a Kotlin interface's default methods are real JVM defaults for a Java
     * implementor as well as a Kotlin one - measured, not assumed: with a default body deleted,
     * `TranslationDoubles.java` reddened at `recentOutgoingAttempts()` while every Kotlin
     * implementor compiled (docs/MIGRATION.md, the `transrows` note). The default does nothing
     * rather than inventing a menu, so a host that has not been taught the verb is silent.
     */
    fun onAttach() {}

    /**
     * An encryption choice was picked in the composer's own selector: make it this conversation's
     * next encryption. The choice carries the `Message.ENCRYPTION_*` value itself
     * ([EncryptionChoice.nextEncryption]), so the host writes it back without a second mapping, and
     * nothing here decides whether the write is allowed - the host owns `setNextEncryption` and the
     * service update that follows, exactly as the deleted Java `handleEncryptionSelection` did.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and a Kotlin
     * interface's default is a real JVM default, so a host with no encryption state is silent rather
     * than forced to write an empty member.
     */
    fun onEncryptionSelect(choice: EncryptionChoice) {}

    /**
     * A choice the selector offered but could not make current yet was picked - only OpenPGP can be
     * that. The explanation is drawn by the selector's own row; this is the flow the Java entered
     * instead of setting the encryption, and the host runs it: `showInstallPgpDialog()` for
     * [EncryptionBlock.OPENPGP_PROVIDER_MISSING], `announcePgp(...)` for
     * [EncryptionBlock.OPENPGP_KEY_UNPUBLISHED].
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onEncryptionBlocked(choice: EncryptionChoice, block: EncryptionBlock) {}

    /**
     * The recording bar's cancel was taken: stop and delete the file, and tell the activity the pick
     * was cancelled. It is the deleted Java `mCancelVoiceRecord`'s whole body, and the host's own
     * back press answers with the same effect.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and a Kotlin
     * interface's default is a real JVM default.
     */
    fun onRecordingCancel() {}

    /**
     * The recording bar's share was taken: stop and keep the file, and hand it to the composer's
     * attachment strip. It is the deleted Java `mShareVoiceRecord`'s job; the half-second wait and
     * the disabled affordance are the host's and [VoiceRecordingState.canShare]'s.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onRecordingShare() {}

    /**
     * The recording bar's timer was tapped: pause a live recording, or resume a paused one. It is the
     * deleted Java `mTimerClickListener`'s two branches; whether the call took is the recorder's
     * answer and comes back as the next [VoiceRecordingState].
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onRecordingTogglePause() {}

    /**
     * The request-to-speak affordance was used: this conversation is one the owner cannot write in,
     * and asking the room's moderators for a voice is the only way out of that. It is drawn exactly
     * when the request is possible - the composer's own `canWrite` - and it is the whole of the Java
     * `requestVoice` button's tap, moved onto the surface the owner can actually see: that button sat
     * in the `GONE` Java row with the send path's field, so a room the owner cannot write in had no
     * route to the request at all. The host owns the ask and its confirmation; nothing here chooses
     * either.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and the member cannot
     * be added to it without an edit, and a Kotlin interface's default is a real JVM default.
     */
    fun onRequestVoice() {}

    /**
     * The thread marker was tapped: switch into a new thread, or - when the thread was locked - leave
     * the single thread and follow the conversation again. It is the whole of the Java
     * `threadIdenticonLayout` click, moved onto the marker the owner can see: the Java marker sat in
     * the `GONE` row, so thread selection was drawn and tappable nowhere the owner could reach.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onThreadTap() {}

    /**
     * The thread marker was long-pressed: clear the thread and select a new one, which is the Java
     * `threadIdenticonLayout` long-click. A separate verb because the gesture is separate, and the
     * host owns the tutorial toast and the back-press callback each one moves.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onThreadLongPress() {}

    /**
     * A staged attachment was tapped: open it, or edit it where it is an image - the Java
     * `MediaPreviewAdapter`'s own two answers. The row is named by the host's opaque id (the
     * attachment's uuid), never by a filename, and the host resolves it again when it answers.
     */
    fun onAttachmentTap(attachmentId: String) {}

    /**
     * A staged attachment's remove affordance was used: drop exactly that one from the strip. It is
     * a removal from the *draft*, never a deletion of the file, so the host has nothing to undo on
     * disk.
     */
    fun onAttachmentRemoved(attachmentId: String) {}

    /**
     * Item 16's per-conversation switch was moved: the owner's new answer for **this** conversation,
     * which the host stores (or clears) and the send path then consults on its next hold decision.
     *
     * <p>It is the owner's own answer and never the value in force: the surface opens holding what is
     * in force ([UiDoubtHold]), so off -> on stores an explicit on rather than deleting a row. Nothing
     * here retries a held message - the retry is the owner's tap on the bubble, on this surface as on
     * every other (`OutgoingTranslation.sendHeldNow`).
     */
    fun onDoubtHoldChanged(hold: Boolean)

    /**
     * A banner's own fix was taken - the picker, the settings screen. The banner says what is true at
     * rest; the host owns what the fix does, exactly as it owns every other effect here.
     */
    fun onNoticeAction(action: NoticeAction)

    /**
     * A reaction chip under a row was tapped: the owner's own answer for **this** row and **this**
     * emoji, which the host toggles against the row's aggregated reactions. The chip says who reacted
     * and never what to send, and the emoji is the same string the projector kept - the host rebuilds
     * the set from the message, because a removal is an empty-or-smaller collection rather than a
     * second verb.
     */
    fun onReaction(messageUuid: String, emoji: String)

    /**
     * A reaction chip was long-pressed: the full picker on this row, which is the detail screen the
     * deleted chips opened and the only reaction surface left. It is a separate verb from
     * [onReaction] because the gesture is separate, and the host owns which screen it is.
     */
    fun onReactionPicker(messageUuid: String)

    /**
     * The header's subject line was tapped: a room's subject opens the room's details, a one-to-one
     * contact's status message opens the contact's. It is the deleted `muc_subject` row's own tap,
     * moved onto the row that is drawn, and the host owns which screen the two cases name.
     *
     * <p>It has a default body for [onAttach]'s reason: the live host is Java and a Kotlin
     * interface's default is a real JVM default.
     */
    fun onSubjectOpen() {}

    /**
     * The header's subject line was put away: the room's subject or the contact's status message is
     * marked hidden on the conversation, so the next refresh does not draw it again. It is the
     * deleted `muc_subject_hide` `ImageView`'s whole body - one write to the conversation and no
     * screen state of its own.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onSubjectHide() {}

    /**
     * The header's tune line was tapped: open the contact's details, which is where the song the
     * peer is listening to is shown. It is the deleted `tune_subject` row's own tap.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onTuneOpen() {}

    /**
     * The header's tune line was put away: drop the peer's `UserTune`, so the line goes and does not
     * come back with the next refresh. It is the deleted `tune_subject_hide` `ImageView`'s whole
     * body.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onTuneHide() {}

    /**
     * The header's ephemeral-messages hint was put away: mark the hint hidden on the conversation
     * and store it, which is the deleted `ephemeral_hint_hide` `ImageView`'s whole body. The timer
     * itself is untouched - this hides the sentence, never the ephemeral setting.
     *
     * <p>It has a default body for [onAttach]'s reason.
     */
    fun onEphemeralHide() {}
}
