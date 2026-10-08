package uk.xa0.tulkki.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.dialogs.AccountDropdown
import uk.xa0.tulkki.ui.dialogs.JidField
import uk.xa0.tulkki.ui.dialogs.selectedAccount
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The "join public channel" dialog: an account, an address, and a Join button that hands the host a
 * parsed JID.
 *
 * <p>It was a `DialogFragment` around `dialog_join_conference.xml`. The address parsing the host
 * used to do for it - `Jid.ofUserInput`, the `?join` URI branch, the `invalid_jid` error under the
 * field - is the dialog's own form validation now, and the host's listener takes the [Jid] it always
 * wanted instead of the address `TextInputLayout` and `AutoCompleteTextView` it used to reach
 * through. Everything else is the fragment's: the two strings, the account dropdown, the known-host
 * suggestions on the address field (`KnownHostsAdapter`), and the prefilled JID and password an
 * invite carries - the password only while the field still holds the prefilled address.
 */
class JoinConferenceDialog {

    fun interface JoinConferenceDialogListener {
        /** The account, the parsed address, and the prefilled password while it still applies. */
        fun onJoinDialogPositiveClick(account: Account, jid: Jid, password: String?)
    }

    companion object {

        @JvmStatic
        fun show(
            activity: XmppActivity,
            prefilledJid: String?,
            prefilledPassword: String?,
            accounts: List<String>,
            domains: List<String>,
            listener: JoinConferenceDialogListener,
        ) {
            activity.showTulkkiDialog { dismiss ->
                JoinConferenceDialogScreen(
                    accounts = accounts,
                    domains = domains,
                    prefilledJid = prefilledJid,
                    prefilledPassword = prefilledPassword,
                    onDismiss = dismiss,
                    onJoin = { account, jid, password ->
                        dismiss()
                        listener.onJoinDialogPositiveClick(account, jid, password)
                    },
                )
            }
        }
    }
}

/** The dialog's face: the deleted layout's two fields inside the alert the builder drew. */
@Composable
fun JoinConferenceDialogScreen(
    accounts: List<String>,
    domains: List<String>,
    prefilledJid: String?,
    prefilledPassword: String?,
    onDismiss: () -> Unit,
    onJoin: (Account, Jid, String?) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(accounts.firstOrNull()) }
    var address by remember { mutableStateOf(prefilledJid ?: "") }
    var invalid by remember { mutableStateOf(false) }

    fun join() {
        val typed = address
        val input = typed.trim { it <= ' ' }
        val jid = try {
            Jid.ofUserInput(input)
        } catch (e: IllegalArgumentException) {
            val uri = XmppUri(input)
            if (uri.isValidJid() && uri.isAction(XmppUri.ACTION_JOIN)) {
                uri.getJid() ?: throw NullPointerException()
            } else {
                invalid = true
                return
            }
        }
        invalid = false
        val account = selectedAccount(context, selected) ?: return
        // `password()`: the invite's password only while the field still holds the invite's address.
        onJoin(account, jid, if (typed == prefilledJid) prefilledPassword else null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.join_public_channel)) },
        text = {
            Column {
                AccountDropdown(
                    accounts = accounts,
                    selected = selected,
                    onSelect = { selected = it },
                )
                JidField(
                    value = address,
                    onValueChange = {
                        address = it
                        invalid = false
                    },
                    label = stringResource(R.string.xmpp_address),
                    placeholder = stringResource(R.string.channel_full_jid_example),
                    domains = domains,
                    isError = invalid,
                    supportingText = if (invalid) stringResource(R.string.invalid_jid) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    onImeAction = { join() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { join() }) { Text(stringResource(R.string.join)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}
