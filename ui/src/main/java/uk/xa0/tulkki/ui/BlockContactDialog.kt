package uk.xa0.tulkki.ui

import android.widget.Toast

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

import uk.xa0.tulkki.data.model.Blockable
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.dialogs.styledJid

/**
 * The block/unblock dialog, as the Compose dialog `showTulkkiDialog` hosts.
 *
 * <p>The layout it replaces was a `TextView` and a "report as spam" `CheckBox` in a `LinearLayout`;
 * the title, the text and the two buttons were `MaterialAlertDialogBuilder`'s. It is now one
 * [AlertDialog] whose title, text and buttons are the builder's three calls - the strings, the
 * branches that pick them, the checkbox's visibility rule and the two side effects are the code's
 * own, unchanged.
 */
object BlockContactDialog {

    @JvmStatic
    @JvmOverloads
    fun show(xmppActivity: XmppActivity, blockable: Blockable?, serverMsgId: String? = null) {
        // Java dereferenced the blockable it was handed; the caller's Kotlin type is nullable
        // (`ContactDetailsActivity` passes a `Contact?`), so the dereference is named here.
        val target = blockable ?: throw NullPointerException()
        val isBlocked = target.isBlocked()
        val jid = target.getJid() ?: throw NullPointerException()
        val account = target.getAccount() ?: throw NullPointerException()
        val reporting =
            (account.getXmppConnection() ?: throw NullPointerException()).getFeatures().spamReporting()

        val value: String
        @StringRes val titleRes: Int
        @StringRes val textRes: Int
        if (jid.isFullJid()) {
            titleRes =
                if (isBlocked) R.string.action_unblock_participant
                else R.string.action_block_participant
            value = jid.toString()
            textRes = if (isBlocked) R.string.unblock_contact_text else R.string.block_contact_text
        } else if (jid.getLocal() == null || account.isBlocked(jid.getDomain())) {
            titleRes =
                if (isBlocked) R.string.action_unblock_domain else R.string.action_block_domain
            value = jid.getDomain().toString()
            textRes = if (isBlocked) R.string.unblock_domain_text else R.string.block_domain_text
        } else {
            titleRes = if (isBlocked) {
                R.string.action_unblock_contact
            } else if (serverMsgId != null) {
                R.string.report_spam_and_block
            } else if (target is Conversation && target.isWithStranger()) {
                R.string.block_stranger
            } else {
                R.string.action_block_contact
            }
            value = jid.asBareJid().toString()
            textRes = if (isBlocked) R.string.unblock_contact_text else R.string.block_contact_text
        }

        // The view was checked and disabled when the message carried a server id; hidden when the
        // account cannot report at all.
        val showReportSpam = reporting && !isBlocked

        xmppActivity.showTulkkiDialog { dismiss ->
            BlockContactDialogBody(
                titleRes = titleRes,
                textRes = textRes,
                value = value,
                showReportSpam = showReportSpam,
                reportSpamChecked = serverMsgId != null,
                reportSpamEnabled = serverMsgId == null,
                confirmRes = if (isBlocked) R.string.unblock else R.string.block,
                onDismiss = dismiss,
            ) { report ->
                dismiss()
                if (isBlocked) {
                    xmppActivity.xmppConnectionService.sendUnblockRequest(
                        target.getAccount(), target.getJid(), target.getBlockedJid())
                } else {
                    var toastShown = false
                    var finalServerId = serverMsgId
                    if (serverMsgId == null && report && target is Conversation) {
                        finalServerId = target.getLatestMessage().getServerMsgId()
                    }
                    if (xmppActivity.xmppConnectionService.sendBlockRequest(
                            target.getAccount(), target.getBlockedJid(), report, finalServerId)) {
                        Toast.makeText(
                                xmppActivity,
                                R.string.corresponding_chats_closed,
                                Toast.LENGTH_SHORT)
                            .show()
                        toastShown = true
                    }
                    if (xmppActivity is ContactDetailsActivity) {
                        if (!toastShown) {
                            Toast.makeText(
                                    xmppActivity,
                                    R.string.contact_blocked_past_tense,
                                    Toast.LENGTH_SHORT)
                                .show()
                        }
                        xmppActivity.finish()
                    }
                }
            }
        }
    }
}

/**
 * The dialog's face: the deleted layout's `TextView` (its address monospaced, the way
 * `JidDialog.style` drew it) and its checkbox, inside the alert the builder used to draw.
 */
@Composable
fun BlockContactDialogBody(
    @StringRes titleRes: Int,
    @StringRes textRes: Int,
    value: String,
    showReportSpam: Boolean,
    reportSpamChecked: Boolean,
    reportSpamEnabled: Boolean,
    @StringRes confirmRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (report: Boolean) -> Unit,
) {
    // A hidden checkbox was unchecked in the view, so a dialog that does not draw the row reports
    // nothing; the checked-and-disabled case only exists while the row is drawn.
    var report by remember { mutableStateOf(showReportSpam && reportSpamChecked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(styledJid(stringResource(textRes, value), value))
                if (showReportSpam) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable(enabled = reportSpamEnabled) { report = !report },
                    ) {
                        Checkbox(
                            checked = report,
                            onCheckedChange = { report = it },
                            enabled = reportSpamEnabled,
                        )
                        Text(stringResource(R.string.report_jid_as_spammer))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(report) }) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}
