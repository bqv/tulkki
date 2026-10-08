package uk.xa0.tulkki.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The one-field editor: `:data`'s `dialog_quickedit.xml`, deleted with it.
 *
 * <p>The layout was a `TextInputLayout` with `app:errorEnabled` around an `EditText`; the title was
 * the caller's hint, the Accept and the Cancel were `MaterialAlertDialogBuilder`'s, and the positive
 * button carried its own listener because a value the callback rejects kept the dialog open. All of
 * that is here: an [OutlinedTextField] whose label is the hint (nothing when the caller passes
 * `hint == 0`, which is what `if (hint != 0) setHint(...)` did), whose error line is the callback's
 * answer, and whose confirm closes only on success. The field takes the focus and the keyboard on
 * open (`requestFocus()` plus `SoftKeyboardUtils.showKeyboard`), the prefilled value is selected when
 * the caller asked for it (`selectAll()`), and the keyboard is dropped on every close.
 *
 * <p>**The validation moved in, not out.** The deleted listener ran
 * `(alwaysCallback || value != previousValue) && (value.trim().isNotEmpty() || permitEmpty)` before
 * calling the callback, and returned without dismissing when the callback answered with an error;
 * that is [QuickEditDialog]'s own confirm, so the one caller that answers with an error (the display
 * name) still sees it in the field.
 *
 * <p>The deleted blank value had almost no representation to keep: `append(previousValue)` was
 * skipped when the previous value was `null`, which leaves the same empty field a `""` does.
 *
 * @param previousValue the field's own value to start from, `null` for none.
 * @param hintRes the field's label, or `0` for a field with no label - which is what the deleted
 *     `setHint` call was skipped for.
 * @param password whether the field masks what is typed, the deleted
 *     `TYPE_TEXT_VARIATION_PASSWORD`.
 * @param permitEmpty whether an all-blank value is accepted anyway.
 * @param alwaysCallback whether the callback runs even for an unchanged value.
 * @param startSelected whether the prefilled value opens selected.
 * @param onDismiss the outside tap, the back gesture, the Cancel button or a confirm that
 *     succeeded.
 * @param onValueEdited the callback the deleted listener called, whose non-null answer is the
 *     field's error and keeps the dialog open.
 */
@Composable
fun QuickEditDialog(
    previousValue: String?,
    @StringRes hintRes: Int,
    password: Boolean,
    permitEmpty: Boolean,
    alwaysCallback: Boolean,
    startSelected: Boolean,
    onDismiss: () -> Unit,
    onValueEdited: (String) -> String?,
) {
    val initial = previousValue ?: ""
    var value by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial,
                // `selectAll()`: an empty field has nothing to select either way.
                selection = if (startSelected) TextRange(0, initial.length) else TextRange(initial.length),
            ),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // `setOnDismissListener { hideSoftKeyboard(...) }`: the keyboard goes with the dialog.
    val close: () -> Unit = {
        keyboard?.hide()
        onDismiss()
    }
    val confirm: () -> Unit = {
        val text = value.text
        if ((alwaysCallback || text != previousValue) &&
            (text.trim { it <= ' ' }.isNotEmpty() || permitEmpty)
        ) {
            val answer = onValueEdited(text)
            if (answer != null) {
                error = answer
            } else {
                close()
            }
        } else {
            close()
        }
    }
    AlertDialog(
        onDismissRequest = close,
        // `setCanceledOnTouchOutside(false)`: the back gesture still closes it, as it did.
        properties = DialogProperties(dismissOnClickOutside = false),
        text = {
            QuickEditField(
                value = value,
                onValueChange = {
                    value = it
                    error = null
                },
                hintRes = hintRes,
                password = password,
                isError = error != null,
                errorText = error,
                imeAction = ImeAction.Done,
                onImeAction = confirm,
                focusRequester = focus,
            )
        },
        confirmButton = {
            TextButton(onClick = confirm) { Text(stringResource(R.string.accept)) }
        },
        dismissButton = {
            TextButton(onClick = close) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}

/**
 * The moderator's reason dialog: `dialog_quickedit.xml` was the field it held, and this is the alert
 * around it.
 *
 * <p>`MucDetailsContextMenuHelper.maybeModerateRecent` built a `MaterialAlertDialogBuilder` with the
 * "moderate recent messages" title and question, prefilled the quick-edit field with "spam" and sent
 * that text as the reason for every message. The alert is [QuickEditDialog]'s shape here: the same
 * title and question the builder set, the same prefilled field with no hint and no error line, and
 * the same Yes/No pair.
 *
 * @param onDismiss the outside tap, the back gesture or No.
 * @param onModerate Yes, with the reason field's text as it stood.
 */
@Composable
fun ModerateRecentDialog(
    onDismiss: () -> Unit,
    onModerate: (reason: String) -> Unit,
) {
    val spam = stringResource(R.string.spam)
    var reason by remember { mutableStateOf(TextFieldValue(spam)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderate_recent)) },
        text = {
            Column {
                Text(stringResource(R.string.moderate_recent_question))
                QuickEditField(
                    value = reason,
                    onValueChange = { reason = it },
                    hintRes = 0,
                    password = false,
                    isError = false,
                    errorText = null,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onModerate(reason.text) }) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.no))
            }
        },
    )
}

/**
 * The field both dialogs above draw: the deleted `TextInputLayout` and its `EditText`, with
 * `app:errorEnabled` answered by [errorText] and the ems-10 single line kept.
 */
@Composable
internal fun QuickEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    @StringRes hintRes: Int,
    password: Boolean,
    isError: Boolean,
    errorText: String?,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = if (hintRes == 0) null else { { Text(stringResource(hintRes)) } },
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        isError = isError,
        supportingText = if (errorText == null) null else { { Text(errorText) } },
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        modifier =
            modifier.fillMaxWidth().then(
                if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
            ),
    )
}
