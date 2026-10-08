package uk.xa0.tulkki.ui

import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.crypto.axolotl.XmppAxolotlSession
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.omemo.ContactKeyAction
import uk.xa0.tulkki.ui.omemo.ContactKeyRowState
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.XmppUri

abstract class OmemoActivity : XmppActivity() {

    @JvmField
    protected var mPendingFingerprintVerificationUri: XmppUri? = null

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == ScanActivity.REQUEST_SCAN_QR_CODE && resultCode == RESULT_OK) {
            val result = (intent ?: throw NullPointerException())
                .getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            val uri = XmppUri(result ?: "")
            if (xmppConnectionServiceBound) {
                processFingerprintVerification(uri)
            } else {
                this.mPendingFingerprintVerificationUri = uri
            }
        }
    }

    protected abstract fun processFingerprintVerification(uri: XmppUri)

    protected fun copyOmemoFingerprint(fingerprint: String) {
        if (copyTextToClipboard(
                CryptoHelper.prettifyFingerprint(fingerprint.substring(2)),
                R.string.omemo_fingerprint,
            )
        ) {
            Toast.makeText(
                this,
                R.string.toast_message_omemo_fingerprint,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * One key row for [session]: what `addFingerprintRow` used to inflate into a `LinearLayout`,
     * resolved to the value its screen draws instead. The switch's listener is the same
     * `setFingerprintTrust(createActive(isChecked))` the old `OnCheckedChangeListener` ran.
     */
    protected fun contactKeyRow(session: XmppAxolotlSession, highlight: Boolean): ContactKeyRowState {
        val account = session.getAccount() as Account
        val fingerprint = session.getFingerprint() ?: throw NullPointerException()
        return contactKeyRow(
            account = account,
            fingerprint = fingerprint,
            highlight = highlight,
            status = session.getTrust(),
            showTag = true,
            undecidedNeedEnablement = true,
            onSwitchChanged = { isChecked ->
                (account.getAxolotlService() ?: throw NullPointerException()).setFingerprintTrust(
                    fingerprint,
                    FingerprintStatus.createActive(isChecked),
                )
            },
        )
    }

    /**
     * The row `addFingerprintRowWithListeners` built, view state for view state: the active/inactive
     * colours, the verified mark's two alphas with the switch hidden behind it, the enable-device
     * button for an undecided key, the key-type line (`showTag` and `highlight` arms), the toast the
     * row, the key and the type line carried, and the `omemo_key_context` items that were left
     * visible. The two mutation listeners are the old row's own.
     */
    protected fun contactKeyRow(
        account: Account,
        fingerprint: String,
        highlight: Boolean,
        status: FingerprintStatus,
        showTag: Boolean,
        undecidedNeedEnablement: Boolean,
        onSwitchChanged: (Boolean) -> Unit,
    ): ContactKeyRowState {
        val x509 =
            Config.X509_VERIFICATION &&
                status.getTrust() == FingerprintStatus.Trust.VERIFIED_X509
        val active = status.isActive()
        val verified = status.isVerified()
        var typeLabel: String? = null
        if (showTag) {
            typeLabel =
                getString(if (x509) R.string.omemo_fingerprint_x509 else R.string.omemo_fingerprint)
        }
        var verifiedAlpha = 1.0f
        var switchVisible = false
        var switchEnabled = true
        var enableDeviceVisible = false
        var tap: (() -> Unit)? = null

        if (active) {
            if (verified) {
                verifiedAlpha = 1.0f
                tap = { replaceToast(getString(R.string.this_device_has_been_verified), false) }
            } else {
                if (status.getTrust() == FingerprintStatus.Trust.UNDECIDED && undecidedNeedEnablement) {
                    enableDeviceVisible = true
                } else {
                    switchVisible = true
                }
                tap = { hideToast() }
            }
        } else {
            tap = { replaceToast(getString(R.string.this_device_is_no_longer_in_use), false) }
            if (verified) {
                verifiedAlpha = 0.4368f
            } else {
                switchVisible = true
                switchEnabled = false
            }
        }
        if (highlight) {
            typeLabel =
                getString(
                    if (x509) {
                        R.string.omemo_fingerprint_x509_selected_message
                    } else {
                        R.string.omemo_fingerprint_selected_message
                    },
                )
        }

        return ContactKeyRowState(
            key = CryptoHelper.prettifyFingerprint(fingerprint.substring(2)),
            typeLabel = typeLabel,
            typeHighlighted = highlight,
            active = active,
            verified = verified,
            verifiedAlpha = verifiedAlpha,
            switchVisible = switchVisible,
            switchChecked = status.isTrusted(),
            switchEnabled = switchEnabled,
            enableDeviceVisible = enableDeviceVisible,
            actions = keyContextActions(account, fingerprint, status),
            onTap = tap,
            onSwitchChanged = onSwitchChanged,
            onEnableDevice = {
                (account.getAxolotlService() ?: throw NullPointerException())
                    .setFingerprintTrust(fingerprint, FingerprintStatus.createActive(false))
            },
        )
    }

    /**
     * The `omemo_key_context.xml` items `onCreateContextMenu` left visible, in the menu's own order
     * (`verify_scan`, `distrust_key`, `copy_omemo_key`) with its two `isVisible` arms. The old
     * `this is TrustKeysActivity` arm is kept: that screen draws its own rows and never asks for the
     * menu, but the visibility rule was the base class's.
     */
    private fun keyContextActions(
        account: Account,
        fingerprint: String,
        status: FingerprintStatus,
    ): List<ContactKeyAction> {
        if (this is TrustKeysActivity) {
            return emptyList()
        }
        val actions = ArrayList<ContactKeyAction>()
        if (status.isActive() && !status.isVerified()) {
            actions.add(ContactKeyAction(getString(R.string.scan_qr_code)) { ScanActivity.scan(this) })
        }
        if (status.isVerified() || (!status.isActive() && status.isTrusted())) {
            actions.add(
                ContactKeyAction(getString(R.string.distrust_omemo_key)) {
                    showPurgeKeyDialog(account, fingerprint)
                }
            )
        }
        actions.add(
            ContactKeyAction(getString(R.string.copy_fingerprint)) {
                copyOmemoFingerprint(fingerprint)
            }
        )
        return actions
    }

    fun showPurgeKeyDialog(account: Account, fingerprint: String) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.distrust_omemo_key)
        builder.setMessage(R.string.distrust_omemo_key_text)
        builder.setNegativeButton(getString(DataR.string.cancel), null)
        builder.setPositiveButton(R.string.confirm) { _, _ ->
            (account.getAxolotlService() ?: throw NullPointerException())
                .distrustFingerprint(fingerprint)
            refreshUi()
        }
        builder.create().show()
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults)
    }
}
