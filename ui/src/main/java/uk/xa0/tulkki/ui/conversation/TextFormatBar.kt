package uk.xa0.tulkki.ui.conversation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiElevation
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * Tulkki: the draft's text-formatting bar, in Compose - the Java `ConversationFragment`'s
 * `insertFormatting` and the row its four buttons are pressed from (`binding.textformat`,
 * `fragment_conversation.xml`), as a **stateless composable**.
 *
 * <p>**It writes markup characters into the draft; it does not apply spans.** This was the shape
 * question, and the Java settles it: `insertFormatting` neither builds a `Spannable` nor sets a span
 * flag - its `BOLD`, `ITALIC`, `MONOSPACE` and `STRIKETHROUGH` are four one-character `String`
 * constants (`*`, `_`, `` ` ``, `~`) that it splices into the `EditText`'s own text around the
 * selection. So the Compose surface is a row of buttons over a pure string edit: no
 * `AnnotatedString`, no styled range, and the selection it needs is a **text** selection, never a
 * span. Had the Java applied spans to an `Editable` this surface could not have been expressible
 * without a rich-text editor; it is a text edit, so it is ordinary arithmetic.
 *
 * <p>**The rule that the Java kept inside the view is here, as a JVM cell.** `insertFormatting` read
 * the `EditText`'s `getSelectionStart()`/`getSelectionEnd()`, took the selected `subSequence`, and
 * mutated the `Editable` in place with `replace(...)`/`insert(...)`. A Compose text field owns no
 * such mutation, so the arithmetic is extracted: [DraftMarkup.format] takes the draft's text and its
 * two selection offsets and answers the text it becomes and where the cursor lands, and
 * [DraftMarkup.marked] is the one-line adapter onto the field's [TextFieldValue]. Markup inserted
 * with nothing selected is the Java's own toggle - **one** marker at the cursor, so the owner taps
 * the button again to close the run - and that is preserved rather than "fixed".
 *
 * <p>**The exact signature, for the lane that will host every surface:**
 *
 * ```
 * @Composable
 * fun TextFormatBar(
 *     draft: TextFieldValue,
 *     onDraftChanged: (TextFieldValue) -> Unit,
 *     modifier: Modifier = Modifier,
 *     onCloseFormatting: (() -> Unit)? = null,
 * )
 * ```
 *
 * <p>**It needs the editable's `TextFieldValue`, not plain text, and that is why the lift came
 * first.** A marker's position is a selection, and a `String` has no cursor: while the composer's
 * [UiComposer.draft] was a `String` and `ConversationComposer`'s field a `BasicTextField(value:
 * String, ...)`, a bar drawn over that pair could only ever append. The lift has landed - the draft
 * is a [TextFieldValue] and the field is a `BasicTextField(value: TextFieldValue, ...)` - so the bar
 * can be wired to it: the field's owner hands the value in, the composable answers the new value
 * through [onDraftChanged] and writes nothing itself.
 *
 * <p>**Every durable fact is the caller's, and the bar decides nothing outside its own text edit.**
 * It does not read the `showtextformatting` preference and does not decide whether it is drawn: the
 * host draws it while the IME is up and `ServicePreferences.showTextFormatting()` is on - the Java's
 * own two-part condition, in `updateinputfield` - and the host owns that setting's write.
 * [onCloseFormatting] is the host's "turn the toolbar off", which `closeFormatting` did by storing
 * `showtextformatting = false` and hiding the row; this composable only confirms and then calls. The
 * single piece of state remembered here is whether that confirmation is open, which is exactly the
 * transient UI state the interface rule allows; a null [onCloseFormatting] draws no close control at
 * all, because a drawn button that performs nothing is worse than an absent one.
 *
 * <p>**The two further draft verbs are carried here too.** The Java row drew `/me ` at position 0
 * (`meCommand`, enabled only while the draft is empty) and `> ` at the cursor - or at the head of the
 * draft when there is a selection (`insertQuote`) - beside the four markers, wired in the same
 * `showTextFormat`. They are draft edits rather than formatting, but they live on the same row, are
 * enabled by the same condition and answer through the same `onDraftChanged`, so they are drawn here
 * rather than in a second bar that would have to be shown and hidden in step with this one. Their
 * arithmetic is [DraftMarkup.me] and [DraftMarkup.quoteLine], pinned by `DraftMarkupTest`.
 */
@Composable
fun TextFormatBar(
    draft: TextFieldValue,
    onDraftChanged: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onCloseFormatting: (() -> Unit)? = null,
) {
    var confirmingClose by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        // The Java row rode on `background_message_bubble` at alpha 0.88; the one real shadow in the
        // design (§1.6) is the theme's own toolbar elevation, so the row is a themed surface rather
        // than a second translucent bubble.
        tonalElevation = TulkkiElevation.toolbar,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DraftVerb(
                label = ME_LABEL,
                descriptionRes = R.string.me,
                enabled = draft.text.isEmpty(),
                onClick = { onDraftChanged(DraftMarkup.me(draft)) },
            )
            DraftVerb(
                label = QUOTE_LABEL,
                descriptionRes = R.string.quote,
                enabled = true,
                onClick = { onDraftChanged(DraftMarkup.quoteLine(draft)) },
            )
            MarkupButton(MarkupKind.BOLD, R.drawable.rounded_format_bold_24, R.string.bold, draft, onDraftChanged)
            MarkupButton(MarkupKind.ITALIC, R.drawable.rounded_format_italic_24, R.string.italic, draft, onDraftChanged)
            MarkupButton(MarkupKind.MONOSPACE, R.drawable.rounded_code_24, R.string.monospace, draft, onDraftChanged)
            MarkupButton(
                MarkupKind.STRIKETHROUGH,
                R.drawable.rounded_strikethrough_s_24,
                R.string.strikethrough,
                draft,
                onDraftChanged,
            )
            if (onCloseFormatting != null) {
                IconButton(onClick = { confirmingClose = true }) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_close_24),
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        }
    }

    val close = onCloseFormatting
    if (confirmingClose && close != null) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            title = { Text(stringResource(R.string.action_close)) },
            text = { Text(stringResource(R.string.close_format_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClose = false
                        close()
                    }
                ) {
                    Text(stringResource(R.string.action_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClose = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * One of the two draft verbs the Java row drew with a text label rather than an icon: `/me` and `>`.
 *
 * <p>They are draft edits like the markers, so they answer through the same `onDraftChanged`; the
 * only difference is that the Java row drew them as `TextView`s, and the label text (`/me`, `>`) is
 * what it drew. The content description carries the accessible name the Java `contentDescription`
 * did. `/me` is enabled only while the draft is empty, exactly as `showTextFormat(me)` gated it.
 */
@Composable
private fun DraftVerb(
    label: String,
    @StringRes descriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(descriptionRes)
    IconButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/**
 * One marker button: the icon the Java row drew, its accessibility name, and the edit it makes.
 *
 * <p>The button computes the next draft and hands it to [onDraftChanged] - it holds no state, and it
 * reads no selection of its own beyond the draft it was given, so the row can be drawn in a preview
 * or a JVM cell with a draft of any shape.
 */
@Composable
private fun MarkupButton(
    kind: MarkupKind,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    draft: TextFieldValue,
    onDraftChanged: (TextFieldValue) -> Unit,
) {
    IconButton(onClick = { onDraftChanged(DraftMarkup.marked(draft, kind)) }) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

/**
 * The four runs the bar writes, each with the character the Java wrapped a selection in.
 *
 * <p>They are `insertFormatting`'s own constants, unchanged: `*` bold, `_` italic, `` ` `` monospace,
 * `~` strikethrough. The character travels in the message body as the owner's own text - there is no
 * span to lose on the way out.
 */
enum class MarkupKind(val marker: Char) {
    BOLD('*'),
    ITALIC('_'),
    MONOSPACE('`'),
    STRIKETHROUGH('~'),
}

/** What the Java row's two `TextView`s said, character for character. */
private const val ME_LABEL = "/me"

private const val QUOTE_LABEL = ">"

/**
 * One markup edit, as the two facts a text field needs: the draft's new text, and where the caret
 * goes.
 *
 * <p>It is deliberately not a [TextFieldValue]: the rule is text and offsets, so it is a JVM cell
 * that a unit test can pin without a Compose runtime, and [DraftMarkup.marked] is the single place
 * the two types meet.
 */
data class MarkupEdit(val text: String, val cursor: Int)

/**
 * `insertFormatting`, as a pure function of the draft.
 *
 * <p>**Two cases, exactly the Java's.** With a selection, the selected text is replaced by
 * `marker + selected + marker`; with none, a **single** marker is inserted at the caret - the Java's
 * `insert(getSelectionStart(), BOLD)` - which is what makes a second tap close the run. In both cases
 * the caret lands **after** the inserted text: past the marker `Editable.insert` wrote in the empty
 * case, and at the end of the region the Java's `Editable.replace` had just rewritten in the other.
 *
 * <p>**Nullability from behaviour, not from a bang.** The selection arrives as two `Int`s and the
 * Java clamped them into `0..length`; here they are coerced into that range rather than trusted, so
 * a stale offset can never throw and a backwards selection (`start > end`) is the same edit as the
 * forwards one - the Java took `min`/`max` for the same reason.
 */
object DraftMarkup {

    /** The draft [kind] makes of `text` with its selection, and the caret it leaves behind. */
    @JvmStatic
    fun format(text: String, selectionStart: Int, selectionEnd: Int, kind: MarkupKind): MarkupEdit {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(0, text.length)
        val min = minOf(start, end)
        val max = maxOf(start, end)
        val marker = kind.marker.toString()
        if (min == max) {
            val inserted = text.substring(0, min) + marker + text.substring(min)
            return MarkupEdit(inserted, min + marker.length)
        }
        val wrapped = marker + text.substring(min, max) + marker
        val replaced = text.substring(0, min) + wrapped + text.substring(max)
        return MarkupEdit(replaced, min + wrapped.length)
    }

    /**
     * The field's own state after a markup edit: the new text, the caret collapsed where the
     * insertion ends, and **no composition**.
     *
     * <p>Dropping the composition is deliberate: a markup insert is not made of the characters an IME
     * was composing, so carrying one over would underline text it never composed. Nothing else of the
     * old value survives, which is the whole of the edit.
     */
    /**
     * The `> ` quote verb, as a pure function of the draft: `EditMessage.insertAsQuote`'s own
     * arithmetic, lifted off the Java `Editable` and onto the draft's home.
     *
     * <p>[text] is the passage being quoted - a shared passage, or the text a share arrived with - and
     * the rule is the Java's: the quoted block starts on its own line, the caret lands after the block,
     * and a following line is opened so the owner can answer under it. The caret is
     * [TextFieldValue.selection]'s end, which is what the Java's `getSelectionEnd()` named (and where
     * it read `-1`, the empty field, the caret is at the head and the same edit is made).
     */
    @JvmStatic
    fun quote(value: TextFieldValue, text: String): TextFieldValue {
        val quoted = QuoteHelper.quote(text)
        val builder = StringBuilder(value.text)
        var position = value.selection.end.coerceIn(0, value.text.length)
        if (position > 0 && builder[position - 1] != '\n') {
            builder.insert(position, "\n")
            position++
        }
        builder.insert(position, quoted)
        position += quoted.length
        builder.insert(position, "\n")
        position++
        if (position < builder.length && builder[position] != '\n') {
            builder.insert(position, "\n")
        }
        return TextFieldValue(text = builder.toString(), selection = TextRange(position))
    }

    @JvmStatic
    fun marked(value: TextFieldValue, kind: MarkupKind): TextFieldValue {
        val edit = format(value.text, value.selection.start, value.selection.end, kind)
        return TextFieldValue(text = edit.text, selection = TextRange(edit.cursor))
    }

    /**
     * `/me`, as the Java `meCommand` wrote it: the command at the head of the draft and the caret
     * after it. The Java's own `insert(0, Message.ME_COMMAND + " ")` is preserved byte for byte,
     * double space and all - it is a quirk of the row that predates this port, not a defect to fix
     * silently, and `Message.hasMeCommand()` trims before it reads so the extra space is inert.
     *
     * <p>The button is enabled only while the draft is empty (`canSendMeCommand`), so the caret the
     * Java field left after its own `insert` is not in question: there is nothing else in the field.
     */
    @JvmStatic
    fun me(value: TextFieldValue): TextFieldValue {
        val insert = Message.ME_COMMAND + " "
        return TextFieldValue(text = insert + value.text, selection = TextRange(insert.length))
    }

    /**
     * The `> ` quote verb, as the Java `insertQuote` wrote it: a quote marker at the head of the
     * draft when the caret is collapsed at position 0, and otherwise on a line of its own at the
     * caret. A selection that is not collapsed quotes at the head, which is the Java's own
     * `pos = 0` branch.
     *
     * <p>The two characters are [QuoteHelper.QUOTE_CHAR] and a space, which is exactly what the Java
     * spliced; the line break is `\n`, the only separator Android's `System.getProperty` ever
     * answers.
     */
    @JvmStatic
    fun quoteLine(value: TextFieldValue): TextFieldValue {
        val collapsed = value.selection.start == value.selection.end
        val position = if (collapsed) value.selection.start.coerceIn(0, value.text.length) else 0
        val insert =
            if (position == 0) {
                "${QuoteHelper.QUOTE_CHAR} "
            } else {
                "\n${QuoteHelper.QUOTE_CHAR} "
            }
        val text = value.text.substring(0, position) + insert + value.text.substring(position)
        return TextFieldValue(text = text, selection = TextRange(position + insert.length))
    }
}
