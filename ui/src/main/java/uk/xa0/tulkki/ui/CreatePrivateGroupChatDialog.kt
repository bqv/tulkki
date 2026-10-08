package uk.xa0.tulkki.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.dialogs.AccountDropdown
import uk.xa0.tulkki.ui.dialogs.selectedAccount
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog

/**
 * The "create private group chat" dialog: an account dropdown and a name, and a button that hands
 * both to the host and opens the participant picker.
 *
 * <p>It was a `DialogFragment` around `dialog_create_conference.xml`, with the account spinner and
 * the field's delayed hint (`DelayedHintHelper.setHint`, the hint appearing once the field takes
 * focus). The dropdown and the hint keep the same behaviour and the same strings; the listener is
 * now handed the chosen [Account] and the trimmed name instead of the spinner view, because the
 * view is what the conversion deletes.
 *
 * <p>One deliberate difference: the dialog closes as the picker opens, where the fragment stayed in
 * the `FragmentManager` behind it and a cancelled picker landed back on a stale dialog. Choosing the
 * participants is the only way forward, and the fragment's lifetime was not part of the dialog.
 */
class CreatePrivateGroupChatDialog {

    fun interface CreateConferenceDialogListener {
        /** The chosen account (null when there is none) and the name typed, trimmed. */
        fun onCreateDialogPositiveClick(account: Account?, name: String)
    }

    companion object {

        @JvmStatic
        fun show(
            activity: XmppActivity,
            accounts: List<String>,
            listener: CreateConferenceDialogListener,
        ) {
            activity.showTulkkiDialog { dismiss ->
                CreatePrivateGroupChatDialogScreen(
                    accounts = accounts,
                    onDismiss = dismiss,
                    onChoose = { accountJid, name ->
                        dismiss()
                        listener.onCreateDialogPositiveClick(
                            selectedAccount(activity, accountJid), name)
                    },
                )
            }
        }
    }
}

/** The dialog's face: the deleted layout's two fields inside the alert the builder drew. */
@Composable
fun CreatePrivateGroupChatDialogScreen(
    accounts: List<String>,
    onDismiss: () -> Unit,
    onChoose: (accountJid: String?, name: String) -> Unit,
) {
    var selected by remember { mutableStateOf(accounts.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_private_group_chat)) },
        text = {
            Column {
                AccountDropdown(
                    accounts = accounts,
                    selected = selected,
                    onSelect = { selected = it },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.create_dialog_group_chat_name)) },
                    // `DelayedHintHelper` put the hint in only while the field had focus.
                    placeholder = if (focused) {
                        { Text(stringResource(R.string.providing_a_name_is_optional)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onChoose(selected, name.trim()) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected, name.trim()) }) {
                Text(stringResource(R.string.choose_participants))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}
