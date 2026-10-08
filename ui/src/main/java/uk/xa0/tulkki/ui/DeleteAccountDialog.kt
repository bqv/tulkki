package uk.xa0.tulkki.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp

/**
 * The account-deletion dialog: `dialog_delete_account.xml`, deleted with it.
 *
 * <p>The layout was the confirmation paragraph and a "delete from server" `CheckBox` in a
 * `LinearLayout`; the title and the two buttons were `MaterialAlertDialogBuilder`'s. It is one
 * [AlertDialog] whose title, text and buttons are the builder's own calls, with the same
 * `?textAppearanceBodyMedium` paragraph and the same unchecked-by-default box, and
 * [DeleteAccountDialogState] carries the two facts the deleted code held in the view: while the
 * unregistration is in flight the confirm button says "please wait" and is disabled, and the box is
 * disabled with it; a failed result puts all three back.
 *
 * <p>**The confirm listener does not dismiss by itself.** The old positive button's own listener
 * either closed the dialog or left it up with the button re-enabled; here that is `onConfirm`,
 * which the host answers by writing [DeleteAccountDialogState.waiting] and calling its dismissal
 * handle - the same two outcomes, one place further out. Dismissing by the outside tap is still
 * allowed, exactly as the builder left it.
 *
 * @param onDismiss the outside tap, the back gesture or the Cancel button.
 * @param onConfirm the Delete button, with the checkbox as it stood.
 * @param state the in-flight flag, created by the host so an asynchronous result can clear it. The
 *     default is the dialog's own, which is what a cell wants.
 */
@Composable
fun DeleteAccountDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    @StringRes checkboxRes: Int,
    @StringRes confirmRes: Int,
    @StringRes waitingRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteFromServer: Boolean) -> Unit,
    state: DeleteAccountDialogState = remember { DeleteAccountDialogState() },
) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(messageRes))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.padding(top = 8.dp).clickable(enabled = !state.waiting) {
                            checked = !checked
                        },
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        enabled = !state.waiting,
                    )
                    Text(stringResource(checkboxRes))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked) }, enabled = !state.waiting) {
                Text(stringResource(if (state.waiting) waitingRes else confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}

/**
 * The account-deletion dialog's one piece of state the host owns: whether the server-side
 * unregistration it started is still in flight.
 *
 * <p>The old code wrote this into the view (`button.setText(R.string.please_wait)`,
 * `setEnabled(false)`, `deleteFromServer.setEnabled(false)`) and undid it in the failure arm of the
 * unregistration callback. The dialog reads it; the host is the only writer.
 */
class DeleteAccountDialogState {

    /** True while the host awaits the server's answer, which is the deleted button's own state. */
    internal var waiting by mutableStateOf(false)
}
