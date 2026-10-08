package uk.xa0.tulkki.ui.conversation

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.translation.GlossContent
import uk.xa0.tulkki.translation.GlossText
import uk.xa0.tulkki.ui.PopupPlacement
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.projection.UiGlossWord
import uk.xa0.tulkki.ui.theme.TulkkiGlossDimens
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import kotlin.math.roundToInt

/**
 * The reading aid's card, rebuilt for the message path from the deleted `GlossPopup.java`.
 *
 * <p>What the Java did is what this does: a tap on a word opens a card anchored to that word, the card
 * holds one [GlossContent], `GlossLookup.request` is asked with the word **and the sentence it sits
 * in** (`GlossText.sentence` over the drawn text), and the answer repaints the card. The request is
 * the owner's deliberate tap and nothing else - there is no prefetch, no "on open", no second entry
 * point - and re-reading a message is free because `GlossLookup`'s cache is keyed by
 * `GlossKey.of(word, studyLanguage, sentence)`, so meeting the word in another sentence is a new
 * purchase by construction.
 *
 * <p>**An in-tree overlay and not a `Popup`.** `docs/MIGRATION.md` "Design: the Compose UI" is
 * explicit that the gloss and the review "become in-tree overlay layers with their own hit-testing"
 * and that a Compose `Popup` is forbidden; the card is therefore a `Box` drawn over the list and
 * positioned with the same pure [PopupPlacement] rule the `PopupWindow` used. The card carries no
 * pointer input at all, which is the Java's own `setTouchable(false)` fix: a mostly read-only card
 * that swallows a touch is a tap that goes nowhere, and here it means a word under the card is still
 * tappable and a tap anywhere else still reaches the bubble.
 *
 * <p>**What dismisses it.** The back button through a callback that exists only while the card does
 * (registered with the activity, skipped where there is none, as in a preview); a scroll of the list
 * (`MessageList` watches its own `LazyListState`); and any tap on the bubble that is not a word -
 * `MessageBubble` clears the card before it emits its own verb. A tap on another word does not
 * dismiss: it supersedes, which is what the deleted lookup already did.
 *
 * <p>The card's own contents are the deleted `gloss_popup.xml`/`gloss_row.xml` in Compose: the word as
 * a title, a spinner while the request is out, one caption for the two states that have one (the
 * failure's own cover words, or "nothing to look up"), and one labelled line per field.
 */
internal class GlossOpen(
    /** The surface form, which is the lookup's question and the card's title. */
    val word: String,
    /** The sentence the word was tapped in, which the request carries. */
    val sentence: String,
    /** The word's rectangle in window coordinates, which the card is placed against. */
    val anchor: Rect,
) {

    /** What the card is showing now; the lookup's callback moves it as the answer lands. */
    var content: GlossContent by mutableStateOf(GlossContent.lookingUp(word))
}

/**
 * The overlay: the live [GlossOpen]'s card, placed beside its anchored word by [PopupPlacement].
 *
 * <p>It measures itself rather than guessing: the field is the whole message area (so the placement
 * clamps against the conversation and not the display) and the card reports its own size, so a
 * longer answer re-places itself inside the screen exactly as the Java `replace()` did after a show.
 */
@Composable
internal fun GlossOverlay(open: GlossOpen, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    var field by remember { mutableStateOf(Rect.Zero) }
    var card by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { PopupPlacement.GAP_DP.dp.roundToPx() }
    val placed =
        remember(open.anchor, field, card, density) {
            if (field == Rect.Zero || card == IntSize.Zero) {
                IntOffset.Zero
            } else {
                val local = open.anchor.translate(-field.left, -field.top)
                val xy =
                    PopupPlacement.place(
                        intArrayOf(
                            local.left.roundToInt(),
                            local.top.roundToInt(),
                            local.right.roundToInt(),
                            local.bottom.roundToInt(),
                        ),
                        card.width,
                        card.height,
                        field.width.roundToInt(),
                        field.height.roundToInt(),
                        density.density,
                        gap,
                    )
                IntOffset(xy[0], xy[1])
            }
        }
    // Back closes the card, and only while one is open: the callback is registered and removed with
    // the overlay, and a host with no dispatcher (a preview, a JVM render) simply has no back button.
    val owner = LocalOnBackPressedDispatcherOwner.current
    if (owner != null) {
        DisposableEffect(owner, open) {
            val callback =
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        onDismiss()
                    }
                }
            owner.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }
    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { field = it.boundsInWindow() }) {
        GlossCardContent(
            content = open.content,
            modifier = Modifier.offset { placed }.onSizeChanged { card = it },
        )
    }
}

/**
 * The card itself: one [GlossContent] drawn, with no knowledge of where it came from or what opens it.
 *
 * <p>It is public so the screenshot suite can render a state the live screen only reaches after a tap,
 * which is the only way a *drawn* card gets coverage.
 */
@Composable
fun GlossCardContent(content: GlossContent, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.widthIn(max = TulkkiGlossDimens.cardMaxWidth),
        shape = RoundedCornerShape(TulkkiGlossDimens.cardCorner),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = TulkkiGlossDimens.cardElevation),
    ) {
        Column(modifier = Modifier.padding(TulkkiSpacing.lg)) {
            Text(
                text = content.word(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            when (content.kind()) {
                GlossContent.Kind.LOOKING_UP ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier.padding(top = TulkkiSpacing.sm)
                                .size(TulkkiGlossDimens.progress),
                    )
                // The failure speaks the covered bubble's own vocabulary, so the aid and the bubble
                // cannot say "no key" two different ways; "nothing" is the aid's own sentence.
                GlossContent.Kind.FAILED ->
                    GlossCaption(stringResource(TranslationText.coverCaption(content.failure())))
                GlossContent.Kind.NOTHING ->
                    GlossCaption(stringResource(R.string.tulkki_gloss_nothing))
                GlossContent.Kind.GLOSS -> Unit
            }
            if (content.rows().isNotEmpty()) {
                Spacer(modifier = Modifier.height(TulkkiSpacing.sm))
                for (row in content.rows()) {
                    GlossRow(row)
                }
            }
        }
    }
}

@Composable
private fun GlossCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = TulkkiSpacing.xs),
    )
}

/** One line: the field's label, and the value when the answer named one. */
@Composable
private fun GlossRow(row: GlossContent.Row) {
    Row(modifier = Modifier.padding(vertical = TulkkiSpacing.xs)) {
        Text(
            text = stringResource(GlossFields.label(row.field)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = TulkkiGlossDimens.labelWidth),
        )
        // The aid's basic-form line is a sentence with no value behind it; an empty value view would
        // only leave the gap where a value belongs (`GlossPopup.render`'s own rule).
        if (row.value.isNotEmpty()) {
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.padding(start = TulkkiGlossDimens.labelGap)
                        .widthIn(max = TulkkiGlossDimens.valueMaxWidth),
            )
        }
    }
}

/**
 * The one place a gloss field becomes a word, kept from the deleted `GlossPopup.labelFor` so the value
 * stays free of resources and the two do not drift.
 */
object GlossFields {

    /** The string a field's label is. */
    @JvmStatic
    @StringRes
    fun label(field: GlossContent.Field): Int =
        when (field) {
            GlossContent.Field.DICTIONARY -> R.string.tulkki_gloss_dictionary
            GlossContent.Field.ENDING -> R.string.tulkki_gloss_ending
            GlossContent.Field.CASE -> R.string.tulkki_gloss_case
            // The whole line, not a label: the aid's own sentence about a word already in its
            // dictionary form, standing where the ending and case rows would have.
            GlossContent.Field.BASIC_FORM -> R.string.tulkki_gloss_basic_form
            GlossContent.Field.MEANING -> R.string.tulkki_gloss_meaning
        }
}

/**
 * The drawn app-language text with its tappable words, which is the reading aid's only entry point.
 *
 * <p>It replaces the Java `GlossSpan`s: the words and their offsets come from the projection
 * ([UiGlossWord], already `GlossText.words`' answer), the tap is read against the live layout
 * (`TextLayoutResult.getOffsetForPosition`), and a tap that lands on a word is **consumed** so the
 * bubble's own "translate this one, now" does not also fire - the shadowing the tree recorded
 * (`ConversationFragment`: a gloss span shadows the body's listener) is kept, deliberately, because a
 * word tap and a bubble tap are different verbs. A tap that lands between words is not consumed and
 * falls through to the bubble.
 *
 * <p>The callback receives the word, the sentence `GlossText.sentence` cuts from **this** text (never a
 * guess), and the word's rectangle in window coordinates for the card to point at.
 */
@Composable
internal fun GlossTextBody(
    text: String,
    words: List<UiGlossWord>,
    style: TextStyle,
    color: Color,
    onGloss: (UiGlossWord, String, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val tap by rememberUpdatedState(onGloss)
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier =
            modifier
                .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
                .pointerInput(text, words) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation() ?: continue
                            val result = layout ?: continue
                            val at = result.getOffsetForPosition(up.position)
                            val word =
                                words.firstOrNull { at >= it.start && at < it.end } ?: continue
                            // The word's tap is the reading aid's; consume it so the bubble's own
                            // listener does not translate the message as well.
                            up.consume()
                            val box = result.getBoundingBox(word.start)
                            tap(
                                word,
                                GlossText.sentence(text, word.start, word.end),
                                Rect(
                                    origin.x + box.left,
                                    origin.y + box.top,
                                    origin.x + box.right,
                                    origin.y + box.bottom,
                                ),
                            )
                        }
                    }
                },
    )
}
