package uk.xa0.tulkki.ui

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.logging.Level
import java.util.logging.Logger
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.MTMDecision
import uk.xa0.tulkki.ui.util.SettingsUtils
import uk.xa0.tulkki.xmpp.R as XmppR
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager

class MemorizingActivity : AppCompatActivity(),
    DialogInterface.OnClickListener,
    DialogInterface.OnCancelListener {

    private var decisionId = 0
    private lateinit var dialog: AlertDialog

    public override fun onCreate(savedInstanceState: Bundle?) {
        LOGGER.log(Level.FINE, "onCreate")
        super.onCreate(savedInstanceState)
    }

    public override fun onResume() {
        super.onResume()
        SettingsUtils.applyScreenshotSetting(this)

        val i = intent
        decisionId =
            i.getIntExtra(MemorizingTrustManager.DECISION_INTENT_ID, MTMDecision.DECISION_INVALID)
        val titleId =
            i.getIntExtra(MemorizingTrustManager.DECISION_TITLE_ID, XmppR.string.mtm_accept_cert)
        val cert = i.getStringExtra(MemorizingTrustManager.DECISION_INTENT_CERT)
        LOGGER.log(
            Level.FINE,
            "onResume with " + i.extras + " decId=" + decisionId + " data: " + i.data,
        )
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleId)
            .setMessage(cert)
            .setPositiveButton(R.string.always, this)
            .setNeutralButton(R.string.once, this)
            .setNegativeButton(DataR.string.cancel, this)
            .setOnCancelListener(this)
            .create()
        dialog.show()
    }

    override fun onPause() {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
        super.onPause()
    }

    private fun sendDecision(decision: Int) {
        LOGGER.log(Level.FINE, "Sending decision: " + decision)
        MemorizingTrustManager.interactResult(decisionId, decision)
        finish()
    }

    // react on AlertDialog button press
    override fun onClick(dialog: DialogInterface, which: Int) {
        val decision: Int
        dialog.dismiss()
        when (which) {
            DialogInterface.BUTTON_POSITIVE -> decision = MTMDecision.DECISION_ALWAYS
            DialogInterface.BUTTON_NEUTRAL -> decision = MTMDecision.DECISION_ONCE
            else -> decision = MTMDecision.DECISION_ABORT
        }
        sendDecision(decision)
    }

    override fun onCancel(dialog: DialogInterface) {
        sendDecision(MTMDecision.DECISION_ABORT)
    }

    companion object {
        private val LOGGER = Logger.getLogger(MemorizingActivity::class.java.name)
    }
}
