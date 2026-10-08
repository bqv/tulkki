package uk.xa0.tulkki.ui.conversation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.Gloss
import uk.xa0.tulkki.translation.GlossContent
import uk.xa0.tulkki.translation.GlossLookup
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.ui.AnchoredPopup
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.conversationlist.AvatarShape
import uk.xa0.tulkki.ui.conversationlist.ConversationAvatar
import uk.xa0.tulkki.ui.projection.ConversationRowTime
import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.PreviewWords
import uk.xa0.tulkki.ui.projection.UiBody
import uk.xa0.tulkki.ui.projection.UiConcealment
import uk.xa0.tulkki.ui.projection.UiEnglishRow
import uk.xa0.tulkki.ui.projection.UiGlossWord
import uk.xa0.tulkki.ui.projection.UiMessage
import uk.xa0.tulkki.ui.projection.UiOriginalRow
import uk.xa0.tulkki.ui.projection.UiQuote
import uk.xa0.tulkki.ui.shimmer
import uk.xa0.tulkki.ui.theme.LocalTulkkiColors
import uk.xa0.tulkki.ui.theme.TulkkiShape
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import uk.xa0.tulkki.ui.conversationlist.UiConnection
import uk.xa0.tulkki.ui.projection.Anchor
import uk.xa0.tulkki.ui.projection.AttachmentKind
import uk.xa0.tulkki.ui.projection.UiAttachment
import uk.xa0.tulkki.ui.projection.UiTransferState

/**
 * The conversation view's message surface, `Design: the Compose UI` §3.6 - the screen [ChatState] was
 * assembled for, drawn from that state and nothing else.
 *
 * <p>**What is drawn here, and what is not yet.** The three screen states §3.6 names for the *read* -
 * loading history as a top-anchored progress row, §4.6's explainer when the read landed empty, and
 * the rows themselves - plus, per row: the reply's quote, the typed body halves (the app language's
 * top half, the conversation's concealed bottom half, the divider between them), §2b's placeholder
 * for a body that could not be translated, the English row in its four states, §3.6's attachment cell
 * for a file or image row (its four transfer states), §4.2's unread pill,
 * §4.5's selection outline, and the last line's time, §4.10's fallback ("metadata on its own row,
 * `chatMeta` 12 sp") rather than the custom layout that rides the timestamp on the text.
 *
 * <p>**What is deliberately not here, each with its reason, so a reader does not mistake a slice for
 * a screen.** §3.6's four remaining state members - `composer`, `notices`, `connection` and
 * `translationActivity` - are 2d: the composer is its own surface with its own gate, and §4's
 * day-header, anchoring and swipe mechanics come with it. §4.5's message menu, the reactions row,
 * §4.8's image grid and §4.9's span tap are `ui-10` and later. The **sender's name** is not drawn
 * even where `UiRunFlags.showName` asks for it: §2.2's `UiMessage` carries no sender or occupant
 * field, so a room's bubble has nothing to name - a projection gap recorded here rather than filled
 * with the conversation's own name, which would be a lie in every room. The **avatar** is drawn
 * through [ConversationAvatar] by the row's own `conversationId`, which is the peer in a one-to-one
 * and the room itself in a room: the same gap, named in the same place. And §4.1's invisible-avatar
 * versus gone distinction is [AvatarPlacement]'s, so it is a cell here even where the pixels are
 * three lines.
 *
 * <p>**The clock is an input.** §4.3 anchors on the previous state and §2.2's `ConversationRowTime`
 * takes the moment it is drawn, so the screen is handed `now` (and the zone and locale the label is
 * written in) rather than reading a clock of its own: a screen that reads one cannot be asked what it
 * says about a fixture, and the reference captures would drift with the calendar.
 *
 * @param state the assembled state, from `ChatState.of`
 * @param events what the screen asks its host to do
 * @param now the moment the rows are drawn, which every row's label is relative to
 * @param header the three at-a-glance lines above the messages - the room's subject (or the
 *     contact's status message), the peer's tune and the ephemeral hint - the empty value when this
 *     conversation has none, which is the ordinary case and draws nothing
 * @param snackbar the conversation's resting bar - the account's state, a block, a stranger, a
 *     pending decryption - or `null` for no bar, which is the ordinary case
 * @param avatar the host's avatar port, or `null` while a screen has none - which is [AvatarPlacement.GONE]
 * @param avatarsOn the owner's `show_avatars`: off means no reserved column either
 * @param colorful the owner's `use_green_background`, the tree's `colorfulChatBubbles`
 * @param locale the locale the timestamp is written in
 * @param zone the zone the timestamp is written in
 */
@Composable
fun ConversationScreen(
    state: ChatState,
    events: ConversationEvents,
    now: Long,
    modifier: Modifier = Modifier,
    header: UiConversationHeader = UiConversationHeader(),
    snackbar: UiSnackbar? = null,
    avatar: ConversationAvatar? = null,
    avatarsOn: Boolean = false,
    colorful: Boolean = true,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // The two screen-level lines sit above the list and never instead of it - "no messages" and "not
    // connected" are different facts - and the composer is always there, because a conversation the
    // owner cannot write into is not a conversation screen.
    //
    // The IME's own inset belongs to the *bar*, not to the root. The app targets 36, so the window is
    // edge-to-edge and the system does not resize it for the keyboard; a root that consumed `ime`
    // would pad the whole page, which lifts the list by the keyboard's height as well and leaves the
    // space under the composer blank. The bar at the bottom is the node anchored to the window's own
    // bottom, so it is the node whose height the keyboard eats into: it carries the inset, the list
    // keeps the space above it, and only the bar rises. A host that has already inset the page for
    // the IME (the shell's `fitsSystemWindows`) must consume it before this surface, or the two
    // applications would both take the keyboard's height.
    Column(modifier = modifier.fillMaxSize()) {
        // The three at-a-glance lines the deleted `muc_subject`/`tune_subject`/`ephemeral_hint` rows
        // drew, at the top of the screen where the XML had them. Each is the conversation's own
        // metadata - a subject, a tune, a timer - and none of them is a body, so none of them
        // touches the concealment rules; an absent line composes nothing, which is why a screen with
        // no header is the same picture it was.
        ConversationHeader(
            header = header,
            onSubjectOpen = events::onSubjectOpen,
            onSubjectHide = events::onSubjectHide,
            onTuneOpen = events::onTuneOpen,
            onTuneHide = events::onTuneHide,
            onEphemeralHide = events::onEphemeralHide,
        )
        ConversationStatus(connection = state.connection, translationActivity = state.translationActivity)
        Box(modifier = Modifier.weight(1f)) {
            ConversationMessages(
                state = state,
                events = events,
                now = now,
                modifier = Modifier.fillMaxSize(),
                avatar = avatar,
                avatarsOn = avatarsOn,
                colorful = colorful,
                locale = locale,
                zone = zone,
            )
        }
        // Item 17's banner, drawn where the tree drew its own bar: in the composer's own area, which is
        // what §3.6 means by "a composer-bar notice". It is [ConversationNotices] rather than a loop
        // so the live Java conversation can host exactly the same surface, and only that surface,
        // through `ConversationHost` while the rest of this screen is still `ui-9`'s.
        Column(modifier = Modifier.imePadding()) {
            // The resting bar, in the slot the deleted `snackbar` `RelativeLayout` occupied: above the
            // composer. It shares that slot with item 17's banner, which is why the two stack rather
            // than overlap - the XML anchored both above `input_area`.
            ConversationSnackbar(snackbar)
            ConversationNotices(state.notices) { events.onNoticeAction(it) }
            ConversationComposer(composer = state.composer, events = events)
        }
    }
}

/**
 * The message list alone: the three states §3.6 names for the read - loading, the empty explainer,
 * the rows - drawn where a host can reach them without the composer or the notice bar.
 *
 * <p>It exists because the live conversation is still `ConversationFragment` and the host read
 * ([ConversationRead], [ConversationHost.PageInputs]) has to draw the rows
 * `MessageSnapshots.watch` delivers while the composer stays the Java one: [ConversationScreen]
 * is the whole destination and would draw a second composer beside it. The split is a seam, not a
 * second list - [ConversationScreen] calls this and draws everything else around it, and no other
 * composable draws a different message list.
 *
 * <p>It owns the jump-to-latest control too, because the control is a fact about *this* list's
 * bottom: it is drawn while the newest row is not fully visible and it moves the list its own state is
 * read from. [scrollTo] is the host's named-row request, [onScrolled] consumes it, [unreadCount] is
 * the badge the fragment counts, and [onJumpToLatest] is the one effect the control cannot perform -
 * leaving a history part that has only loaded an older page - which only a live host has.
 */
@Composable
fun ConversationMessages(
    state: ChatState,
    events: ConversationEvents,
    now: Long,
    modifier: Modifier = Modifier,
    avatar: ConversationAvatar? = null,
    avatarsOn: Boolean = false,
    colorful: Boolean = true,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
    scrollTo: MessageId? = null,
    onScrolled: () -> Unit = {},
    unreadCount: Int = 0,
    onJumpToLatest: () -> Unit = {},
) {
    val rows = state.rows
    val context = LocalContext.current
    // The reading aid's one piece of screen state: the card that is open, or none. It lives here so
    // the card is drawn over the whole list rather than clipped inside a row, and so a tap on a word
    // anywhere in the list replaces it (the lookup supersedes, `GlossLookups`).
    var open by remember { mutableStateOf<GlossOpen?>(null) }
    Box(modifier = modifier) {
        when {
            rows == null -> ConversationLoading(Modifier.fillMaxSize())
            rows.isEmpty() -> ConversationExplainer(Modifier.fillMaxSize())
            else ->
                MessageList(
                    state = state,
                    rows = rows,
                    events = events,
                    now = now,
                    modifier = Modifier.fillMaxSize(),
                    avatar = avatar,
                    avatarsOn = avatarsOn,
                    colorful = colorful,
                    locale = locale,
                    zone = zone,
                    // The owner's deliberate tap is the only way in: the projector decided which words
                    // exist, and this is the request a tap makes - one word, in the sentence it was
                    // tapped in, with the card drawn while the answer is out.
                    onGloss = { word, sentence, anchor ->
                        val next = GlossOpen(word.word, sentence, anchor)
                        open = next
                        GlossLookup.request(
                            context,
                            word.word,
                            sentence,
                            object : GlossLookup.Callback {
                                override fun onGloss(gloss: Gloss) {
                                    if (open === next) next.content = GlossContent.of(gloss)
                                }

                                override fun onFailure(reason: HeldSend.HoldReason) {
                                    if (open === next) next.content = GlossContent.failed(next.word, reason)
                                }

                                override fun onNothingToGloss() {
                                    if (open === next) next.content = GlossContent.nothing(next.word)
                                }
                            },
                        )
                    },
                    onDismissGloss = { open = null },
                    scrollTo = scrollTo,
                    onScrolled = onScrolled,
                    unreadCount = unreadCount,
                    onJumpToLatest = onJumpToLatest,
                )
        }
        open?.let { card -> GlossOverlay(card) { open = null } }
    }
}

/**
 * §3.6's two screen-level lines: the account's own state and the queue's, in that order, because a
 * reconnect explains a translation that is not happening. `CONNECTED` with nothing in flight draws
 * nothing at all, which is the ordinary case and the reason this is not a permanent bar.
 */
@Composable
private fun ConversationStatus(connection: UiConnection, translationActivity: Int) {
    when {
        connection == UiConnection.DISCONNECTED ->
            StatusLine(stringResource(R.string.tulkki_list_disconnected), MaterialTheme.colorScheme.error)
        connection == UiConnection.CONNECTING ->
            StatusLine(stringResource(R.string.tulkki_list_connecting), MaterialTheme.colorScheme.onSurfaceVariant)
        translationActivity > 0 ->
            StatusLine(stringResource(R.string.tulkki_chat_translating), MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Unit
    }
}

/**
 * Item 17's decision one, as the one composable that draws it: every [UiNotice] the state carries,
 * in order, above the composer.
 *
 * <p>It is public because the live conversation is still `ConversationFragment`, which hosts this
 * surface inside a `ComposeView` through `ConversationHost` - the interim bridge `ui-9` leaves behind
 * until the whole screen is Compose. The screen and the host therefore draw the same banner and not
 * two: the host hands the notices this composable and nothing else.
 */
@Composable
fun ConversationNotices(notices: List<UiNotice>, onAction: (NoticeAction) -> Unit) {
    for (notice in notices) {
        NoticeBar(notice = notice, onAction = onAction)
    }
}

/**
 * Item 17's decision one - "make it loud": a conversation-level banner that names the reason with the
 * fix inline.
 *
 * <p>It sits above the composer, which is where the tree drew its own bar (upstream's `snackbar` view,
 * the same one the drop-holder used) and what §3.6 calls "a composer-bar notice". The sentence is the
 * app's at-rest vocabulary and the action is the fix; the banner decides neither, and
 * [ConversationNotice] is where the choice between them lives.
 *
 * <p>**The plate is content-sized, not a full-width shout.** It was one: a `fillMaxWidth` row on a
 * full-bleed `secondaryContainer` band, which read as a second status line across the whole screen
 * rather than a note beside what it is about. It is now the sentence and its button in their own
 * rounded box, wrapped to what they measure - the notices themselves, their order and their wording
 * are unchanged.
 *
 * <p>**Deliberately not drawn: a dismiss affordance.** The tree's bar had a close button
 * (`tulkki_bar_dismiss`), and hiding a line is a piece of host state this surface does not have. The
 * banner is a statement recomputed from the conversation, so it goes when the state it states goes -
 * which is what the tree's own resting bar already did on the next refresh regardless of the close.
 */
@Composable
private fun NoticeBar(notice: UiNotice, onAction: (NoticeAction) -> Unit) {
    Row(
        modifier =
            Modifier.padding(horizontal = TulkkiSpacing.lg, vertical = TulkkiSpacing.xs)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(notice.words),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        for (action in notice.actions) {
            TextButton(onClick = { onAction(action) }) {
                Text(text = stringResource(action.label))
            }
        }
    }
}

@Composable
private fun StatusLine(words: String, colour: Color) {
    Text(
        text = words,
        style = MaterialTheme.typography.labelMedium,
        color = colour,
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = TulkkiSpacing.lg, vertical = TulkkiSpacing.xs),
    )
}

/**
 * §3.6's loading state: "a top-anchored progress row, never a blank list", so the reader is told the
 * read is coming rather than shown an empty conversation.
 */
@Composable
internal fun ConversationLoading(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(TulkkiSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(TulkkiSpacing.md))
        Text(
            text = stringResource(R.string.tulkki_chat_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * §4.6's "empty state that removes itself": the read landed and there is nothing, so the screen
 * explains the one thing a new conversation is about - and says nothing at all once a row exists,
 * because this composable is not in the tree then.
 */
@Composable
private fun ConversationExplainer(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(TulkkiSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card {
            Column(modifier = Modifier.padding(TulkkiSpacing.lg)) {
                Text(
                    text = stringResource(R.string.tulkki_chat_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(TulkkiSpacing.sm))
                Text(
                    text = stringResource(R.string.tulkki_chat_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * §4.2's day headers and the rows, from [ChatItems]' own item list.
 *
 * <p>The day header is the `stickyHeader` the design names as Compose's platform-supported answer to
 * "draw, not insert", and the list's state is anchored by §4.3's rule rather than by a plain
 * `rememberLazyListState`: Tulkki's catch-up pass translates a whole gap at once, so rows change
 * under a reader who is not at the bottom and the list must not move them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
    state: ChatState,
    rows: List<UiMessage>,
    events: ConversationEvents,
    now: Long,
    modifier: Modifier,
    avatar: ConversationAvatar?,
    avatarsOn: Boolean,
    colorful: Boolean,
    locale: Locale,
    zone: ZoneId,
    onGloss: (UiGlossWord, String, Rect) -> Unit,
    onDismissGloss: () -> Unit,
    scrollTo: MessageId?,
    onScrolled: () -> Unit,
    unreadCount: Int,
    onJumpToLatest: () -> Unit,
) {
    val words = rememberWords()
    val items =
        ChatItems.of(rows, state.unreadAnchor) { index ->
            DayLabel.of(rows[index].time, now, words, locale, zone)
        }
    // §4.3 anchors on the *items*' ids, because those are the indices a `LazyListState` reports.
    val anchored = rememberAnchoredListState(items.map { it.key })
    val listState = anchored.state
    val currentItems by rememberUpdatedState(items)
    // The card is anchored to a word, not to the list: a scroll moves the word out from under it, so
    // the reading aid closes, exactly as the deleted popup closed on the conversation's scroll. That
    // dismissal now moves with the list that is actually on screen, and it is also where the Java
    // list's touch-outside contract ended up: the Compose card's own overlay takes the tap the Java
    // listener used to catch.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                onDismissGloss()
                AnchoredPopup.dismiss()
            }
        }
    }
    // The viewport facts the deleted Java `OnScrollListener` read off the `ListView`: the bottom-most
    // drawn row, whether the newest row is fully on screen, and whether the first is. They are the
    // host's (`ConversationEvents.onViewport`): the read receipt, the jump-to-latest control and the
    // older page are its effects, and the arithmetic that used to run through a `GONE` list view now
    // runs through the list that draws.
    //
    // It is keyed on the state alone and reads the items through `currentItems`, so a recomposition
    // that produced the same item list does not restart the subscription and re-fire the host's
    // effects; `distinctUntilChanged` is then free to collapse the frames that changed nothing.
    LaunchedEffect(listState) {
        snapshotFlow {
                val visible = listState.layoutInfo.visibleItemsInfo
                val rows = currentItems
                Viewport(
                    lastVisibleUuid =
                        visible.asReversed().firstNotNullOfOrNull { info ->
                            (rows.getOrNull(info.index) as? ChatItem.Row)?.message?.id?.uuid
                        },
                    // §4.3's own fact, not "the last item's top has been drawn": a row whose bottom
                    // is still cut is not on screen, and saying it was hid the control while the
                    // newest message was half-hidden.
                    atBottom = anchored.atBottom,
                    atStart = visible.firstOrNull()?.index == 0,
                )
            }
            .distinctUntilChanged()
            .collect { events.onViewport(it.lastVisibleUuid, it.atBottom, it.atStart) }
    }
    // The host's jump-to-message request, which the Java jumps (a reply's target, the first unread
    // row, a pinned message) cannot perform on a `LazyListState`. The row is named and this is the
    // screen's half; the request is consumed whether or not the row is in the drawn window. The
    // follow is the request's own answer: a jump to the newest row means the reader is following it
    // again, any other row means they are not.
    LaunchedEffect(scrollTo, items) {
        val target = scrollTo ?: return@LaunchedEffect
        val index = items.indexOfFirst { it is ChatItem.Row && it.message.id == target }
        if (index >= 0) {
            anchored.jumpTo(index, follow = index == items.lastIndex)
        }
        onScrolled()
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = TulkkiSpacing.sm),
        ) {
            for (item in items) {
                when (item) {
                    is ChatItem.Day -> stickyHeader(key = item.key) { DayHeader(item.label) }
                    is ChatItem.Row ->
                        item(key = item.key) {
                            MessageRow(
                                row = item.message,
                                run = BubbleRun.of(item.message.run, avatarsOn = avatarsOn),
                                tone = BubbleTone.of(item.message.direction, item.message.encryption, colorful),
                                unreadCount = item.unreadCount,
                                events = events,
                                now = now,
                                avatar = avatar,
                                locale = locale,
                                zone = zone,
                                onGloss = onGloss,
                                onDismissGloss = onDismissGloss,
                            )
                        }
                }
            }
        }
        // The jump-to-latest control is the list's own drawing now, not the Java `FloatingActionButton`
        // that was anchored to a `GONE` list view and floated near the top of the screen. It is drawn
        // exactly while the newest row is not fully visible - §4.3's own bottom fact - and it jumps to
        // the true end of the list, asking the host to load the latest page first when the reader is
        // inside history.
        if (!anchored.atBottom) {
            ScrollToBottom(
                unreadCount = unreadCount,
                onClick = {
                    onJumpToLatest()
                    if (items.isNotEmpty()) {
                        anchored.jumpTo(items.lastIndex, follow = true)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(TulkkiSpacing.lg),
            )
        }
    }
}

/**
 * §4.3's jump-to-latest control: a round, tonal affordance over the list's end with the count of
 * messages that arrived while the reader was away, and the whole of the control's action.
 *
 * <p>It replaces the Java `FloatingActionButton` the fragment showed and hid: that view was aligned to
 * the bottom of `messages_view` in a `RelativeLayout`, and the list view is `GONE` behind the Compose
 * list, so its anchor was a zero-height box near the top - the control the owner saw "floating
 * somewhere at the top of the screen and moving about randomly". Drawing it inside the list means the
 * fact that shows it and the list it moves are the same state.
 */
@Composable
private fun ScrollToBottom(unreadCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val words = stringResource(R.string.pref_scroll_to_bottom)
    BadgedBox(
        modifier = modifier,
        badge = {
            if (unreadCount > 0) {
                Badge { Text(unreadCount.toString()) }
            }
        },
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            // The button's own node carries the description, so the icon inside it does not repeat it.
            modifier = Modifier.semantics { contentDescription = words },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard_double_arrow_down_24dp),
                contentDescription = null,
            )
        }
    }
}

/** §4.2's drawn label: the day, centred, on the surface the pinned header sits on. */
@Composable
private fun DayHeader(label: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = TulkkiSpacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * §4.3's anchoring, wired: the reader's place is taken against the item keys before they change and
 * given back after, by [Anchor]'s own rules. The decision and its cells are `Anchor`'s; this is only
 * the screen's half - the `LazyListState` a JVM test cannot reach.
 *
 * <p>**"Follow the newest row" is the reader's own answer, and it is written only when the reader's
 * hand finished moving.** `pinned` is this list's whole opinion about the bottom: true means the
 * newest row is the one the reader is reading, so a rows change, a row that grew, an arrival or the
 * keyboard has to carry them down with it. The pin is written **only at the end of a scroll the
 * reader's own hand was moving** ([Anchor.settled]: the position moved, `isScrollInProgress` was
 * true and is now false) - not during the motion, not on a reading a grown layout rewrote, and not on
 * a scroll this screen started (the opening, a jump, this control). Those other position changes are
 * exactly what the deleted shape read: its own re-anchoring scroll arrived as "the reader scrolled
 * away", wrote `pinned = false`, and every later row change then handed the reader to [Anchor.after]
 * instead of the newest row - the list that hides the last few messages and then drifts upwards.
 * `reading.canScrollForward` is the whole answer when the hand stops: false means the last row is on
 * screen and the newest row is the one being read.
 *
 * <p>**The pin survives a layout that grew, and that is what re-anchors it.** A position change
 * cannot be caused by growth - an appended row does not move `firstVisibleItemIndex` or its offset -
 * so growth never writes the pin. But growth *does* push the newest row below the fold, so while
 * pinned and no longer fully visible the list is scrolled back to its last item. That is the whole of
 * "a tap or an arrival never leaves the newest messages hidden": a translation replacing a cover
 * changes that row's height without changing any key, and the old shape only re-anchored on a key
 * change.
 *
 * <p>**`changed` is "the list's own shape moved", and the keyboard is one of those shapes.**
 * The item keys changing is a rows change; the viewport's height changing is a rotation or the emoji
 * panel; and the IME's own height is read as its own fact, so the keyboard's arrival counts as a
 * reason to re-anchor whether or not it also moved the viewport - a host whose list keeps its box and
 * only pads its content would otherwise never see the keyboard at all. Both re-anchor: pinned, the
 * list goes to the last key (so the newest row stays at the bottom when the keyboard takes the
 * viewport's lower half); not pinned, [Anchor.after] hands the reader back the row they were on,
 * because opening the keyboard is the owner asking to write, not an instruction to leave the row they
 * were reading. The opening is its own sentinel, so a conversation still opens at its latest
 * message rather than its oldest.
 *
 * <p>The reader's place is recorded for the not-pinned case under the item-count guard the KDoc of the
 * old shape named: a reading taken from a layout whose rows just changed is not a place, because the
 * same index then names a different row. The guard is no longer asked whether the reader was at the
 * bottom - [Anchor.At.atBottom] is [Anchor]'s own field and this function no longer consults it for
 * that decision - so it can no longer misfire into the pin.
 */
@Composable
private fun rememberAnchoredListState(keys: List<String>): AnchoredList {
    val listState = rememberLazyListState()
    val currentKeys by rememberUpdatedState(keys)
    // The keyboard's own height, which is a shape fact of the list and not a reader move. A host that
    // already consumed the inset for its own bar reports zero here, which is the honest answer: this
    // list was not inset, so nothing about it changed.
    val currentIme by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current))
    // A jump this screen was asked for - the host's named row, or the control's own end - held as a
    // request so the one collector performs it: a programmatic move has to write the pin itself, and
    // it can only do that where the pin lives.
    val request = remember { mutableStateOf<Jump?>(null) }
    // §4.3's bottom fact, and the one the control is drawn from: the list can scroll no further, or
    // the newest row is fully on screen. The two together are the honest answer on both sides of the
    // content padding: a list whose last row is whole but whose own end padding is not scrolled in is
    // still at its bottom, and a list that can scroll is not - so the control is drawn only when the
    // newest row is actually cut. An unmeasured list counts as the bottom, so it does not blink on the
    // first frame.
    val bottom = remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            if (layout.totalItemsCount == 0 || !listState.canScrollForward) {
                true
            } else {
                val last = layout.visibleItemsInfo.lastOrNull()
                Anchor.newestFullyVisible(
                    lastIndex = layout.totalItemsCount - 1,
                    lastVisibleIndex = last?.index ?: -1,
                    lastVisibleBottom = if (last == null) 0 else last.offset + last.size,
                    viewportEnd = layout.viewportEndOffset,
                )
            }
        }
    }
    LaunchedEffect(listState) {
        // The reader's place, for the one case that must not move: a reader who scrolled away.
        var place: Anchor.At? = null
        // The item count the place above was recorded against: a grown layout is not a scroll.
        var placeCount = -1
        // Whether the newest row is the one the reader is following. See the KDoc: only the end of
        // the reader's own scroll may write it.
        var pinned = true
        // §4.3's opening, held apart from the place: it is *only* the opening.
        var opened = false
        var lastKeys: List<String> = emptyList()
        var lastViewport = -1
        var lastIme = -1
        var lastPosition: Pair<Int, Int>? = null
        var wasScrolling = false
        snapshotFlow {
                ScrollReading(
                    keys = currentKeys,
                    position =
                        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                    viewport = listState.layoutInfo.viewportSize.height,
                    ime = currentIme,
                    canScrollForward = listState.canScrollForward,
                    scrolling = listState.isScrollInProgress,
                    jump = request.value,
                )
            }
            .distinctUntilChanged()
            .collect { reading ->
                val layout = listState.layoutInfo
                val visible = layout.visibleItemsInfo
                // The list's own shape moved: content, the space it is drawn in, or the keyboard.
                val changed =
                    reading.keys != lastKeys ||
                        reading.viewport != lastViewport ||
                        reading.ime != lastIme
                // The shape facts move on every reading, so a change that lands while the reader's
                // hand is still moving is consumed there and does not read as a fresh change at the
                // moment the scroll ends; only then is the pin - and the move - decided.
                lastKeys = reading.keys
                lastViewport = reading.viewport
                lastIme = reading.ime
                if (!reading.scrolling) {
                    val moved = !changed && reading.position != lastPosition
                    if (Anchor.settled(
                            moved = moved,
                            scrolling = reading.scrolling,
                            wasScrolling = wasScrolling,
                        )
                    ) {
                        // The reader's hand has stopped. `reading.canScrollForward` is the whole
                        // answer: false means the last row is on screen, so the newest row is the one
                        // being read.
                        pinned = !reading.canScrollForward
                    }
                    if (!changed && visible.isNotEmpty() && layout.totalItemsCount == placeCount) {
                        place = Anchor.before(reading.keys, visible.first().index, visible.last().index)
                    }
                    placeCount = layout.totalItemsCount
                    val last = visible.lastOrNull()
                    val newest =
                        layout.totalItemsCount > 0 &&
                            Anchor.newestFullyVisible(
                                lastIndex = layout.totalItemsCount - 1,
                                lastVisibleIndex = last?.index ?: -1,
                                lastVisibleBottom = if (last == null) 0 else last.offset + last.size,
                                viewportEnd = layout.viewportEndOffset,
                            )
                    val jump = reading.jump
                    when {
                        reading.keys.isEmpty() -> Unit
                        // A named row wins over the opening: a jump into history is the whole point
                        // of it, and the reader asked for that row rather than the newest one.
                        jump != null -> {
                            request.value = null
                            opened = true
                            pinned = jump.follow
                            listState.scrollToItem(jump.index)
                        }
                        // The tree's `TRANSCRIPT_MODE_NORMAL`: a conversation opens at its latest
                        // message rather than at its oldest, and a reader who scrolled away is never
                        // pulled back.
                        !opened -> {
                            listState.scrollToItem(reading.keys.lastIndex)
                            opened = true
                            pinned = true
                        }
                        changed && pinned -> listState.scrollToItem(reading.keys.lastIndex)
                        // The pin also survives a layout that grew: a row whose height changed under
                        // a following reader - a translation replacing a cover - leaves the newest row
                        // below the fold without changing a single key, and this is what carries it
                        // back into view. `canScrollForward` keeps an over-tall last row from asking
                        // for a scroll that cannot happen.
                        pinned && !newest && reading.canScrollForward ->
                            listState.scrollToItem(reading.keys.lastIndex)
                        changed -> {
                            val before = place
                            if (before != null) {
                                val target = Anchor.after(before, reading.keys)
                                if (target != 0) {
                                    listState.scrollToItem(target)
                                }
                            }
                        }
                    }
                    lastPosition = reading.position
                }
                wasScrolling = reading.scrolling
            }
    }
    return remember(listState, bottom) {
        AnchoredList(state = listState, bottom = bottom) { index, follow ->
            request.value = Jump(index, follow)
        }
    }
}

/**
 * The anchored list, as [MessageList] needs it: the state the `LazyColumn` draws, §4.3's bottom fact,
 * and the one way to ask it to move.
 */
private class AnchoredList(
    val state: LazyListState,
    private val bottom: State<Boolean>,
    private val request: (Int, Boolean) -> Unit,
) {
    /** The newest row is fully on screen - or the list has not been measured yet. */
    val atBottom: Boolean get() = bottom.value

    /**
     * Bring [index] on screen. [follow] is the reader's new answer about the newest row: true for the
     * control's own end, true for a jump to the last row, false for a jump into history.
     */
    fun jumpTo(index: Int, follow: Boolean) = request(index, follow)
}

/** A named row the screen was asked to bring on screen, and whether that means following the newest. */
private data class Jump(val index: Int, val follow: Boolean)

/**
 * One reading of the list's own scroll state: the facts that may re-anchor it, and the pin.
 *
 * <p>[position] is what the reader's hand moves and growth does not; [keys], [viewport] and [ime] are
 * the list's shape, so a change in any of them is the list moving under the reader rather than the
 * reader moving. [canScrollForward] rides along because it is the pin's whole answer and it has to be
 * true of *this* reading: a read of the state while the collector runs can belong to a later frame.
 * [scrolling] separates the reader's hand from the two programmatic moves - this list's own
 * re-anchoring scroll and a [Jump] - which must never be read as the reader leaving the bottom.
 * [jump] is the request the collector answers, held in the reading so the one coroutine performs
 * every scroll. Keeping them one value lets `distinctUntilChanged` collapse the frames that change
 * nothing.
 */
private data class ScrollReading(
    val keys: List<String>,
    val position: Pair<Int, Int>,
    val viewport: Int,
    /** The keyboard's own height: the shape fact a host that keeps the list's box reports alone. */
    val ime: Int,
    /** The pin's whole answer, taken in the same snapshot as [position] rather than beside it. */
    val canScrollForward: Boolean,
    /** Whether a scroll is in flight at all: the reader's hand, or one this screen started. */
    val scrolling: Boolean,
    /** The host's or the control's named row, or `null` while there is no request. */
    val jump: Jump?,
)


/** The list's viewport, as the three facts [ConversationEvents.onViewport] carries. */
private data class Viewport(
    val lastVisibleUuid: String?,
    val atBottom: Boolean,
    val atStart: Boolean,
)

@Composable
private fun MessageRow(
    row: UiMessage,
    run: BubbleRun,
    tone: BubbleTone,
    unreadCount: Int?,
    events: ConversationEvents,
    now: Long,
    avatar: ConversationAvatar?,
    locale: Locale,
    zone: ZoneId,
    onGloss: (UiGlossWord, String, Rect) -> Unit,
    onDismissGloss: () -> Unit,
) {
    val incoming = row.direction == Direction.INCOMING
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = TulkkiSpacing.sm,
                    end = TulkkiSpacing.sm,
                    top = run.gapAbove,
                    bottom = run.gapBelow,
                )
    ) {
        // The column's own width comes off the ceiling first: an own row's avatar is on the far side
        // and a bubble that ran to 82% of the whole row would overflow the space that is left.
        val avatarSpace = dimensionResource(R.dimen.bubble_avatar_size) + TulkkiSpacing.sm
        val bubbleCeiling =
            if (run.avatar == AvatarPlacement.GONE) {
                maxWidth * MAX_BUBBLE_OF_WIDTH
            } else {
                (maxWidth - avatarSpace) * MAX_BUBBLE_OF_WIDTH
            }
        // §4.4: rightward only, damped at the wall, and armed before the wall is reached, so the reply
        // fires while the bubble still moves and the haptic is felt as the action becomes possible.
        val width = constraints.maxWidth.toFloat()
        var drag by remember { mutableFloatStateOf(0f) }
        var wasArmed by remember { mutableStateOf(false) }
        val pulled = SwipeToReply.offset(drag, width)
        Box(modifier = Modifier.fillMaxWidth()) {
            if (pulled > 0f) {
                Icon(
                    painter = painterResource(R.drawable.ic_reply_24dp),
                    contentDescription = stringResource(R.string.tulkki_chat_replying),
                    tint =
                        MaterialTheme.colorScheme.primary
                            .copy(alpha = SwipeToReply.armProgress(pulled, width)),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = TulkkiSpacing.sm),
                )
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .offset { IntOffset(pulled.roundToInt(), 0) }
                        .pointerInput(row.id.uuid, width) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (SwipeToReply.armed(SwipeToReply.offset(drag, width), width)) {
                                        events.onReply(row.id.uuid)
                                    }
                                    drag = 0f
                                    wasArmed = false
                                },
                                onDragCancel = {
                                    drag = 0f
                                    wasArmed = false
                                },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    val armed = SwipeToReply.armed(SwipeToReply.offset(drag, width), width)
                                    if (armed && !wasArmed) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    wasArmed = armed
                                },
                            )
                        },
                horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                if (incoming) {
                    AvatarColumn(
                        run.avatar,
                        row,
                        avatar,
                        onTap = events::onAvatarTap,
                        onLongPress = events::onAvatarLongPress,
                    )
                }
                MessageBubble(
                    row = row,
                    tone = tone,
                    unreadCount = unreadCount,
                    events = events,
                    now = now,
                    locale = locale,
                    zone = zone,
                    onGloss = onGloss,
                    onDismissGloss = onDismissGloss,
                    ceiling = bubbleCeiling,
                    modifier = Modifier.widthIn(max = bubbleCeiling),
                )
                if (!incoming) {
                    AvatarColumn(
                        run.avatar,
                        row,
                        avatar,
                        onTap = events::onAvatarTap,
                        onLongPress = events::onAvatarLongPress,
                    )
                }
            }
        }
    }
}

/**
 * The row's avatar, or the column it will hold. [AvatarPlacement.GONE] composes nothing at all, which
 * is the tree's one "no space" case; [AvatarPlacement.RESERVED] composes the column and **nothing in
 * it**, because §4.1's reservation is "INVISIBLE (space kept)" - the bubbles of a run stay on one edge
 * and the owner sees no avatar there; [AvatarPlacement.DRAWN] composes the image, or the plate it will
 * cover while the host has none yet.
 *
 * <p>**The plate belongs to the drawn placement and not to the reserved one.** It was drawn for both,
 * which put a grey circle beside every message that was not its run's edge - a cell that fills where it
 * should not, the same class of defect as a bubble that stretches to a width nobody asked for. A host
 * with no image yet still gets its plate: that row *is* the one that asks for an avatar.
 *
 * <p>**The two gestures are the tree's.** A tap is `onContactPictureClicked` (the thread, a room
 * occupant's private chat, the room highlight) and a long press is `onContactPictureLongClicked` (the
 * contact/account context menu); both are the host's verbs, named from this row's uuid. They are
 * nullable exactly as [ReactionRow]'s pair is: a caller with no host (a preview, a JVM cell) draws
 * the same avatar pixels and no touch target at all, because a drawn affordance that performs nothing
 * is worse than an absent one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AvatarColumn(
    placement: AvatarPlacement,
    row: UiMessage,
    avatar: ConversationAvatar?,
    onTap: ((String) -> Unit)? = null,
    onLongPress: ((String) -> Unit)? = null,
) {
    if (placement == AvatarPlacement.GONE) {
        return
    }
    val shape = chatAvatarShape(avatar)
    val answerable = onTap != null || onLongPress != null
    Box(
        modifier =
            Modifier.padding(horizontal = TulkkiSpacing.xs)
                .size(dimensionResource(R.dimen.bubble_avatar_size))
                .then(
                    if (answerable) {
                        Modifier.combinedClickable(
                            onClick = { onTap?.invoke(row.id.uuid) },
                            onLongClick = { onLongPress?.invoke(row.id.uuid) },
                        )
                    } else {
                        Modifier
                    }
                )
    ) {
        if (placement == AvatarPlacement.DRAWN) {
            val image = avatar?.of(row.conversationId.uuid)
            if (image != null) {
                Canvas(modifier = Modifier.fillMaxSize().clip(shape)) {
                    drawIntoCanvas { canvas ->
                        image.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        image.draw(canvas.nativeCanvas)
                    }
                }
            } else {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

/** The host's `avatar_shape`, as the clip this row draws with; a host with no avatar names the oval. */
@Composable
private fun chatAvatarShape(avatar: ConversationAvatar?): Shape =
    when (avatar?.shape() ?: AvatarShape.OVAL) {
        AvatarShape.OVAL -> CircleShape
        AvatarShape.ROUNDED_SQUARE -> RoundedCornerShape(dimensionResource(R.dimen.avatar_corners_radius))
        AvatarShape.SQUARE -> RectangleShape
    }

@Composable
private fun MessageBubble(
    row: UiMessage,
    tone: BubbleTone,
    unreadCount: Int?,
    events: ConversationEvents,
    now: Long,
    locale: Locale,
    zone: ZoneId,
    onGloss: (UiGlossWord, String, Rect) -> Unit,
    onDismissGloss: () -> Unit,
    ceiling: Dp,
    modifier: Modifier,
) {
    val words = rememberWords()
    val (background, foreground) = toneColours(tone)
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val textPx = with(density) { bodyStyle.fontSize.toPx() }
    val linePx = with(density) { bodyStyle.lineHeight.toPx() }
    val lineDp = with(density) { bodyStyle.lineHeight.toDp() }
    // A bubble with words of its own is exactly as wide as them - no wider than its text plus its
    // padding, which is the whole of "aligned" for a row. A bubble whose only body is a cover has no
    // words to measure (a placeholder carries none on purpose, §6.1), so it would collapse onto its
    // timestamp; it takes a fixed share of the ceiling instead.
    val hasWords = row.top is UiBody.Visible || row.bottom is UiBody.Visible
    val floor = if (hasWords) 0.dp else ceiling * COVER_OF_CEILING
    // The bubble and the reaction row below it sit on the same edge, so a chip group wider than the
    // bubble cannot drag the bubble off the side it belongs to.
    Column(
        modifier = modifier,
        horizontalAlignment =
            if (row.direction == Direction.INCOMING) Alignment.Start else Alignment.End,
    ) {
        if (unreadCount != null) {
            UnreadPill(unreadCount)
        }
        Column(
            modifier =
                // **`width(IntrinsicSize.Max)`: the bubble is sized by its own words, and the cells that
                // fill it are measured *into* that size instead of setting it.** This is the alignment
                // defect the row had: Material3's `HorizontalDivider` fills its parent by default, so the
                // divider between the two halves of a bubble stretched every two-half bubble to the
                // ceiling - a five-word sent message drew a bubble spanning the row with its text at the
                // far left, while a *single-half* bubble was correctly narrow and right-aligned. The same
                // held for the bare English bar. Neither has words to be measured by, so neither may
                // decide a width; the intrinsic pass measures the text, fixes it, and the divider, the
                // cover, the quote's own column and the rest then fill exactly that.
                Modifier.widthIn(min = floor)
                    .width(IntrinsicSize.Max)
                    .clip(TulkkiShape.bubble)
                    .background(background)
                    .then(
                        if (row.selected) {
                            Modifier.border(TulkkiSpacing.xxs, MaterialTheme.colorScheme.primary, TulkkiShape.bubble)
                        } else {
                            Modifier
                        }
                    )
                    .combinedClickable(
                        // A tap on the bubble that is not a word means "translate this one, now":
                        // the card that was open belongs to the word the owner has moved on from.
                        onClick = {
                            onDismissGloss()
                            events.onBodyTap(row.id.uuid)
                        },
                        onLongClick = {
                            onDismissGloss()
                            events.onLongPress(row.id.uuid)
                        },
                    )
                    .padding(
                        horizontal = dimensionResource(R.dimen.bubble_horizontal_padding),
                        vertical = dimensionResource(R.dimen.bubble_vertical_padding),
                    )
        ) {
            row.quote?.let { quote ->
                QuoteStrip(quote, foreground, events, linePx, textPx, lineDp, bodyStyle)
            }
            // §3.6's attachment cell. A transfer row's `top` is `Absent` (its body is an address or a
            // path, never prose), so without this the row is a timestamp and nothing else - the defect
            // this cell answers. It sits where the body would, because for this row it *is* the body.
            row.attachment?.let { attachment -> AttachmentCell(attachment, foreground) }
            BodyHalf(row.top, foreground, bodyStyle, textPx, linePx, lineDp, onTranslate = { events.onBodyTap(row.id.uuid) }, gloss = row.gloss, onGloss = onGloss)
            val bottom = row.bottom
            if (bottom != null) {
                if (row.divider) {
                    HorizontalDivider(
                        color = foreground.copy(alpha = DIVIDER_OF_FOREGROUND),
                        modifier = Modifier.padding(vertical = TulkkiSpacing.xs),
                    )
                }
                BodyHalf(bottom, foreground, bodyStyle, textPx, linePx, lineDp, onTranslate = { events.onBodyTap(row.id.uuid) })
            }
            OfferedOriginal(row, foreground, bodyStyle, textPx, linePx, lineDp, events)
            EnglishRow(row, foreground, bodyStyle, textPx, linePx, lineDp, events)
            Text(
                text = ConversationRowTime.of(row.time, now, relative = false, words = words, locale = locale, zone = zone),
                style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = META_OF_FOREGROUND),
                modifier = Modifier.align(Alignment.End).padding(top = TulkkiSpacing.xxs),
            )
        }
        // §3.6's reaction row, below the bubble on the bubble's own side. The projector has already
        // decoded and grouped the document; this only draws what it answered, and the two gestures
        // are the host's verbs - the row names the emoji, the host rebuilds the set.
        ReactionRow(
            reactions = row.reactions,
            modifier = Modifier.padding(top = TulkkiSpacing.xs),
            onReaction = { events.onReaction(row.id.uuid, it.emoji) },
            onPicker = { events.onReactionPicker(row.id.uuid) },
        )
    }
}

/**
 * §3.6's attachment cell: the one thing a file or image row draws in place of a body, and the four
 * states it is honest about.
 *
 * <p>**The state is the cell's own field and it is the whole of the branch.** *In flight*
 * (`Offered`/`Downloading`/`Uploading`/`Checking`) draws the transfer's direction, its progress where
 * there is one and the size; *ready* (`Ready`) draws the attachment itself - pixels for an image the
 * host supplied a thumbnail for, otherwise a named plate - and a name and size for any file; *failed*
 * (`Failed`) and *cancelled* draw the tree's own two sentences; and `None` **inside this cell** is the
 * fourth state - the row is a transfer with nothing on the phone yet, and it draws the remote
 * attachment's name, size and a download mark. A completed transfer and an offer therefore no longer
 * draw the same empty row, which is the defect this cell answers.
 *
 * <p>**Nothing here is a tap target of its own.** Opening or fetching the file is the host's verb and no
 * `ConversationEvents` member carries it yet, so the cell draws the affordance and the bubble's own tap
 * stands unaltered: inventing an action would be worse than the named hole.
 */
@Composable
private fun AttachmentCell(attachment: UiAttachment, foreground: Color) {
    Column(modifier = Modifier.padding(vertical = TulkkiSpacing.xxs)) {
        when (val state = attachment.state) {
            is UiTransferState.Ready -> ReadyAttachment(attachment, foreground)
            is UiTransferState.Failed ->
                AttachmentPlate(
                    icon = R.drawable.ic_error_24dp,
                    attachment = attachment,
                    foreground = foreground,
                    sentence = stringResource(R.string.file_transmission_failed),
                )
            is UiTransferState.Cancelled ->
                AttachmentPlate(
                    icon = R.drawable.ic_cancel_24dp,
                    attachment = attachment,
                    foreground = foreground,
                    sentence = stringResource(R.string.file_transmission_cancelled),
                )
            is UiTransferState.None ->
                AttachmentPlate(
                    icon = R.drawable.ic_download_24dp,
                    attachment = attachment,
                    foreground = foreground,
                )
            is UiTransferState.Offered,
            is UiTransferState.Downloading,
            is UiTransferState.Uploading,
            is UiTransferState.Checking -> InFlightAttachment(attachment, state, foreground)
        }
    }
}

/** The ready state: pixels where the host built them, a named plate where it did not. */
@Composable
private fun ReadyAttachment(attachment: UiAttachment, foreground: Color) {
    val thumbnail = attachment.thumbnail
    when {
        attachment.kind == AttachmentKind.IMAGE && thumbnail != null ->
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().clip(TulkkiShape.image),
            )
        attachment.kind == AttachmentKind.IMAGE ->
            AttachmentPlate(R.drawable.ic_image_24dp, attachment, foreground)
        else -> AttachmentPlate(R.drawable.ic_attach_file_24dp, attachment, foreground)
    }
}

/**
 * A name, an optional size or sentence, and an icon - the shape every non-pixel state draws.
 *
 * <p>A file's name is a filename and never a message body: it arrives from the payload tree through
 * `MessageFacts.attachment`, so it carries none of the stored text the concealment rules are about.
 */
@Composable
private fun AttachmentPlate(
    @DrawableRes icon: Int,
    attachment: UiAttachment,
    foreground: Color,
    sentence: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(TulkkiSpacing.xl),
        )
        Spacer(modifier = Modifier.width(TulkkiSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name ?: stringResource(attachment.kind.words()),
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = sentence ?: attachment.sizeBytes?.let { sizeWords(it) }
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = META_OF_FOREGROUND),
                )
            }
        }
    }
}

/** The four in-flight states: a determinate bar where there is progress, a sentence either way. */
@Composable
private fun InFlightAttachment(attachment: UiAttachment, state: UiTransferState, foreground: Color) {
    val label = attachment.name ?: stringResource(attachment.kind.words())
    Column {
        when (state) {
            is UiTransferState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress / PERCENT },
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentLine(stringResource(R.string.receiving_x_file, label, state.progress), foreground)
            }
            is UiTransferState.Uploading -> {
                LinearProgressIndicator(
                    progress = { state.progress / PERCENT },
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentLine(stringResource(R.string.sending_x_file, label), foreground)
            }
            is UiTransferState.Offered ->
                AttachmentLine(stringResource(R.string.x_file_offered_for_download, label), foreground)
            is UiTransferState.Checking ->
                AttachmentLine(stringResource(R.string.checking_x, label), foreground)
            else -> Unit
        }
        attachment.sizeBytes?.let { AttachmentLine(sizeWords(it), foreground) }
    }
}

/** One muted line of the cell: the transfer's own sentence, or its size. */
@Composable
private fun AttachmentLine(words: String, foreground: Color) {
    Text(
        text = words,
        style = MaterialTheme.typography.labelMedium,
        color = foreground.copy(alpha = META_OF_FOREGROUND),
        modifier = Modifier.padding(top = TulkkiSpacing.xxs),
    )
}

/**
 * The cell's own size line: four magnitudes, each its own string. The cell is drawn under the
 * screenshot harness as well, so it calls no framework formatter whose resource it does not own.
 */
@Composable
private fun sizeWords(bytes: Long): String =
    when {
        bytes >= GIGA -> stringResource(R.string.tulkki_attachment_size_giga, bytes / GIGA.toDouble())
        bytes >= MEGA -> stringResource(R.string.tulkki_attachment_size_mega, bytes / MEGA.toDouble())
        bytes >= KILO -> stringResource(R.string.tulkki_attachment_size_kilo, bytes / KILO)
        else -> stringResource(R.string.tulkki_attachment_size_bytes, bytes)
    }

/** The generic word for a cell whose host could not name the file. */
@StringRes
private fun AttachmentKind.words(): Int =
    when (this) {
        AttachmentKind.IMAGE -> R.string.image
        AttachmentKind.FILE -> R.string.file
    }

/** §4.2: "the pill carries a number, never text" - the words are the screen reader's only. */
@Composable
private fun UnreadPill(count: Int) {
    val label = stringResource(R.string.tulkki_list_unread)
    Box(
        modifier =
            Modifier.padding(bottom = TulkkiSpacing.xxs)
                .clip(CircleShape)
                .background(LocalTulkkiColors.current.unreadAccent)
                .semantics { contentDescription = label }
                .padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xxs)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * One half of a bubble's body, or the thing that stands in its place.
 *
 * <p>§2.2's typing is the whole of the rule: a [UiBody.Visible] is text, a
 * [UiConcealment.Placeholder] is §6's shimmer with §6.2's caption policy under it (no caption for
 * `TAP` and `TRANSLATING`, the reason for every other cover, because "when the tap cannot translate,
 * the message says why rather than falling back to the raw text"), and a [UiConcealment.Smear] is
 * pixels - an [Image] of a bitmap that carries no text to read back.
 */
@Composable
private fun BodyHalf(
    body: UiBody,
    foreground: Color,
    style: TextStyle,
    textPx: Float,
    linePx: Float,
    lineDp: Dp,
    onTranslate: () -> Unit,
    onReveal: (() -> Unit)? = null,
    gloss: List<UiGlossWord> = emptyList(),
    onGloss: ((UiGlossWord, String, Rect) -> Unit)? = null,
) {
    when (body) {
        is UiBody.Visible ->
            // Only the app-language half a tap can gloss carries words; every other caller of this
            // composable leaves the two at their defaults and draws plain text.
            if (gloss.isNotEmpty() && onGloss != null) {
                GlossTextBody(
                    text = body.text,
                    words = gloss,
                    style = style,
                    color = foreground,
                    onGloss = onGloss,
                )
            } else {
                Text(text = body.text, style = style, color = foreground)
            }
        is UiBody.Concealed ->
            when (val concealment = body.concealment) {
                is UiConcealment.Placeholder -> {
                    val caption = concealment.reason?.takeIf { it != DisplayedBody.Cover.TAP && it != DisplayedBody.Cover.TRANSLATING }
                    // Item 17's decision five: a strip the failure gate offered has its **own** tap, and
                    // the bubble's stays "translate this one, now". A cover's own placeholder never
                    // carries `revealable`, so a cover has only the translate tap.
                    val tap = if (concealment.revealable && onReveal != null) onReveal else onTranslate
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(lineDp * concealment.shape.lines)
                                    .shimmer(
                                        shape = concealment.shape,
                                        reason = concealment.reason,
                                        lineHeight = linePx,
                                        textSize = textPx,
                                        onClick = tap,
                                    )
                        )
                        if (caption != null) {
                            Text(
                                text = stringResource(TranslationText.coverCaption(caption)),
                                style = MaterialTheme.typography.labelSmall,
                                color = foreground.copy(alpha = META_OF_FOREGROUND),
                                modifier = Modifier.padding(top = TulkkiSpacing.xxs),
                            )
                        }
                    }
                }
                is UiConcealment.Smear ->
                    Image(
                        bitmap = concealment.image,
                        contentDescription = null,
                        modifier =
                            Modifier.fillMaxWidth()
                                .then(
                                    if (concealment.revealable && onReveal != null) {
                                        Modifier.clickable(onClick = onReveal)
                                    } else {
                                        Modifier
                                    }
                                ),
                    )
            }
        UiBody.Absent -> Unit
    }
}

/**
 * A reply's quote: the referenced row's translation, with the referenced original as concealed as
 * the message it sits under - `ReplyQuote` already decided which, so nothing here consults a setting.
 */
@Composable
private fun QuoteStrip(
    quote: UiQuote,
    foreground: Color,
    events: ConversationEvents,
    linePx: Float,
    textPx: Float,
    lineDp: Dp,
    style: TextStyle,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min).padding(bottom = TulkkiSpacing.xs)) {
        Box(
            modifier =
                Modifier.width(TulkkiSpacing.xxs)
                    .fillMaxHeight()
                    .clip(TulkkiShape.bubble)
                    .background(foreground.copy(alpha = QUOTE_OF_FOREGROUND))
        )
        Spacer(modifier = Modifier.width(TulkkiSpacing.xs))
        // A quote with no referenced row (`UiQuote.messageId` null: `ReplyQuote.unresolved`) has no row
        // a tap could translate, so its cover is inert rather than pointed at the reply, whose
        // translation would be a different purchase.
        val translate: () -> Unit = quote.messageId?.let { id -> { events.onBodyTap(id.uuid) } } ?: {}
        Column(modifier = Modifier.fillMaxWidth()) {
            BodyHalf(quote.top, foreground, style, textPx, linePx, lineDp, onTranslate = translate)
            val bottom = quote.bottom
            if (bottom != null) {
                if (quote.divider) {
                    HorizontalDivider(
                        color = foreground.copy(alpha = DIVIDER_OF_FOREGROUND),
                        modifier = Modifier.padding(vertical = TulkkiSpacing.xs),
                    )
                }
                BodyHalf(bottom, foreground, style, textPx, linePx, lineDp, onTranslate = translate)
            }
        }
    }
}

/**
 * Item 17's decision five, drawn: the original the failure gate offered, under the covered body.
 *
 * <p>It is the English row's shape, and AGENTS.md says why: the exception "takes the same shape as the
 * row above". Absent is nothing at all; concealed is the strip, whose **own** tap reads it (the bubble's
 * tap still means "translate this one, now"); revealed is the words, which only the projector's own gate
 * and the owner's tap could have put there.
 */
@Composable
private fun OfferedOriginal(
    row: UiMessage,
    foreground: Color,
    style: TextStyle,
    textPx: Float,
    linePx: Float,
    lineDp: Dp,
    events: ConversationEvents,
) {
    when (val original = row.original) {
        UiOriginalRow.Absent -> Unit
        is UiOriginalRow.Concealed ->
            Column(modifier = Modifier.padding(top = TulkkiSpacing.xs)) {
                BodyHalf(
                    body = UiBody.Concealed(original.concealment),
                    foreground = foreground,
                    style = style,
                    textPx = textPx,
                    linePx = linePx,
                    lineDp = lineDp,
                    onTranslate = { events.onBodyTap(row.id.uuid) },
                    onReveal = { events.onRevealOriginal(row.id.uuid) },
                )
            }
        is UiOriginalRow.Visible ->
            Column(modifier = Modifier.padding(top = TulkkiSpacing.xs)) {
                HorizontalDivider(color = foreground.copy(alpha = DIVIDER_OF_FOREGROUND))
                Text(
                    text = original.text,
                    style = style,
                    color = foreground,
                    modifier = Modifier.padding(top = TulkkiSpacing.xs),
                )
            }
    }
}

/**
 * §2b's four English-row states, drawn where the projector put them: nothing at all, the bare bar a
 * tap buys, the bought-and-concealed pixels, and the revealed text.
 *
 * <p>The bar's height is the tree's own 12 dp, and `docs/MIGRATION.md` "Open and unverified" records the open question about it
 * ("nothing has measured whether a 12 dp bar is a comfortable tap target") - so it is a constant with
 * a name rather than a number in the layout, and the question stays open instead of being answered
 * by a guess here.
 */
@Composable
private fun EnglishRow(
    row: UiMessage,
    foreground: Color,
    style: TextStyle,
    textPx: Float,
    linePx: Float,
    lineDp: Dp,
    events: ConversationEvents,
) {
    when (val english = row.english) {
        UiEnglishRow.Absent -> Unit
        UiEnglishRow.Pending -> {
            val hidden = stringResource(R.string.tulkki_english_row_hidden)
            Box(
                modifier =
                    Modifier.padding(top = TulkkiSpacing.xs)
                        .fillMaxWidth()
                        .height(EnglishBarHeight)
                        .clip(TulkkiShape.bubble)
                        .background(LocalTulkkiColors.current.coverStrip)
                        .clickable { events.onEnglishTap(row.id.uuid) }
                        .semantics { contentDescription = hidden }
            )
        }
        is UiEnglishRow.Concealed ->
            Column(modifier = Modifier.padding(top = TulkkiSpacing.xs)) {
                BodyHalf(
                    body = UiBody.Concealed(english.concealment),
                    foreground = foreground,
                    style = style,
                    textPx = textPx,
                    linePx = linePx,
                    lineDp = lineDp,
                    onTranslate = { events.onEnglishTap(row.id.uuid) },
                )
            }
        is UiEnglishRow.Visible ->
            Column(modifier = Modifier.padding(top = TulkkiSpacing.xs)) {
                HorizontalDivider(color = foreground.copy(alpha = DIVIDER_OF_FOREGROUND))
                Text(
                    text = english.text,
                    style = style,
                    color = foreground,
                    modifier = Modifier.padding(top = TulkkiSpacing.xs),
                )
            }
    }
}

/** §1.2's five bubble families as the theme's own tokens, and the text each is written in. */
@Composable
private fun toneColours(tone: BubbleTone): Pair<Color, Color> {
    val tokens = LocalTulkkiColors.current
    return when (tone) {
        BubbleTone.SURFACE -> tokens.bubbleSurface to MaterialTheme.colorScheme.onSurface
        BubbleTone.SURFACE_HIGH -> tokens.bubbleSurfaceHigh to MaterialTheme.colorScheme.onSurface
        BubbleTone.SECONDARY -> tokens.bubbleSecondary to tokens.onBubbleSecondary
        BubbleTone.TERTIARY -> tokens.bubbleTertiary to tokens.onBubbleTertiary
        BubbleTone.WARNING -> tokens.bubbleWarning to tokens.onBubbleWarning
    }
}

/**
 * The projection's own `Context::getString`, narrowed the way [PreviewWords] asks for it - the same
 * one-liner the host uses, and here only because the row's timestamp is `ConversationRowTime`'s and
 * that rule takes its words as a seam so a JVM cell can reach it.
 */
@Composable
private fun rememberWords(): PreviewWords {
    val context = LocalContext.current
    return remember(context) { PreviewWords { id, args -> context.getString(id, *args) } }
}

/** The bubble's ceiling, so an own bubble does not run to the far edge. */
private const val MAX_BUBBLE_OF_WIDTH = 0.82f

/**
 * The share of the ceiling a bubble takes when it has **no words of its own** - a body that is only a
 * cover, whose placeholder carries no text and no pixels of the hidden one (§6.1) and therefore gives
 * the intrinsic pass nothing to measure. Without it such a bubble would collapse onto its timestamp;
 * with it, a covered message is a bubble of a steady width and not a sliver. A bubble with words is
 * never floored - its own text decides, which is what makes an own row no wider than its text.
 */
private const val COVER_OF_CEILING = 0.6f

/** A `Transferable`'s own progress scale, as the 0..1 fraction the Compose bar takes. */
private const val PERCENT = 100f

/** The decimal magnitudes the attachment cell's size line switches on. */
private const val KILO = 1_000L
private const val MEGA = 1_000_000L
private const val GIGA = 1_000_000_000L

/** The bare bar's height, the tree's own 12 dp (`docs/MIGRATION.md` "Open and unverified"'s open tap-target question). */
private val EnglishBarHeight = 12.dp

/** How much of the bubble's own text colour a divider, a quote's bar and the meta line keep. */
private const val DIVIDER_OF_FOREGROUND = 0.24f
private const val QUOTE_OF_FOREGROUND = 0.5f
private const val META_OF_FOREGROUND = 0.72f
