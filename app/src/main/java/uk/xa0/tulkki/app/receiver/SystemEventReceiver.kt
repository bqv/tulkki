package uk.xa0.tulkki.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import com.google.common.base.Strings
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The one receiver every explicit alarm and boot broadcast lands in, and the two strings other
 * classes read off it.
 *
 * <p>**The two constants live in a companion `const val`, which is what keeps the JVM surface
 * unchanged.** Java callers (`XmppTulkkiHost`'s adapter) still read `SystemEventReceiver
 * .EXTRA_NEEDS_FOREGROUND_SERVICE` and `SystemEventReceiver.SETTING_ENABLED_ACCOUNTS` as static
 * fields, because a companion `const val` compiles to one on the class. Kotlin callers must not
 * import the member through the class name - that is a Java-static import and has no companion
 * spelling - so `ConnectionService`, `UpdateNowPlayingService` and `Compatibility` name the constant
 * through the class instead (`9ffa85c873`, `4681e34e50`, `bbd46bf1d7`), which resolves on both sides
 * of this port.
 *
 * <p>`hasEnabledAccounts` was a `public static` helper and nothing on the Java side calls it; it is a
 * companion function now (reachable unqualified from `onReceive`), with no `@JvmStatic` because no
 * Java caller needs the static spelling.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, originalIntent: Intent) {
        val intentForService = Intent(context, XmppConnectionService::class.java)
        val action = originalIntent.action
        intentForService.action = if (Strings.isNullOrEmpty(action)) "other" else action
        val extras: Bundle? = originalIntent.extras
        if (extras != null) {
            intentForService.putExtras(extras)
        }
        if ("ui" == action || hasEnabledAccounts(context)) {
            Compatibility.startService(context, intentForService)
        } else {
            Log.d(Config.LOGTAG, "EventReceiver ignored action " + intentForService.action)
        }
    }

    companion object {

        const val SETTING_ENABLED_ACCOUNTS = "enabled_accounts"
        const val EXTRA_NEEDS_FOREGROUND_SERVICE = "needs_foreground_service"

        fun hasEnabledAccounts(context: Context): Boolean =
                PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(SETTING_ENABLED_ACCOUNTS, true)
    }
}
