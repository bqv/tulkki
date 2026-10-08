package uk.xa0.tulkki.app.utils

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.AccountConfiguration
import uk.xa0.tulkki.ui.EditAccountActivity
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Takes a provisioning JSON blob and either refuses it out loud or starts the service that creates
 * the account.
 *
 * <p>An `object` with `@JvmStatic`s: `UiAppHost` is the only caller and nothing ever constructed the
 * class.
 *
 * <p>The two `R` classes are kept apart exactly as the Java had them -
 * `uk.xa0.tulkki.xmpp.R` is imported and `uk.xa0.tulkki.ui.R` is spelled out in full at its one use
 * - so the resource ids resolve to the same two classes they did before.
 *
 * <p>The `IllegalArgumentException` catch is the same catch: a malformed blob shows the
 * improperly-formatted toast and returns, and nothing after it runs.
 */
object ProvisioningUtils {

    @JvmStatic
    fun provision(activity: Activity, json: String) {
        val accountConfiguration: AccountConfiguration
        try {
            accountConfiguration = AccountConfiguration.parse(json)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(
                            activity,
                            uk.xa0.tulkki.ui.R.string.improperly_formatted_provisioning,
                            Toast.LENGTH_LONG)
                    .show()
            return
        }
        val jid: Jid = accountConfiguration.getJid()
        val accounts: List<Jid> =
                DatabaseBackend.getInstance(activity).getAccountJids(true)
        if (accounts.contains(jid)) {
            Toast.makeText(activity, R.string.account_already_exists, Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(activity, XmppConnectionService::class.java)
        serviceIntent.action = uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_PROVISION_ACCOUNT
        serviceIntent.putExtra("address", jid.asBareJid().toString())
        serviceIntent.putExtra("password", accountConfiguration.password)
        Compatibility.startService(activity, serviceIntent)
        val intent = Intent(activity, EditAccountActivity::class.java)
        intent.putExtra("jid", jid.asBareJid().toString())
        intent.putExtra("init", true)
        activity.startActivity(intent)
    }
}
