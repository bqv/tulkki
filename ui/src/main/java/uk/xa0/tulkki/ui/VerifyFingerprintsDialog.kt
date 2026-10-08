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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import uk.xa0.tulkki.ui.dialogs.styledJid

/**
 * The fingerprint-verification warning: `dialog_verify_fingerprints.xml`, deleted with it.
 *
 * <p>The layout was a warning `TextView` and a "trusted source" `CheckBox` in a `LinearLayout`; the
 * title, the Continue and the Cancel were `MaterialAlertDialogBuilder`'s. It is one [AlertDialog]
 * with that title, that box and those two buttons, and the two facts the deleted code held in the
 * dialog are kept: the outside tap does not close it (`setCanceledOnTouchOutside(false)`), and the
 * back gesture does (the old `setOnCancelListener`), which is why [DialogProperties] turns off
 * [DialogProperties.dismissOnClickOutside] only.
 *
 * <p>**The two hosts' difference is the warning, and it is named here rather than drawn twice.**
 * `EditAccountActivity` verifies the owner's own account and used a plain string with no arguments;
 * `StartConversationActivity` verifies a contact's keys and composed the sentence with the contact's
 * bare JID and display name, monospaced on the JID by `JidDialog.style`. [warningArgs] is those
 * arguments - empty for the account, two for the contact - and the first of them is what
 * [styledJid] sets in monospace, the same span in the same place.
 *
 * <p>What each host answers on the buttons is not the dialog's: it is [onConfirm] and [onDismiss],
 * called in the order the host's own listener ran. The checkbox is the only local state, unchecked
 * on open as the XML left it.
 */
@Composable
fun VerifyFingerprintsDialog(
    @StringRes warningRes: Int,
    @StringRes confirmRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (trusted: Boolean) -> Unit,
    warningArgs: List<String> = emptyList(),
) {
    var trusted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.verify_omemo_keys)) },
        text = {
            Column {
                Text(warning(warningRes, warningArgs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.padding(top = 8.dp).clickable { trusted = !trusted },
                ) {
                    Checkbox(checked = trusted, onCheckedChange = { trusted = it })
                    Text(stringResource(R.string.i_followed_this_link_from_a_trusted_source))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trusted) }) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}

/** The warning sentence, its first argument monospaced - `JidDialog.style`'s own span. */
@Composable
private fun warning(@StringRes res: Int, args: List<String>): AnnotatedString {
    if (args.isEmpty()) {
        return AnnotatedString(stringResource(res))
    }
    return styledJid(stringResource(res, *args.toTypedArray()), args[0])
}
