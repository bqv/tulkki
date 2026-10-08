package uk.xa0.tulkki.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import uk.xa0.tulkki.translation.ConversationLanguage
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsRows
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The three editors a row's key opens, as Composable dialogs the **host** shows.
 *
 * <p>They live here rather than in the screen for the reason the ledger's price dialog lives in its
 * host: a dialog needs the value *in force*, and the value in force is the host's reading - the stored
 * key, the base URL, the wording `PromptBook` has, the language code the picker offers. The screen
 * above them draws rows and emits taps, and takes no view of which editor is open.
 *
 * <p>Each opens holding what is in force and hands the committed value back; nothing is read or
 * written here, and no dialog validates beyond what it can see - a cap that is not a number cannot be
 * committed because the field only offers the committed value when it parses, and a blank field means
 * what the field's own convention says (the store's "blank is the shipped wording", the ledger's
 * "blank is the page's number").
 */

/** One editable string: the key (masked), the base URL, or one of the three instructions. */
@Composable
fun SettingsTextEditorDialog(
    @StringRes title: Int,
    initial: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
    masked: Boolean = false,
    multiline: Boolean = false,
    hint: String? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = !multiline,
                minLines = if (multiline) MULTILINE_MIN_LINES else 1,
                visualTransformation =
                    if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                placeholder = if (hint == null) null else { { Text(hint) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCommit(value) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * The daily cap. The field is digits-only, so anything else is a paste or an empty dialog; OK is
 * offered only when the text parses, which is the honest version of the store's "keep the old cap" -
 * the owner sees that the value is not a number instead of the write silently not happening.
 */
@Composable
fun SettingsNumberEditorDialog(
    @StringRes title: Int,
    initial: Int,
    onDismiss: () -> Unit,
    onCommit: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initial.toString()) }
    val parsed = value.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onCommit) }, enabled = parsed != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * One language, from the list the row's own picker offers - `TulkkiSettingsRows.pickerValues`, the
 * sentinel first, then the languages the three detectors agree on, then a stored value nothing else
 * knows. The sentinel is asked first, because nothing may render it through the detector-derived name
 * path: a row that printed a language for the one value that means "no interpreter at all" would name
 * a language nobody selected.
 */
@Composable
fun SettingsLanguagePickerDialog(
    @StringRes title: Int,
    codes: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = TulkkiSpacing.xxl * 10)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
            ) {
                for (code in codes) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == selected, onClick = { onCommit(code) })
                        Text(languageLabel(code), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** "fi" -> "Finnish", and the sentinel -> the mode's own words; never a language for `none`. */
@Composable
private fun languageLabel(code: String): String =
    if (TulkkiSettingsRows.isTheOffState(code)) {
        stringResource(R.string.tulkki_language_none)
    } else {
        ConversationLanguage.languageName(code)
    }

/** Seven, the old dialog's own minimum for an instruction nobody can rewrite in one line. */
private const val MULTILINE_MIN_LINES = 7
