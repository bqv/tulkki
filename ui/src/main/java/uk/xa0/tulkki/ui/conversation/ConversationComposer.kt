package uk.xa0.tulkki.ui.conversation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import uk.xa0.tulkki.translation.ConversationLanguage
import uk.xa0.tulkki.ui.LanguageChip
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.encryption.EncryptionSelector
import uk.xa0.tulkki.ui.projection.AttachmentKind
import uk.xa0.tulkki.ui.projection.UiBody
import uk.xa0.tulkki.ui.projection.UiDoubtHold
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiQuote
import uk.xa0.tulkki.ui.theme.LocalTulkkiColors
import uk.xa0.tulkki.ui.theme.TulkkiComposerDimens
import uk.xa0.tulkki.ui.theme.TulkkiElevation
import uk.xa0.tulkki.ui.theme.TulkkiShape
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import uk.xa0.tulkki.ui.utils.UIHelper

/**
 * The composer, `Design: the Compose UI` §3.6's `composer` member, drawn from [UiComposer] - and it
 * decides nothing: the language gate and the hold are the send path's, so this composable draws the
 * draft the gate will read, the reason the hold already produced, and the reply the owner opened.
 *
 * <p>**Item 16's switch is [UiComposer.doubtHold]'s, and it is a small chip in the composer's own
 * control row above the field.** `null` is the interpreter-off shape and draws nothing at all, exactly
 * as the chip's `null` does; on, the chip carries the value in force the host resolved. It is
 * [ConversationDoubtHold]'s one drawing: the standalone row the live fragment used to host is deleted,
 * so the switch is not drawn twice. The row that preceded it was a full-width label with a full-size
 * `Switch` above the field - the owner's "really in-your-face" - so the control became the composer's
 * own chip vocabulary, sized and shaped like the language pill it sits near. It shares that pill's row
 * ([ComposerControls]) rather than the input row: both are fixed children where the draft is the
 * weighted one, and at 360 dp the two of them beside four `IconButton`s left the draft 2 dp wide,
 * which is the owner's "mate. where do i type".
 *
 * <p>**The chip is the design's two-tier block, and every part of that is load-bearing.** `AGENTS.md`
 * fixes it: "**the conversation's language prominent** (larger, full opacity, bottom-left), **the app
 * language faded** (smaller, grey, top-right). The fade carries through size and alpha, not hue alone,
 * from theme attributes so light and dark both work. No detected language says 'not known yet' in the
 * conversation's own slot." So the two tiers are two `Text`s in one `Column`, the prominent one first
 * in the reading order and second on the screen, and the faded one is smaller *and* translucent. No
 * language at all is **no chip** - §2.12's interpreter-off shape - rather than a chip that says
 * nothing. **And the block is the way to the picker**: its tap is
 * [ConversationEvents.onLanguageChipTap], so the conversation's language - a decision the app makes
 * visible and overridable on purpose, and the send target - is reachable here and not only on a
 * `GONE` Java view.
 *
 * <p>**The block is one size in every state, and the unknown state is the marker.** The conversation's
 * slot draws `LanguageChip.tiers`' own second member - a code when the language is established, the
 * three-glyph `???` marker otherwise - inside a box whose minimum is the theme step that holds those
 * three glyphs, so the set, the unset and the one-language-missing states are the same small block.
 * The "not known yet" sentence is the chip's accessible name (the `tulkki_language_chip_content_*`
 * family already says it), never a second visible line the block would grow to hold: rendering that
 * sentence in the prominent slot is exactly the wide, lopsided block the owner reported.
 *
 * <p>**The reply preview conceals, and it is the quote's own decision.** §"Originals are hidden in the
 * UI" names the composer's always-outgoing preview as one of the surfaces where the original must
 * never appear: a concealed quote is drawn as a strip, never as its text, and the type makes that
 * structural - `UiQuote.bottom` is never read here at all.
 *
 * <p>**The suggestion is not drawn**, and the reason is the setting: the gate's app-language rendering
 * has a reveal order the owner chooses (`tulkki_reveal_first`), so drawing it here would pick one of
 * the two orders and make the setting do nothing. It arrives with the surface that reads it.
 *
 * <p>**The attachments are the composer's half of the Java surface, and only that half.** The Java
 * `mediaPreviewAdapter` strip and its attach menu are what this carries: the staged list is
 * [UiComposer.attachments], drawn as [PendingAttachments], and the attach affordance emits
 * [ConversationEvents.onAttach], whose menu is the host's (it reads encryption and permissions). What
 * is *not* here is the transfer cell: a staged file and a transferred one are different shapes, and
 * the row's own cell (`AttachmentCell`) draws the second. The affordance is drawn unconditionally,
 * unlike the chip and the switch: whether a conversation can stage a file is the host's question and
 * it has no state here to read, so the honest default is the verb itself, whose default body is
 * silent until a host answers it.
 *
 * <p>**The encryption selector is the composer's own leading control.** The Java drew it as the
 * toolbar's `action_security` submenu, configured by `configureEncryptionMenu`; the redesign puts the
 * choice where the message it governs is written, so the same `EncryptionSelector` the screen already
 * had is drawn here from [UiComposer.encryption] and its two picks are
 * [ConversationEvents.onEncryptionSelect]/[ConversationEvents.onEncryptionBlocked]. A `null` state,
 * or one whose options are empty, draws nothing - the Java's `setVisible(false)`.
 *
 * <p>**The emoji affordance is the picker's only way in.** The Java row drew two buttons -
 * `emojiButton` and `keyboardButton` - and both sat in the row the Compose composer hides, so the panel
 * `EmojiPanelHost` installs (`ui/emoji/EmojiPanel.kt`) had no reachable trigger at all. One affordance
 * is drawn here instead, and it emits [ConversationEvents.onEmojiTap]; the toggle itself is the host's,
 * because open or closed is the panel controller's state and not this composable's. It is drawn
 * unconditionally, for the attach affordance's reason: whether a conversation can stage a file is the
 * host's question, and this composable has no state here to read.
 *
 * <p>**The two controls that do read the host are [UiComposer.canWrite]'s and [UiComposer.thread]'s.**
 * A conversation that refuses a message draws the request-to-speak affordance in the send icon's
 * place - exactly when `canWrite` is false, and never otherwise - and a host that resolved a thread
 * draws the marker beside the attach affordance, while a `null` thread draws none. Both are the
 * host's readings of facts this composable cannot see, so neither is decided here.
 */
@Composable
fun ConversationComposer(composer: UiComposer, events: ConversationEvents, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        // §1.6: `toolbar_elevation` "is the one real shadow, kept for the app bar and the composer when
        // it floats" - and this composer floats over the list.
        tonalElevation = TulkkiElevation.toolbar,
    ) {
        Column(modifier = Modifier.padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xs)) {
            // The recording bar is the Java `recordingVoiceActivity` block, drawn where the owner can
            // see it: one row above the input row, exactly as the Java bar sat above it. An inactive
            // state draws nothing, so the row costs a plain composer nothing.
            VoiceRecordingBar(
                state = composer.recording,
                onCancel = { events.onRecordingCancel() },
                onShare = { events.onRecordingShare() },
                onTogglePause = { events.onRecordingTogglePause() },
            )
            composer.held?.let { hold ->
                Text(
                    // The hold's own sentence, and the two kinds read from two places: an ordinary
                    // hold is `TranslationText.held`'s table (a bar that drew `failureReason` would
                    // say "no DeepSeek key is set" where the owner is owed "Not sent: ... Add one in
                    // Tulkki's settings."), and item 16's doubt is the kind's own sentence - the
                    // contract, never a re-derivation.
                    text = heldSentence(hold),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = TulkkiSpacing.xs),
                )
            }
            composer.reply?.let { quote -> ReplyPreview(quote, onCancel = { events.onReplyCancel() }) }
            if (composer.attachments.isNotEmpty()) {
                PendingAttachments(composer.attachments, events)
            }
            // Item 16's switch and the language pair draw in the composer's own control row, above the
            // field and not in it; [ComposerControls] carries the reason, which is the owner's "mate.
            // where do i type". The field's row follows it.
            ComposerControls(composer, events)
            ComposerInputRow(composer, events)
            if (composer.formatting) {
                TextFormatBar(
                    draft = composer.draft,
                    onDraftChanged = events::onDraftChanged,
                    onCloseFormatting = { events.onFormattingClose() },
                    modifier = Modifier.padding(top = TulkkiSpacing.xs),
                )
            }
        }
    }
}

/**
 * The bar's sentence for a held send, from the shape the send path handed over.
 *
 * <p>The doubt branch is deliberately a one-line read of the kind's own sentence and nothing else:
 * `:translation` measured that contract, and a copy of the words here - or a second judgement of the
 * answer - is exactly the re-derivation it forbids.
 */
@Composable
private fun heldSentence(hold: UiHold): String =
    when (hold) {
        is UiHold.Reason -> stringResource(TranslationText.held(hold.reason))
        is UiHold.Doubt -> hold.kind.because
    }

/**
 * The composer's own control row: the two-tier language block and item 16's hold switch, above the
 * field and not in it. It is content-sized and draws nothing at all when the host handed over neither,
 * which is §2.12's interpreter-off shape.
 *
 * <p>**Why they are not in the input row: a `Row` sizes its fixed children first and gives the
 * weighted field what is left.** These two are the widest fixed children the bar has - the block's own
 * theme floor plus its `sm` gutter, and `ConversationDoubtHold`'s measured 110 dp chip - and beside
 * the input row's four 48 dp `IconButton`s (attach, emoji, send, and the encryption selector when the
 * host offers one) they left the draft **2 dp at 360 dp**: the field collapsed to a sliver and its
 * hint wrapped down the middle of the row. That is the owner's report, and the arithmetic is a
 * function of the window, not of the words: at 420 dp the same row left the field 110 dp without the
 * lock - the `chat-composer` reference, where the draft already wrapped - and 62 dp with it. In a row
 * of their own they cost the draft nothing: at 360 dp the two rows of padding leave 344 dp, the four
 * 48 dp `IconButton`s 192 dp of it, and the field the remaining **152 dp** with the lock drawn (200 dp
 * without it, 122 dp even with the thread marker's own 30 dp).
 *
 * <p>**Nothing about either control is weakened by the move.** The block is still
 * [LanguagePairBlock]'s one size in every state - the conversation's language prominent at the block's
 * bottom left, the app language faded at its top right, `???` for an unset language, the block's tap
 * the way to the picker - and the switch is still [ConversationDoubtHold]'s one drawing, reading and
 * writing the value in force.
 */
@Composable
private fun ComposerControls(composer: UiComposer, events: ConversationEvents) {
    val pair = composer.language
    val doubtHold = composer.doubtHold
    if (pair == null && doubtHold == null) {
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = TulkkiSpacing.xs),
    ) {
        if (pair != null) {
            LanguagePairBlock(pair, events)
            if (doubtHold != null) {
                Spacer(modifier = Modifier.width(TulkkiSpacing.sm))
            }
        }
        doubtHold?.let { hold -> ConversationDoubtHold(hold, events::onDoubtHoldChanged) }
    }
}

/**
 * The input row: the draft field and the controls that belong beside it - the thread marker, the
 * encryption selector, the attach affordance, the emoji affordance and the send (or, in a
 * conversation that refuses a message, the request to speak in its place).
 *
 * <p>Centred, not bottom-aligned: the row's height is an `IconButton`'s 48 dp, the field's own
 * one-line height is smaller than that, so a bottom alignment parks the words on the row's bottom edge
 * and leaves them visibly low against the icons beside them. Centring is the row's answer at one line
 * and still the icons' when the draft grows to several.
 *
 * <p>**What is deliberately not here is the language pair and item 16's switch**; [ComposerControls]
 * draws them one row up. What is also not here is a spacer between the field and the emoji
 * `IconButton`: the button's own 48 dp box already centres a 24 dp glyph in a 12 dp margin, so the
 * spacer only took 4 dp off the draft - and 4 dp is the margin this row is short of at 360 dp, not a
 * matter of taste.
 */
@Composable
private fun ComposerInputRow(composer: UiComposer, events: ConversationEvents) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        composer.thread?.let { thread -> ThreadMarker(thread, events) }
        composer.encryption?.let { selector ->
            EncryptionSelector(
                state = selector,
                onSelect = { events.onEncryptionSelect(it) },
                onBlocked = { choice, block -> events.onEncryptionBlocked(choice, block) },
            )
        }
        IconButton(onClick = { events.onAttach() }) {
            Icon(
                painter = painterResource(R.drawable.ic_attach_file_24dp),
                contentDescription = stringResource(R.string.attach),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DraftField(
            draft = composer.draft,
            hint = composer.hint,
            events = events,
            focusRequest = composer.focusRequest,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { events.onEmojiTap() }) {
            Icon(
                painter = painterResource(R.drawable.outline_emoji_emotions_24),
                contentDescription = stringResource(R.string.choose_emoji),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (composer.canWrite) {
            IconButton(onClick = { if (composer.canSend) events.onSend() }, enabled = composer.canSend) {
                Icon(
                    painter = painterResource(R.drawable.ic_send_24dp),
                    contentDescription = stringResource(R.string.send),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            // The one control a conversation that refuses a message has: the request that is the only
            // way out of it, in place of the send affordance the Java row hid.
            RequestVoice(events)
        }
    }
}

/**
 * Item 16's per-conversation switch: whether a doubtful translation is held for the owner's tap.
 *
 * <p>It is drawn only when the host has one to draw - `UiComposer.doubtHold` is `null` with the
 * interpreter off, and [uk.xa0.tulkki.ui.conversation.ConversationHost.doubtHold] answers the same
 * `null` for the live conversation - and it shows the value **in force**, so a room the owner never
 * touched reads as the shipped default, which is on. `null` draws nothing at all, which is §2.12's
 * interpreter-off shape rather than a switch that would do nothing.
 *
 * <p>**The drawing is a chip, not the labelled switch row it replaced.** The owner's report was that
 * the full-width label beside a full-size `Switch` above the field was "really in-your-face"; the
 * control is now a [FilterChip] in the composer's own control row above the field - the composer's own
 * shape, a check when the hold is in force - so it reads as one more small control in the bar rather
 * than a second bar. It moved out of the input row because as a fixed child there it was the single
 * widest thing in the row and left the weighted draft 2 dp at 360 dp; the arithmetic is
 * [ComposerControls]'. The short chip label is the visible word and the switch's own full sentence is
 * the chip's accessible name, so the setting is still named where it matters and is not spelled out
 * twice. A chip and not an icon: the composer already spends icons on encryption and the language
 * pair, and neither of those means "hold a doubtful translation".
 *
 * <p>It is public for the reason [ConversationNotices] is: the live conversation is still
 * `ConversationFragment`, and the composable the live host draws has to be reachable from Java. What
 * changed is where it is drawn - the switch had its own `tulkki_doubt_hold` `ComposeView` while the
 * composer was not live, and the fragment now hands [UiComposer.doubtHold] into
 * [ConversationComposer] through `ConversationHost.composer` instead, so there is one drawing for the
 * live host and `ConversationScreen` alike rather than two that could drift.
 */
@Composable
fun ConversationDoubtHold(doubtHold: UiDoubtHold?, onChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val hold = doubtHold ?: return
    val words = stringResource(R.string.tulkki_doubt_hold)
    FilterChip(
        selected = hold.inForce,
        // The chip is the toggle itself: the tap writes the value the switch would have written, and
        // it asks for the change of the state in force rather than a checkbox's own local opinion.
        onClick = { onChanged(!hold.inForce) },
        label = { Text(stringResource(R.string.tulkki_doubt_hold_chip)) },
        leadingIcon =
            if (hold.inForce) {
                { Icon(painter = painterResource(R.drawable.ic_check_24dp), contentDescription = null) }
            } else {
                null
            },
        modifier = modifier.semantics { contentDescription = words },
    )
}

/**
 * The two-tier language chip, and the one affordance that makes the conversation's language
 * reachable: tap it for the picker.
 *
 * <p>The conversation's own language is the prominent tier and the app's the faded one, both written
 * by `LanguageChip.tiers` - the rule, not a pair of locals a rewrite could swap without a failing
 * cell. The spoken form is the pair by *name*, through the three sentences the deleted Java chip used
 * for its own content description: the visible tiers are codes, so the screen reader is the one place
 * a language is spelled out, and both ends are named there rather than the conversation's alone.
 *
 * <p>**The block is small because the tiers are codes, and that is the fix the owner measured.** The
 * shape it replaces put a provenance sentence - `%1$s (detected)` / `%1$s (set by you)` - in the
 * conversation's slot, which took roughly a third of a 411 dp row and left the draft's hint wrapping
 * onto a second line. Provenance belongs to the spoken sentence, not to a chip this size; a chip of two
 * codes is a chip the bar can afford.
 *
 * <p>**It draws in [ComposerControls]' row above the field, not in the input row**, for that row's
 * reason: however small the block is, it is a fixed child where the draft is weighted, and the draft
 * is the one thing in the bar that must not lose. Being one row up changes neither tier, neither
 * corner, nor the tap.
 *
 * <p>**And it is one size in every state.** The conversation's slot draws [tiers]'s second member
 * directly - a code when the language is established, [LanguageChip.UNKNOWN]'s three glyphs otherwise -
 * inside a box whose minimum is the theme step that holds those three glyphs, so the set, unset and
 * one-language-missing states are the same compact two-tier block at the same corner positions. The
 * "not known yet" wording is not lost: it is the chip's spoken name below
 * (`tulkki_language_chip_content_unknown`), and drawing it again as a visible line is what made the
 * unset block wide and lopsided - the owner's second report. The rule that the conversation's own slot
 * never borrows the app language still holds: the slot is the marker.
 *
 * <p>The tap is the gesture the Java chip carried and is the whole of the fix: the Java chip is
 * `GONE` with the row it sat in, so leaving the picker on it made the conversation's language - the
 * send target, and a decision this app makes visible and overridable on purpose - unreachable in the
 * UI. The long press is not carried: see [ConversationEvents.onLanguageChipTap].
 */
@Composable
private fun LanguagePairBlock(pair: UiLanguagePair, events: ConversationEvents) {
    val known = pair.conversationLanguage != null
    val tiers = LanguageChip.tiers(pair.appLanguage, pair.conversationLanguage, known)
    // The two tiers are the two codes - or the conversation's three-glyph "???" marker - and the
    // block is *small* because they are: the deleted shape put `%1$s (detected)` / `%1$s (set by you)`
    // in the conversation's slot, which measured roughly a third of a 411 dp row on the handset and
    // wrapped the field's own hint. The provenance is not lost: it is [spoken] below, where the screen
    // reader spells both ends out, and `LanguageChipTest` pins the codes themselves.
    //
    // A conversation with no language yet draws the marker in its own slot rather than borrowing the
    // app language (AGENTS.md, "One app language"), and "not known yet" is [spoken] - the sentence for
    // a state, never a second visible line that would size the block by its wording rather than by a
    // token.
    val spoken =
        if (known) {
            stringResource(
                if (pair.overridden) R.string.tulkki_language_chip_content_set
                else R.string.tulkki_language_chip_content_detected,
                ConversationLanguage.languageName(pair.appLanguage),
                ConversationLanguage.languageName(pair.conversationLanguage),
            )
        } else {
            stringResource(
                R.string.tulkki_language_chip_content_unknown,
                ConversationLanguage.languageName(pair.appLanguage),
            )
        }
    Column(
        horizontalAlignment = Alignment.Start,
        // The minimum is the theme's own step that holds the three-glyph marker, so the block cannot
        // take the row's room back when the conversation's slot is the marker instead of a code: the
        // two states occupy the same small box, at the same two corners.
        modifier =
            Modifier.widthIn(min = TulkkiSpacing.xl)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .clickable { events.onLanguageChipTap() },
    ) {
        Text(
            text = tiers.app,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FADED_OF_OPACITY),
            maxLines = 1,
            modifier = Modifier.align(Alignment.End),
        )
        Text(
            text = tiers.conversation,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The request-to-speak affordance: the one control a conversation that refuses a message has, drawn
 * in place of the send icon exactly when [UiComposer.canWrite] says the request is possible.
 *
 * <p>It is the whole of the Java `requestVoice` button's tap - ask the room's moderators for a voice
 * and say so - moved onto a surface that is drawn: the button sat in the `GONE` Java row with the
 * send path's field, so a room the owner could not write in had no route to the request. The
 * sentence is the platform's own `request_to_speak`, not a second wording, and the ask itself is the
 * host's [ConversationEvents.onRequestVoice].
 */
@Composable
private fun RequestVoice(events: ConversationEvents) {
    TextButton(onClick = { events.onRequestVoice() }) {
        Text(text = stringResource(R.string.request_to_speak))
    }
}

/**
 * The thread marker: a disc in the thread's own colour with the lock badge, tapped to switch thread
 * and long-pressed to clear it.
 *
 * <p>**The behaviour is the Java `threadIdenticonLayout`'s, on a surface the owner can see.** That
 * marker sat in the `GONE` Java row, so thread selection - `setThread`, `newThread`,
 * `updateThreadFromLastMessage` - was drawn and tappable nowhere. The generated identicon graphic is
 * the one part not carried across: a Compose field has no `GithubIdenticonView`, and the redesign
 * licence lets the marker be a disc in the colour the deleted identicon was given. `null`
 * [UiThread.threadId] is a conversation with no thread yet, so the disc falls back to the theme's
 * surface rather than inventing a colour; the lock badge is the same `ic_lock_24dp` the Java view
 * drew. The tap target is the spacing scale's `xl`, not the `26dp` box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadMarker(thread: UiThread, events: ConversationEvents) {
    val words = stringResource(R.string.pref_show_thread_feature)
    val plate =
        thread.threadId?.let { Color(UIHelper.getColorForName(it)) }
            ?: MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier =
            Modifier.padding(start = TulkkiSpacing.xs, end = TulkkiSpacing.xxs)
                .size(TulkkiSpacing.xl)
                .clip(CircleShape)
                .background(plate)
                .combinedClickable(
                    onClick = { events.onThreadTap() },
                    onLongClick = { events.onThreadLongPress() },
                )
                .semantics { contentDescription = words },
        contentAlignment = Alignment.Center,
    ) {
        if (thread.locked) {
            Icon(
                painter = painterResource(R.drawable.ic_lock_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(TulkkiSpacing.md),
            )
        }
    }
}

/**
 * The field itself: one `BasicTextField`, drawn as the field the owner types in.
 *
 * <p>[focusRequest] is the host's "put the caret here" - the deleted `binding.textinput.requestFocus()`,
 * which `ConversationFragment.onResume` ran so that opening a conversation offered the keyboard. It is
 * a counter and not a flag: a host asks by moving it, and `0` is "no request", so a screen that never
 * asks (a cell, a preview) never steals focus. A request that lands before the field is attached is
 * replayed by [LaunchedEffect] on the next composition with the same value, so the first frame of a
 * freshly shown conversation is not a dropped request.
 */
@Composable
private fun DraftField(
    draft: TextFieldValue,
    hint: String?,
    events: ConversationEvents,
    focusRequest: Int,
    modifier: Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            focusRequester.requestFocus()
        }
    }
    BasicTextField(
        value = draft,
        onValueChange = events::onDraftChanged,
        modifier = modifier.focusRequester(focusRequester),
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = COMPOSER_MAX_LINES,
        // The IME's own send key is the deleted Java `EditText`'s editor action, moved onto the field
        // that is drawn: the listener on the hidden mirror could never run because the field it
        // belonged to receives no input. It emits the same verb the send affordance does and decides
        // nothing - the send path's own `carriage` answers an empty field, exactly as the Java
        // listener's `sendMessage()` did.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { events.onSend() }),
        decorationBox = { field ->
            Box {
                if (draft.text.isEmpty()) {
                    Text(
                        // The conversation's own hint where the host has one - "You are muted", a
                        // private message's addressee, a correction - and the shipped placeholder
                        // otherwise. It is the Java `EditText`'s `setHint`, and it is drawn here now
                        // because the field the owner types in is this one.
                        text = hint ?: stringResource(R.string.tulkki_chat_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // One line, always: a hint is a label and never a paragraph. The owner's
                        // report was a hint that wrapped down the middle of the row while the field
                        // held 2 dp, and a hint that is allowed to wrap is a hint that can size the
                        // bar by its wording - the encrypted conversation's own hint
                        // (`send_encrypted_message`) is longer than the room the field has at 360 dp,
                        // and the phone that reported this runs the Finnish locale. The bound is the
                        // field's, not the host's string's, so no caller can take the row back.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            }
        },
    )
}

/**
 * The staged attachments, above the draft: the Java `media_preview` strip, one square per file, in
 * the order the send will travel. It is drawn only when the host has staged something - an empty list
 * is no strip, the same honest empty the chip and the reply follow.
 *
 * <p>Scrolls horizontally rather than wrapping, exactly as the Java strip did
 * (`LinearLayoutManager(HORIZONTAL)` over a `RecyclerView`): a long list of files must not push the
 * draft field off the bar. The scroll position is the one piece of transient state here, so it is a
 * `remember` and never the host's.
 */
@Composable
private fun PendingAttachments(attachments: List<UiPendingAttachment>, events: ConversationEvents) {
    val scroll = rememberScrollState()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(bottom = TulkkiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            PendingAttachment(attachment, events)
            Spacer(modifier = Modifier.width(TulkkiSpacing.xs))
        }
    }
}

/**
 * One staged attachment: its pixels where the host built them, a kind's plate otherwise, a remove
 * affordance over the corner, and a tap that opens or edits it.
 *
 * <p>Nothing here is words about the file. The strip draws no filename because a staged attachment
 * has none to draw - [UiPendingAttachment] carries the id, the kind and at most the pixels - so the
 * screen reader is given the kind's own phrase and the remove button its own verb, and the raw-text
 * rule has no surface to leak through.
 */
@Composable
private fun PendingAttachment(attachment: UiPendingAttachment, events: ConversationEvents) {
    val words = stringResource(R.string.tulkki_composer_attachment)
    Box(
        modifier =
            Modifier.size(TulkkiComposerDimens.attachmentPreview)
                .clip(TulkkiShape.image)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { events.onAttachmentTap(attachment.id) }
                .semantics { contentDescription = words }
    ) {
        val thumbnail = attachment.thumbnail
        if (thumbnail != null) {
            // The pixels decide, whichever kind they are: the Java strip drew a thumbnail for an
            // image *and* for a file whose mime renders one (video, PDF), so gating on the kind
            // here would drop the file thumbnails the host did build. The plate is the fallback,
            // not the other branch of the decision.
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(attachment.kind.plate()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(TulkkiSpacing.xl),
            )
        }
        IconButton(
            onClick = { events.onAttachmentRemoved(attachment.id) },
            modifier = Modifier.align(Alignment.TopEnd).size(TulkkiSpacing.xl),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cancel_24dp),
                contentDescription = stringResource(R.string.tulkki_composer_remove_attachment),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The plate a staged attachment with no pixels draws: the projection's own two kinds, so the strip
 * and the row's cell cannot disagree about what an image is. It is the same "the pixels decide"
 * decision `AttachmentCell`'s ready state makes, with the name left out because a staged file has
 * none.
 */
@DrawableRes
private fun AttachmentKind.plate(): Int =
    when (this) {
        AttachmentKind.IMAGE -> R.drawable.ic_image_24dp
        AttachmentKind.FILE -> R.drawable.ic_attach_file_24dp
    }

/**
 * The row being replied to, above the field: the quote's bar and its app-language half, with a
 * concealed half drawn as the strip it is. The second half is not read at all, which is the point.
 *
 * <p>[onCancel] is the dismiss the Java preview carried as its own cancel button, moved onto the
 * preview the owner actually sees: without it a reply opened by mistake could only be abandoned by
 * sending it or closing the conversation.
 */
@Composable
private fun ReplyPreview(quote: UiQuote, onCancel: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val words = stringResource(R.string.tulkki_chat_replying)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = TulkkiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.width(TulkkiSpacing.xxs)
                    .fillMaxHeight()
                    .clip(TulkkiShape.bubble)
                    .background(accent)
        )
        Spacer(modifier = Modifier.width(TulkkiSpacing.xs))
        Column(modifier = Modifier.weight(1f).semantics { contentDescription = words }) {
            Text(text = words, style = MaterialTheme.typography.labelSmall, color = accent)
            when (val top = quote.top) {
                is UiBody.Visible ->
                    Text(
                        text = top.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = PREVIEW_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                is UiBody.Concealed ->
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(ReplyStripHeight)
                                .clip(TulkkiShape.bubble)
                                .background(LocalTulkkiColors.current.coverStrip)
                    )
                UiBody.Absent -> Unit
            }
        }
        IconButton(onClick = onCancel) {
            Icon(
                painter = painterResource(R.drawable.ic_cancel_24dp),
                contentDescription = stringResource(R.string.tulkki_composer_cancel_reply),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How many lines a draft may grow to before the field scrolls. */
private const val COMPOSER_MAX_LINES = 5

/** How much of the reply preview is shown before it is ellipsised. */
private const val PREVIEW_MAX_LINES = 2

/**
 * The app language's tier: smaller and translucent, so the fade is not hue alone.
 *
 * <p>0.75 rather than the 0.6 it was: the Java chip this replaced raised its own faded tier to 0.75
 * for measured contrast - 3.85:1 dark and 3.14:1 light at 0.6, below what small text needs - and the
 * figure came over with the design. The base colour here is the theme's rather than that chip's own
 * dedicated palette, so the Java measurement does not transfer value for value; a device read is what
 * would pin this tier's ratio.
 */
private const val FADED_OF_OPACITY = 0.75f

/**
 * A concealed preview's strip: one line of body text, so the preview is the height it would be with
 * the words. It is the spacing scale's own 16 dp rather than a number invented here.
 */
private val ReplyStripHeight = TulkkiSpacing.lg
