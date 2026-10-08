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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.dialogs.AccountDropdown
import uk.xa0.tulkki.ui.dialogs.JidField
import uk.xa0.tulkki.ui.dialogs.selectedAccount
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * The "create public channel" dialog: the channel's name first, then its XMPP address.
 *
 * <p>It was a `DialogFragment` around `dialog_create_public_channel.xml`, and its two steps, its
 * per-step buttons (Next/Cancel, then Create/Back), the address suggestion derived from the account's
 * MUC server, the "this is an XMPP address" and "this channel already exists" refusals, and the two
 * error slots the layout's `TextInputLayout`s carried are all the fragment's own logic, unchanged.
 * The address field's known-host suggestions are `KnownHostsAdapter`'s filter (see `JidField`).
 *
 * <p>Like its siblings this file no longer keeps the entered state across a rotation: the state was
 * the fragment's, and a Compose dialog over a view screen is the screen's state.
 */
class CreatePublicChannelDialog {

    interface CreatePublicChannelDialogListener {
        fun onCreatePublicChannel(account: Account, name: String, address: Jid)
    }

    companion object {

        @JvmStatic
        fun show(
            activity: XmppActivity,
            accounts: List<String>,
            domains: List<String>,
            listener: CreatePublicChannelDialogListener,
        ) {
            activity.showTulkkiDialog { dismiss ->
                CreatePublicChannelDialogScreen(
                    accounts = accounts,
                    domains = domains,
                    onDismiss = dismiss,
                    onCreate = { account, name, jid ->
                        dismiss()
                        listener.onCreatePublicChannel(account, name, jid)
                    },
                )
            }
        }
    }
}

/**
 * The dialog's face: the two steps the deleted layout swapped between, with the same fields, the
 * same buttons and the same error text.
 */
@Composable
fun CreatePublicChannelDialogScreen(
    accounts: List<String>,
    domains: List<String>,
    onDismiss: () -> Unit,
    onCreate: (Account, String, Jid) -> Unit,
) {
    val context = LocalContext.current
    var nameEntered by remember { mutableStateOf(false) }
    var jidWasModified by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(accounts.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<Int?>(null) }
    var addressError by remember { mutableStateOf<Int?>(null) }
    val nameFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    var firstPass by remember { mutableStateOf(true) }

    fun suggestion(accountJid: String?, nameText: String): String {
        val account = selectedAccount(context, accountJid) ?: return ""
        val connection = account.getXmppConnection() ?: return ""
        val domain = connection.getMucServer() ?: return ""
        val localpart = cleanLocal(nameText.trim { it <= ' ' })
        if (localpart.isEmpty()) {
            return ""
        }
        return try {
            Jid.of(localpart, domain, null).toString()
        } catch (e: IllegalArgumentException) {
            Jid.of(CryptoHelper.pronounceable(), domain, null).toString()
        }
    }

    fun submit() {
        if (nameEntered) {
            nameError = null
            val typed = address.trim { it <= ' ' }
            if (typed.isEmpty()) {
                addressError = R.string.please_enter_xmpp_address
                return
            }
            val jid = try {
                Jid.of(typed)
            } catch (e: IllegalArgumentException) {
                addressError = R.string.invalid_jid
                return
            }
            val account = selectedAccount(context, selected) ?: return
            val service: XmppConnectionService? =
                (context as? XmppActivity)?.xmppConnectionService
            if (service != null && service.findFirstMuc(jid) != null) {
                addressError = R.string.channel_already_exists
                return
            }
            onCreate(account, name.trim { it <= ' ' }, jid)
        } else {
            addressError = null
            val typed = name.trim { it <= ' ' }
            if (typed.isEmpty()) {
                nameError = R.string.please_enter_name
            } else if (StartConversationActivity.isValidJid(typed)) {
                nameError = R.string.this_is_an_xmpp_address
            } else {
                nameError = null
                nameEntered = true
                address = suggestion(selected, typed)
            }
        }
    }

    // The step that opens takes the focus, as `updateInputs(binding, requestFocus = true)` did; the
    // opening state takes none (`requestFocus = false`).
    LaunchedEffect(nameEntered) {
        if (firstPass) {
            firstPass = false
        } else if (nameEntered) {
            addressFocus.requestFocus()
        } else {
            nameFocus.requestFocus()
        }
    }

    // The error slot is a composable, not a resolved string - `setError` landed on the old layout,
    // and `supportingText` draws what it says.
    val nameErrorRes = nameError
    val nameSupport: (@Composable () -> Unit)? =
        if (nameErrorRes == null) {
            null
        } else {
            { Text(stringResource(nameErrorRes)) }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_public_channel)) },
        text = {
            Column {
                AccountDropdown(
                    accounts = accounts,
                    selected = selected,
                    onSelect = { account ->
                        selected = account
                        if (!jidWasModified) {
                            address = suggestion(account, name)
                        }
                    },
                )
                if (nameEntered) {
                    JidField(
                        value = address,
                        onValueChange = { typed ->
                            address = typed
                            jidWasModified = if (jidWasModified) {
                                typed.isNotEmpty()
                            } else {
                                typed != suggestion(selected, name)
                            }
                        },
                        label = stringResource(R.string.xmpp_address),
                        placeholder = stringResource(R.string.channel_bare_jid_example),
                        domains = domains,
                        isError = addressError != null,
                        supportingText = addressError?.let { stringResource(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        focusRequester = addressFocus,
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.create_dialog_channel_name)) },
                        isError = nameError != null,
                        supportingText = nameSupport,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .focusRequester(nameFocus),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                Text(
                    stringResource(
                        if (nameEntered) R.string.create else R.string.next))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (nameEntered) {
                        nameEntered = false
                        addressError = null
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (nameEntered) R.string.back
                        else uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}

/** `CreatePublicChannelDialog.clean`, which strips the characters a channel's local part forbids. */
private fun cleanLocal(name: String): String {
    var cleaned = name
    for (c in FORBIDDEN) {
        cleaned = cleaned.replace(c.toString(), "")
    }
    return cleaned.replace(Regex("\\s+"), "-")
}

private val FORBIDDEN = charArrayOf('\u0022', '&', '\'', '/', ':', '<', '>', '@')
