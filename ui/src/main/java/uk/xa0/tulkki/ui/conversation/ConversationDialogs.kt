package uk.xa0.tulkki.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.ui.R

/**
 * The conversation's two Compose dialogs: the clear-history prompt and the language picker.
 *
 * <p>Both were `dialog_*.xml` layouts inflated into a `MaterialAlertDialogBuilder`, and both are
 * launched from the fragment's own call sites through [uk.xa0.tulkki.ui.dialogs.showTulkkiDialog],
 * which is the door the rest of the tree's view-based screens use. The strings, the branches and the
 * side effects are the fragment's and are unchanged; only the drawing moved.
 */

/**
 * The clear-history prompt: the tree's warning paragraph, a "delete the chat afterwards" checkbox
 * and Confirm/Cancel.
 *
 * <p>The checkbox is unchecked at every opening, exactly as the deleted layout's
 * `android:checked="false"` was, so a cancelled-then-reopened dialog does not remember a choice
 * nobody made. The paragraph's one `<b>Warning:</b>` is carried as a bold run rather than lost with
 * the span aapt gave the old `TextView`.
 *
 * @param onDismiss the Cancel button and the outside tap, which do nothing else
 * @param onConfirm the Confirm button, handed whether the chat should also be archived
 */
@Composable
fun ClearHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (endConversation: Boolean) -> Unit,
) {
    var endConversation by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_conversation_history)) },
        text = {
            Column {
                Text(clearHistoryWarning())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.padding(top = 8.dp).clickable { endConversation = !endConversation },
                ) {
                    Checkbox(
                        checked = endConversation,
                        onCheckedChange = { endConversation = it },
                    )
                    Text(stringResource(R.string.archive_this_chat))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(endConversation) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DataR.string.cancel)) }
        },
    )
}

/**
 * The warning paragraph with its one bold run.
 *
 * <p>`clear_histor_msg` carries `<b>Warning:</b>`; a string resource's markup is a span, and Compose
 * draws text, not spans, so the one run is re-styled here. A resource whose marker is missing or
 * translated away draws as plain text rather than dropping words - the marker is English because the
 * interface is.
 */
@Composable
private fun clearHistoryWarning(): AnnotatedString {
    val message = stringResource(R.string.clear_histor_msg)
    val marker = "Warning:"
    val at = message.indexOf(marker)
    return buildAnnotatedString {
        if (at < 0) {
            append(message)
        } else {
            append(message, 0, at)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(message, at, at + marker.length)
            }
            append(message, at + marker.length, message.length)
        }
    }
}

/**
 * The conversation-language picker: a two-ended header, one line explaining what is being chosen, and
 * the single-choice list under it.
 *
 * <p>The list's first row is "Detect automatically" - index `0`, the absence of an override - and
 * every row after it is one selectable language; the names are built by the caller through
 * `ConversationLanguage.languageName`, so this file names no language. The app language is the fixed
 * end of the pair, shown in its own row with its tag and deliberately not one of the choices.
 *
 * <p>Picking a row answers [onChoose] with its index and closes the dialog, which is what
 * `setSingleChoiceItems` did; the caller maps the index back to a code. Cancel and the outside tap
 * both [onDismiss]. The list scrolls because the selectable set is the intersection of three
 * detectors and is longer than any dialog panel.
 *
 * @param appLanguage the app language's display name, the fixed end of the pair
 * @param theyWrite the conversation's end, already labelled with how it is known
 * @param items "Detect automatically" first, then one display name per selectable language
 * @param selectedIndex the row drawn as chosen; `0` when there is no override or the override's code
 *     is outside the selectable set
 */
@Composable
fun LanguagePickerDialog(
    appLanguage: String,
    theyWrite: String,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onChoose: (index: Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tulkki_language_picker_title)) },
        text = {
            Column {
                PairRows(appLanguage = appLanguage, theyWrite = theyWrite)
                Text(
                    text = stringResource(R.string.tulkki_language_picker_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                LazyColumn(modifier = Modifier.padding(top = 8.dp).heightIn(max = 320.dp)) {
                    itemsIndexed(items) { index, label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { onChoose(index) }
                                    .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = index == selectedIndex, onClick = { onChoose(index) })
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DataR.string.cancel)) }
        },
    )
}

/**
 * The picker's two-ended header: the app language with its own tag, and the conversation's end under
 * it. Ids and appearances are the deleted layout's: an 88 dp label column, the value at
 * `titleMedium`, the tag and the labels at the theme's secondary colour.
 */
@Composable
private fun PairRows(appLanguage: String, theyWrite: String) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            PairLabel(stringResource(R.string.tulkki_pair_you_label))
            Text(appLanguage, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.tulkki_pair_app_language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            PairLabel(stringResource(R.string.tulkki_pair_they_label))
            Text(theyWrite, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PairLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(88.dp),
    )
}
