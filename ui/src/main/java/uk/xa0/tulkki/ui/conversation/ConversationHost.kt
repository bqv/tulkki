package uk.xa0.tulkki.ui.conversation

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.viewinterop.AndroidView
import java.time.ZoneId
import java.util.Locale
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.composer.ComposerBarContent
import uk.xa0.tulkki.ui.composer.ComposerBarController
import uk.xa0.tulkki.ui.composer.CorrectionBarContent
import uk.xa0.tulkki.ui.composer.CorrectionBarController
import uk.xa0.tulkki.ui.emoji.EmojiPanelContent
import uk.xa0.tulkki.ui.emoji.EmojiPanelController
import uk.xa0.tulkki.ui.encryption.EncryptionSelectionState
import uk.xa0.tulkki.ui.encryption.encryptionSelectionState
import uk.xa0.tulkki.ui.pinnedmessage.PinnedBarContent
import uk.xa0.tulkki.ui.pinnedmessage.PinnedBarController
import uk.xa0.tulkki.ui.pinnedmessage.PinnedMediaResolver
import uk.xa0.tulkki.ui.pinnedmessage.PinnedMessageRepository
import uk.xa0.tulkki.ui.projection.ConversationFacts
import uk.xa0.tulkki.ui.projection.MessageFacts
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.ProjectionSettings
import uk.xa0.tulkki.ui.projection.UiDoubtHold
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiQuote
import uk.xa0.tulkki.ui.util.MucDetailsAction
import uk.xa0.tulkki.ui.util.MucUserDropdownMenu
import uk.xa0.tulkki.ui.util.MucUserMenu

/**
 * The Java door to the Compose surfaces the live conversation does not host yet: `ConversationFragment`.
 *
 * <p>It exists for the reason [uk.xa0.tulkki.ui.conversationlist.ConversationListHost] and
 * [uk.xa0.tulkki.ui.failures.FailuresHost] do - a Composable cannot be called from Java - and it is
 * deliberately narrower than they are: the live conversation is 8,000 lines of Java that `ui-9` will
 * replace whole, and each slice only makes a surface `ui-9` already wrote *visible* there. So the host
 * draws [ConversationNotices] and [ConversationMessages] and nothing else, over the same state the
 * Compose screen draws them from, so the two cannot drift while both exist. Item 16's switch is
 * [doubtHold]'s reading and rides the composer ([UiComposer.doubtHold]), which draws
 * [ConversationDoubtHold] for the live host and the screen alike.
 * The message list is the slice this host exists for: its rows come from [ConversationRead], the
 * read `MessageSnapshots.watch` was missing, and [MessagesSession] owns the one watcher behind them.
 *
 * <p>**The read is not here.** [notices] and [doubtHold] are the whole of the deciding, and the
 * message list's subscription is [ConversationRead]'s, not this object's. The first is
 * [ConversationNotice.of]'s: the Java fragment reads whether the pair is on, whether the
 * conversation's language is known, whether a key is set and whether this conversation has a failure,
 * and this turns those four facts into the lines to draw. The second is [UiDoubtHold.of]'s: the
 * fragment reads the conversation's stored tri-state, and this resolves it - "never chose" is the
 * build's default, answered in exactly one place in this module. Nothing below either reads a setting,
 * a database or a clock, so a JVM cell reaches both.
 *
 * <p>[show], [showComposer] and [showMessages] set the content once, over an observable session, so a
 * refresh recomposes the surface rather than rebuilding the composition - the same split the list's host
 * keeps. Each callback is one verb and not the whole [ConversationEvents]: a banner has one action, a
 * switch has one answer, and a host that draws these surfaces should not have to answer for a swipe, a
 * long press or a draft.
 */
object ConversationHost {

    /** The live banner's state: the lines to draw, updated as the fragment re-reads. */
    class Session {

        /** The lines the banner draws. Empty is the ordinary case and draws nothing at all. */
        internal var notices: List<UiNotice> by mutableStateOf(emptyList())

        /** The host's next reading, already assembled. */
        fun update(next: List<UiNotice>) {
            notices = next
        }
    }

    /**
     * The live composer's state: the draft the gate will read, the pair the chip names and the switch
     * item 16 owns, updated as the fragment re-reads.
     *
     * <p>It holds the whole [UiComposer] rather than the draft alone, because the composer is one
     * surface: a draft change and a language change are the same state write, and a session that kept
     * them apart could draw a chip that disagrees with the field beside it. The fragment fills it from
     * the facts it already reads - [composer] is the single builder, so nothing here decides a
     * language or a send.
     */
    class ComposerSession {

        /** What the composer draws. The empty composer is an empty field and no chip. */
        internal var composer: UiComposer by mutableStateOf(UiComposer())

        /**
         * Where the composer is drawn, in window pixels: its own top-left corner.
         *
         * <p>It is the anchor the popup hanging off the composer is measured from, and the page's
         * composition reports it (`ConversationComposer`'s modifier) because the composer has no view
         * any more - the deleted `tulkki_composer` ComposeView was what a caller read this from. A
         * plain field and not state: only the fragment reads it, and nothing here draws it.
         */
        internal var anchorInWindow: Offset = Offset.Zero

        /** The host's next reading, already assembled. */
        fun update(next: UiComposer) {
            composer = next
        }
    }

    /**
     * The live header's state: the three at-a-glance lines above the messages, updated as the
     * fragment re-reads the conversation.
     *
     * <p>It holds the whole [UiConversationHeader] rather than the three lines apart, because the
     * presence colour is part of the same reading: a `updateSendButton` that moved the colour and a
     * refresh that moved the subject are the same write, and a session that kept them apart could
     * draw a green subject beside a grey tune.
     */
    class HeaderSession {

        /** What the header draws. The empty value is no line at all. */
        internal var header: UiConversationHeader by mutableStateOf(UiConversationHeader())

        /** The host's next reading, already assembled. */
        fun update(next: UiConversationHeader) {
            header = next
        }
    }

    /**
     * The live resting bar's state: the sentence and the action `updateSnackBar` resolved, or `null`
     * for no bar at all.
     *
     * <p>It holds the listeners themselves - a Java `View.OnClickListener` and its long-press
     * sibling - because they are the fragment's own bodies and the surface hands them its host view;
     * a session that re-modelled them as lambdas would be a second copy of `showBlockSubmenu` and of
     * the unblock flow.
     */
    class SnackbarSession {

        /** The bar to draw, or `null` - the deleted `View.GONE`. */
        internal var snackbar: UiSnackbar? by mutableStateOf(null)

        /** The host's next reading, already assembled. A fresh reading clears [UiSnackbar.actionUsed]. */
        fun show(
            words: Int,
            actionLabel: Int,
            onAction: View.OnClickListener?,
            onActionLongPress: View.OnLongClickListener?,
        ) {
            snackbar = UiSnackbar(words, actionLabel, onAction, onActionLongPress)
        }

        /**
         * The action was used: the deleted `v.setVisibility(INVISIBLE)` on the button the listener
         * was handed. The button keeps its place and loses its pixels and its touch target.
         */
        fun markActionUsed() {
            snackbar = snackbar?.copy(actionUsed = true)
        }

        /** Take the bar away: the deleted `hideSnackbar()`. */
        fun hide() {
            snackbar = null
        }
    }

    /**
     * The live wall's state: the conversation's own background picture, or `null` for the theme's
     * surface.
     *
     * <p>It holds the URI as a `String` because that is what a host can carry without the `Uri`'s
     * permissions riding along, and because a URI is the whole input: the view decodes, this decides
     * nothing. `null` is the ordinary conversation.
     */
    class BackgroundSession {

        /** The picture to draw, or `null` - which is the deleted `View.GONE`. */
        internal var uri: String? by mutableStateOf(null)

        /** The host's next reading, already assembled. */
        fun update(next: String?) {
            uri = next
        }
    }


    /**
     * The live popup's state: the rows the fragment assembled and where they hang, or `null` for no
     * popup.
     *
     * <p>One slot, because the conversation's popups cannot be open with one another: the owner's tap
     * picks one, and a second ask replaces the first rather than stacking.
     *
     * <p>It carries two shapes, because the conversation's menus come from two places: [menu] is one
     * the fragment assembled a row at a time ([UiRowMenu]), and [mucUser] is `:ui`'s participant menu
     * (`MucUserDropdownMenu`), whose caller ([uk.xa0.tulkki.ui.util.MucDetailsContextMenuHelper]) owns
     * the rows and the verbs. Both are drawn by [showRowMenu]'s one content, so there is one popup on
     * the screen and not two.
     */
    class RowMenuSession {

        /** The popup to draw, or `null` - which is the ordinary state. */
        internal var menu: UiRowMenu? by mutableStateOf(null)

        /** The participant menu to draw, or `null`. */
        internal var mucUser: MucUserMenu? by mutableStateOf(null)

        /** The participant menu's dispatcher, held apart from the drawing [mucUser] it answers. */
        private var onMucUserSelected: ((MucDetailsAction) -> Unit)? = null

        /**
         * The host's next reading: the visible rows, in the deleted menu's order, the anchor's own
         * corner in dp, and the dispatcher that answers the row that was taken.
         */
        fun show(items: List<UiMenuItem>, offset: DpOffset, onSelected: (Int) -> Unit) {
            mucUser = null
            menu = UiRowMenu(items, offset, onSelected)
        }

        /**
         * The participant menu, with the entries `MucDetailsContextMenuHelper.visibleEntries` resolved
         * and the verb it will answer with.
         */
        fun showMucUser(entries: MucUserMenu, onSelected: (MucDetailsAction) -> Unit) {
            menu = null
            mucUser = entries
            onMucUserSelected = onSelected
        }

        /** The popup was dismissed, or a row was taken: the deleted `PopupMenu`'s own dismissal. */
        fun hide() {
            menu = null
            mucUser = null
            onMucUserSelected = null
        }

        /**
         * The participant menu's own dismissal, which the composable reports before it reports the
         * row: it takes the drawing away and leaves the dispatcher, so the row that is being chosen
         * still has its verb when [dispatchMucUser] runs next.
         */
        internal fun dismissMucUser() {
            mucUser = null
        }

        /** A participant row was taken: the drawing goes, then the verb it named runs. */
        internal fun dispatchMucUser(action: MucDetailsAction) {
            val selected = onMucUserSelected
            hide()
            selected?.invoke(action)
        }
    }


    /**
     * Sets the command page's content once, over the live [CommandsSession].
     *
     * <p>It draws [ConversationCommands]: the rows the fragment resolved, the fetch's progress and the
     * onboarding note. The tap is a `Consumer<UiCommand>` rather than the whole [ConversationEvents]
     * because a command row has one verb - run the command - and the fragment owns what "run" means
     * for the conversation that is on screen now.
     */
    @JvmStatic
    fun showCommands(
        view: ComposeView,
        session: CommandsSession,
        darkTheme: Boolean,
        onStart: Consumer<UiCommand>,
    ) {
        view.setTulkkiContent(darkTheme) {
            ConversationCommands(session = session, onStart = { onStart.accept(it) })
        }
    }

    /**
     * The live message list's state: the rows [ConversationRead] has delivered, and the one watcher
     * behind them.
     *
     * <p>[rows] is three-valued, like the screen's own `ChatState.rows` and the list's state: `null`
     * is "the read's first emission has not landed", which [ConversationMessages] draws as the
     * loading shape rather than telling the owner the conversation is empty. A session that was
     * never shown, or one already [release]d, is exactly that `null`: the off state offers no rows.
     *
     * <p>[now] is the host's clock, an input like the screen's - the rows' day labels are drawn
     * against the moment they were read rather than against a clock the list reads for itself.
     */
    class MessagesSession {

        /** The observable backing of [rows]; private so only an emission can move it. */
        private var rowsState: List<MessageSnapshot>? by mutableStateOf(null)

        /** The conversation's rows, oldest first - or `null` before the read's first emission. */
        val rows: List<MessageSnapshot>?
            get() = rowsState

        /** The observable backing of [now]; private so only the host's clock moves it. */
        private var nowState: Long by mutableStateOf(0L)

        /** The moment the rows were read, which the list's day labels are relative to. */
        val now: Long
            get() = nowState

        /**
         * The live watcher, or `null` when nothing is subscribed. `null` is the off state, so a
         * session can be shown, released and shown again without a stale stream surviving.
         */
        private var watcher: Job? = null

        /**
         * The two facts the projector may not decide and the screen has no state of its own for
         * until `ChatState` is built: the rows whose English row the owner unblurred, the rows whose
         * offered original the owner opened, and the rows in §4.5's selection. They are the session's
         * because the live host is Java and cannot hold a Kotlin `set` the assembly reads; a reveal
         * moves one of these and the next recomposition re-projects from the same snapshots, so
         * nothing re-reads the file.
         */
        internal var revealed: Set<MessageId> by mutableStateOf(emptySet())

        internal var revealedOriginals: Set<MessageId> by mutableStateOf(emptySet())

        internal var selected: Set<MessageId> by mutableStateOf(emptySet())

        /**
         * The row the host asked the list to bring on screen, or `null` when there is no request. It
         * is the Java jumps' half: `ConversationFragment` cannot move a `LazyListState`, so a reply's
         * target, the first unread row and a pinned message are named here and the screen scrolls.
         */
        internal var scrollTo: MessageId? by mutableStateOf(null)

        /**
         * How many messages arrived while the reader was away from the newest row: the count the
         * jump-to-latest control carries as its badge. It is the fragment's own reading (the tree's
         * `unreadCountCustomView`), written here because the control is the Compose list's now and the
         * count has to travel with the same state that draws it.
         */
        internal var unreadCount: Int by mutableStateOf(0)

        /** Ask the list to bring this row on screen; the screen clears it once it has. */
        fun requestScroll(id: String) {
            scrollTo = MessageId(id)
        }

        /** The count of messages received since the reader left the bottom; `0` draws no badge. */
        fun unread(count: Int) {
            unreadCount = if (count < 0) 0 else count
        }

        /** The list has answered [scrollTo]; a stale request must not re-fire on a recomposition. */
        fun scrolled() {
            scrollTo = null
        }

        /** The owner unblurred this row's English strip, or bought it: reveal it in this process. */
        fun revealEnglish(id: String) {
            revealed = revealed + MessageId(id)
        }

        /** The owner opened this row's offered original: the failure gate's own strip, its own tap. */
        fun revealOriginal(id: String) {
            revealedOriginals = revealedOriginals + MessageId(id)
        }

        /** §4.5: one more selected row, or one fewer when it was already selected. */
        fun toggled(id: String) {
            val key = MessageId(id)
            selected = if (selected.contains(key)) selected - key else selected + key
        }

        /** Out of selection mode entirely, at a back gesture. */
        fun cleared() {
            selected = emptySet()
        }

        /**
         * The host's listener for a rows write, called with every [update] **before** the state
         * moves.
         *
         * <p>It exists for a derived cache the composition reads: `XmppActivity.cacheMessageRows` is
         * the one listener the live host installs, so the reply seam's quote answer can resolve the
         * row a reply names without opening the database. Calling it *before* [rowsState] moves is the
         * whole point - a write that changed the rows would otherwise schedule a recomposition whose
         * projection read a cache still one write behind. A listener that threw would take the rows
         * with it, so a host installs one that cannot.
         */
        var onRows: Consumer<List<MessageSnapshot>>? = null

        /** The host's next reading, from one emission. */
        fun update(next: List<MessageSnapshot>) {
            onRows?.accept(next)
            rowsState = next
        }

        /** The host's clock for the rows it just read. */
        fun at(moment: Long) {
            nowState = moment
        }

        /**
         * Subscribe to [source]. A second call releases the first watcher before it starts the
         * next, so a re-show - or a `show` after a view recreation - cannot leave two live.
         */
        fun watch(source: Flow<List<MessageSnapshot>>, scope: CoroutineScope) {
            watcher?.cancel()
            watcher = ConversationRead.subscribe(source, scope) { update(it) }
        }

        /**
         * Release the watcher: after this no emission lands. It is the whole of "release on
         * destroy" - the fragment calls it from `onDestroyView` - and a session shown again
         * [watch]es a fresh stream rather than keeping the dead one.
         */
        fun release() {
            watcher?.cancel()
            watcher = null
        }
    }


    /**
     * The lines this conversation's banner draws, from the four facts the fragment read.
     *
     * <p>It is [ConversationNotice.of] wrapped into the list the screen takes, and the wrap is the
     * point: the screen draws "the notices", the rule answers "which one, if any", and a host that
     * found something to say on its own would be a second answer. Off, the rule answers `null`, so
     * this is empty and the surface is not drawn at all - the interpreter-off cell pins that.
     */
    @JvmStatic
    fun notices(
        interpreting: Boolean,
        languageKnown: Boolean,
        keyConfigured: Boolean,
        failures: Boolean,
    ): List<UiNotice> = listOfNotNull(ConversationNotice.of(interpreting, languageKnown, keyConfigured, failures))

    /**
     * The switch's reading, from the two facts the fragment read.
     *
     * <p>It is [UiDoubtHold.of] wrapped in the interpreter's own predicate, and the wrap is the point:
     * off, there is nothing to hold and an affordance that would do nothing must not be drawn
     * (`UiComposer.doubtHold`'s own convention), so this answers `null` and the surface is not drawn
     * at all - the interpreter-off cell pins that. On, the value is the one **in force**:
     * [UiDoubtHold.of] resolves the stored tri-state, and the fragment never does, so a room the owner
     * never touched reads as the shipped default rather than as a second answer.
     *
     * @param stored the conversation's own tri-state, straight off the entity: `null` is "never
     *     chose", which is not "off"
     */
    @JvmStatic
    fun doubtHold(interpreting: Boolean, stored: Boolean?): UiDoubtHold? =
        if (interpreting) UiDoubtHold.of(stored) else null


    /**
     * The composer's own reading, from the facts the fragment supplies: the [draft] the gate will read
     * (its words **and** its caret), the pair the chip names (`null` when the interpreter is off, which
     * is no chip at all - [UiComposer.language]'s own convention), the switch item 16 owns ([doubtHold],
     * already resolved by [doubtHold], `null` when there is none to draw), and the row being answered
     * ([reply], `null` when there is none).
     *
     * <p>It is the one builder of the live [UiComposer], so the fragment cannot hand the composer a
     * half-filled state: the field it does not fill is the honest empty [UiComposer] declares - no
     * held bar - because that surface is already live on its own host (the composer's own bar) and a
     * second copy here would draw it twice. The reply is filled now: the Java preview is gone and
     * [UiQuote] is the one preview, so a quote drawn here cannot draw beside a second one.
     * [formatting] is filled too: the fragment's `showTextFormat`/`hideTextFormat` resolve the
     * two-part condition and this carries the answer. [attachments] is filled from the host's own
     * staged list, thumbnails and all, so the Compose strip is the one the owner sees and no Java
     * strip is drawn beside it. And [doubtHold] is filled: the switch is
     * [ConversationDoubtHold]'s single drawing, so the live composer and the screen draw the same row
     * from the same resolution rather than one of them owning a second copy.
     *
     * <p>[hint] is filled from the fragment's own `updateChatMsgHint`, which is the one place that
     * reads the reasons a field carries a sentence - a correction, a private-message addressee, a
     * mute, and the encryption's own line - so the Compose field states the reason rather than the
     * hidden Java `EditText` carrying it where nobody can read it. [canWrite] is the conversation's
     * own "accepts a message" answer, which gates the request-to-speak affordance, and [thread] is
     * the marker's typed state - `null` when the host resolved that there is no marker to draw.
     * [encryption] is the selector's typed state, `null` when the host has no conversation to read,
     * and it is the Java `configureEncryptionMenu`'s whole answer now. [recording] is the recording
     * session's typed state, whose inactive empty draws nothing, so the bar the Java
     * `recordingVoiceActivity` block carried is drawn only while a session is up.
     *
     * <p>[focusRequest] is the fragment's own `requestFocus`: the field takes the caret whenever the
     * number moves, so `onResume` still offers the keyboard the way the deleted `EditText` did, and a
     * host that never asks (a cell, a preview) passes nothing.
     */
    @JvmStatic
    fun composer(
        draft: TextFieldValue,
        language: UiLanguagePair?,
        doubtHold: UiDoubtHold?,
        reply: UiQuote?,
        attachments: List<UiPendingAttachment>,
        formatting: Boolean,
        hint: String?,
        canWrite: Boolean,
        thread: UiThread?,
        encryption: EncryptionSelectionState?,
        recording: VoiceRecordingState,
        focusRequest: Int,
    ): UiComposer =
        UiComposer(
            draft = draft,
            language = language,
            doubtHold = doubtHold,
            reply = reply,
            attachments = attachments,
            formatting = formatting,
            hint = hint,
            canWrite = canWrite,
            thread = thread,
            encryption = encryption,
            recording = recording,
            focusRequest = focusRequest,
        )

    /**
     * The encryption selector's reading, from the facts the fragment read.
     *
     * <p>It is [encryptionSelectionState] itself, named here for the reason [doubtHold] is: the rule
     * belongs to [uk.xa0.tulkki.ui.encryption.EncryptionSelector] and the host only supplies the
     * facts, so a host cannot re-derive the visibility chain and the Java `configureEncryptionMenu`
     * has exactly one successor. Every parameter is the Java's own reading, in the order
     * [encryptionSelectionState] documents.
     */
    @JvmStatic
    @Suppress("LongParameterList")
    fun encryption(
        nextEncryption: Int,
        multi: Boolean,
        participating: Boolean,
        privateAndNonAnonymous: Boolean,
        formerlyPrivateNonAnonymous: Boolean,
        unencryptedSupported: Boolean,
        openPgpSupported: Boolean,
        omemoSupported: Boolean,
        otrSupported: Boolean,
        omemoAlways: Boolean,
        otrEnabled: Boolean,
        openPgpProviderInstalled: Boolean,
        openPgpKeyPublished: Boolean,
    ): EncryptionSelectionState =
        encryptionSelectionState(
            nextEncryption = nextEncryption,
            multi = multi,
            participating = participating,
            privateAndNonAnonymous = privateAndNonAnonymous,
            formerlyPrivateNonAnonymous = formerlyPrivateNonAnonymous,
            unencryptedSupported = unencryptedSupported,
            openPgpSupported = openPgpSupported,
            omemoSupported = omemoSupported,
            otrSupported = otrSupported,
            omemoAlways = omemoAlways,
            otrEnabled = otrEnabled,
            openPgpProviderInstalled = openPgpProviderInstalled,
            openPgpKeyPublished = openPgpKeyPublished,
        )

    /**
     * The thread marker's reading, from the two facts the fragment read: whether the thread is
     * locked and the thread's own content.
     *
     * <p>**The decision to draw it is the fragment's**, because it depends on a preference and on
     * `canWrite()` - both the host's own thread-feature condition, which `updateSendButton` used to
     * resolve for the `GONE` Java row. This builder only types the two facts the marker draws, so a
     * caller that has no thread to show hands `null` instead and the composer draws no marker at all.
     */
    @JvmStatic
    fun thread(locked: Boolean, threadId: String?): UiThread = UiThread(locked = locked, threadId = threadId)

    /**
     * The draft's own state as the field hands it over, from the three facts a Java caller has: the
     * text and its two selection offsets.
     *
     * <p>**It exists because Java cannot spell a `TextFieldValue` constructor's defaults**, and it is
     * also where the offsets are made safe: the Java `EditText` and the Compose field can both hand
     * a stale cursor, and a `TextRange` outside the text would make the field throw on the next
     * recomposition. Both offsets are clamped into `0..length` rather than trusted, and a backwards
     * range stays backwards (`TextRange` orders nothing), because a reversed selection is a real
     * shape the markup arithmetic takes `min`/`max` for.
     */
    @JvmStatic
    fun draft(text: String, selectionStart: Int, selectionEnd: Int): TextFieldValue =
        TextFieldValue(
            text = text,
            selection =
                TextRange(
                    selectionStart.coerceIn(0, text.length),
                    selectionEnd.coerceIn(0, text.length),
                ),
        )

    /**
     * Where [draft]'s caret starts, for a Java caller.
     *
     * <p>**The accessors are not a convenience: `TextRange` is a value class.** Its `start` and `end`
     * are `getStart-impl(long)`/`getEnd-impl(long)` in the bytecode, and `TextFieldValue.selection`
     * is mangled too, so `draft.getSelection().getStart()` is not a thing Java can write. The same
     * one reason [draft] exists, one level down.
     */
    @JvmStatic
    fun selectionStart(draft: TextFieldValue): Int = draft.selection.start

    /** Where [draft]'s caret ends, for a Java caller. See [selectionStart] for why it exists. */
    @JvmStatic
    fun selectionEnd(draft: TextFieldValue): Int = draft.selection.end

    /**
     * The header's own reading, from the three facts the fragment read - the subject-or-status line,
     * the tune and the ephemeral hint, each already worded - and the presence colour the icons take.
     *
     * <p>It is the one builder of the live [UiConversationHeader], so the fragment cannot hand the
     * header a half-filled value. The colour travels as an ARGB `int` because that is what
     * `SendButtonTool.getSendButtonColor` answers and what Java can spell; the empty reading is
     * [UiConversationHeader]'s own `Color.Unspecified`, whose sentence is the theme's.
     */
    @JvmStatic
    fun header(
        subject: UiSubjectLine?,
        tune: UiTuneLine?,
        ephemeral: UiEphemeralLine?,
        iconTint: Int,
    ): UiConversationHeader =
        UiConversationHeader(
            subject = subject,
            tune = tune,
            ephemeral = ephemeral,
            iconTint = Color(iconTint),
        )



    /**
     * The conversation page's inputs: the surfaces' own sessions, the four bar controllers and the
     * drawing switches, as one value the fragment hands over once.
     *
     * <p>It is a class and not a data class because one member moves when the conversation does:
     * [messages] is Compose state, so a thread switch that replaces the rows' session redraws the page
     * instead of needing the page's content set again. The facts beside it - [conversation], [facts],
     * [settings] and the four drawing switches - are plain variables read in the same recomposition
     * that write schedules, and they are written before it.
     *
     * <p>The controllers are the fragment's own ([PinnedBarController], [EmojiPanelController],
     * [CorrectionBarController] and `ComposerBarController`), which is what the four `*Content` doors
     * take: the page draws the bar the fragment controls rather than installing one on a view of its
     * own.
     */
    class PageInputs(
        val header: HeaderSession,
        val notices: Session,
        val composer: ComposerSession,
        val snackbar: SnackbarSession,
        val rowMenu: RowMenuSession,
        val bar: ComposerBarController,
        val pinned: PinnedBarController,
        val pinnedRepository: PinnedMessageRepository,
        val pinnedMedia: PinnedMediaResolver,
        val pinnedJump: Consumer<String>,
        val emoji: EmojiPanelController,
        val emojiPicked: Consumer<String>,
        val correction: CorrectionBarController,
        val correctionCancel: Runnable,
    ) {

        /** The rows' session. A new one per conversation, which is why it is state. */
        internal var messages: MessagesSession by mutableStateOf(MessagesSession())

        /** The facts every row shares, or `null` before the conversation is known. */
        internal var conversation: ConversationFacts? = null

        /** The row-level live facts, [MessageFacts.NONE] until a host supplies them. */
        internal var facts: MessageFacts = MessageFacts.NONE

        /** The projector's settings. */
        internal var settings: ProjectionSettings = ProjectionSettings.SHIPPED

        /** The owner's `show_avatars`: off means no reserved column either. */
        internal var avatarsOn: Boolean = true

        /** The owner's `use_green_background`, the tree's `colorfulChatBubbles`. */
        internal var colorful: Boolean = true

        /** The owner's `show_formatting_marks`: off means a drawn body's markers are consumed. */
        internal var formattingMarks: Boolean = false

        /** The locale the rows' labels are written in. */
        internal var locale: Locale = Locale.getDefault()

        /** The zone the rows' labels are written in. */
        internal var zone: ZoneId = ZoneId.systemDefault()
    }

    /**
     * Sets the root's content once: the wall behind everything, the pager, and the conversation's
     * popups.
     *
     * <p>It draws [ConversationBackground], an `AndroidView` holding the pager
     * [ConversationPagerController] built, and the two menus [RowMenuSession] carries. Nothing here
     * decides a fact - the background's URI and the pager's pages are the host's.
     */
    @JvmStatic
    fun showRoot(
        view: ComposeView,
        pager: ConversationPagerController,
        background: BackgroundSession,
        rowMenu: RowMenuSession,
        darkTheme: Boolean,
    ) {
        view.setTulkkiContent(darkTheme) {
            Box(modifier = Modifier.fillMaxSize()) {
                ConversationBackground(background.uri)
                AndroidView(factory = { pager.root }, modifier = Modifier.fillMaxSize())
            }
            RowMenus(rowMenu)
        }
    }

    /**
     * Sets the conversation page's content once, over the live [PageInputs]: the one composition the
     * screen is.
     *
     * <p>It draws the header, the pinned bar, the messages, the snackbar, the notices, the correction
     * bar, the composer's own bar, the composer, the emoji panel and the reading aid's overlay - every
     * surface the conversation has - from the state the fragment hands over, so a surface cannot be
     * live twice and none of them needs a `ComposeView` to hang from.
     *
     * @param onJumpToLatest what the list's jump-to-latest control asks the host before it scrolls:
     *     the fragment's `jumpToTheBottom`, which leaves a loaded history part.
     */
    @JvmStatic
    fun showPage(
        view: ComposeView,
        inputs: PageInputs,
        darkTheme: Boolean,
        events: ConversationEvents,
        onJumpToLatest: Runnable,
    ) {
        view.setTulkkiContent(darkTheme) { ConversationPage(inputs, events, onJumpToLatest) }
    }

    /** The two menus the conversation can have open, drawn from one session. */
    @Composable
    private fun RowMenus(session: RowMenuSession) {
        ConversationRowMenu(menu = session.menu, onDismiss = session::hide)
        val muc = session.mucUser
        if (muc != null) {
            MucUserDropdownMenu(
                menu = muc,
                onDismiss = { session.dismissMucUser() },
                onSelected = { action -> session.dispatchMucUser(action) },
            )
        }
    }

    /**
     * The conversation page: every surface of the conversation, in the order the deleted layout had
     * them, drawn from [PageInputs].
     *
     * <p>The rows are projected once per emission ([ChatState.of]) exactly as the message list's own
     * host did, and a `null` [PageInputs.conversation] - the moment before the fragment's first read -
     * draws the list's loading shape rather than a conversation that does not exist yet.
     */
    @Composable
    private fun ConversationPage(
        inputs: PageInputs,
        events: ConversationEvents,
        onJumpToLatest: Runnable,
    ) {
        val messages = inputs.messages
        val conversation = inputs.conversation
        val state =
            remember(
                messages.rows,
                messages.revealed,
                messages.revealedOriginals,
                messages.selected,
                conversation,
                inputs.facts,
                inputs.settings,
            ) {
                if (conversation == null) {
                    null
                } else {
                    ChatState.of(
                        messages = messages.rows,
                        facts = inputs.facts,
                        settings = inputs.settings,
                        conversation = conversation,
                        revealed = messages.revealed,
                        selected = messages.selected,
                        revealedOriginals = messages.revealedOriginals,
                    )
                }
            }
        Column(modifier = Modifier.fillMaxSize()) {
            ConversationHeader(
                header = inputs.header.header,
                onSubjectOpen = events::onSubjectOpen,
                onSubjectHide = events::onSubjectHide,
                onTuneOpen = events::onTuneOpen,
                onTuneHide = events::onTuneHide,
                onEphemeralHide = events::onEphemeralHide,
            )
            PinnedBarContent(
                controller = inputs.pinned,
                repository = inputs.pinnedRepository,
                media = inputs.pinnedMedia,
                jumpToMessage = inputs.pinnedJump,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (state != null) {
                    ConversationMessages(
                        state = state,
                        events = events,
                        now = messages.now,
                        modifier = Modifier.fillMaxSize(),
                        avatarsOn = inputs.avatarsOn,
                        colorful = inputs.colorful,
                        formattingMarks = inputs.formattingMarks,
                        locale = inputs.locale,
                        zone = inputs.zone,
                        scrollTo = messages.scrollTo,
                        onScrolled = messages::scrolled,
                        unreadCount = messages.unreadCount,
                        onJumpToLatest = { onJumpToLatest.run() },
                    )
                } else {
                    ConversationLoading(Modifier.fillMaxSize())
                }
            }
            Column(modifier = Modifier.imePadding()) {
                ConversationSnackbar(inputs.snackbar.snackbar)
                ConversationNotices(inputs.notices.notices) { events.onNoticeAction(it) }
                CorrectionBarContent(inputs.correction, inputs.correctionCancel)
                ComposerBarContent(inputs.bar)
                ConversationComposer(
                    composer = inputs.composer.composer,
                    events = events,
                    // Tulkki: the composer is drawn here, so this is where a popup anchored at it
                    // reads its corner from (`ComposerSession.anchorInWindow`). It is the view that
                    // is gone, not the anchor: the composer is still the thing the owner taps.
                    modifier =
                        Modifier.onGloballyPositioned {
                            inputs.composer.anchorInWindow = it.boundsInWindow().topLeft
                        },
                )
            }
            EmojiPanelContent(inputs.emoji, inputs.emojiPicked)
        }
    }
}
