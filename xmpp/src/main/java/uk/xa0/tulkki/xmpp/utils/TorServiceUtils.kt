package uk.xa0.tulkki.xmpp.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import me.drakeet.support.toast.ToastCompat
import uk.xa0.tulkki.xmpp.R

/**
 * The three Orbot interactions: is it installed, open it, or send the owner to the market page.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The two `Intent` constants and the two string constants are `@JvmField`/`const val`**, because
 *    the readers are Java as well as Kotlin: `XmppConnectionService.java:668`, `:1411`, `:1413`,
 *    `:2370` read `ACTION_STATUS`/`EXTRA_STATUS`, and `NotificationService.kt:2233`, `:2245` read
 *    `LAUNCH_INTENT`/`INSTALL_INTENT`.
 * 2. **`isOrbotInstalled`, `downloadOrbot` and `startOrbot` are `@JvmStatic`**: `EditAccountActivity.java:253`,
 *    `:254`, `:256`, `:732` are Java callers, `NotificationService.kt:2226` a Kotlin one.
 * 3. **The four private constants keep their names and their initialisation order**, so the two
 *    `Intent`s are built from the same `Uri` and action string as Java's.
 * 4. **The two catches stay `ActivityNotFoundException`**, which is what makes the market-less and
 *    Orbot-less cases fall back to their toasts rather than crash.
 */
class TorServiceUtils {

    companion object {

        private const val URI_ORBOT: String = "org.torproject.android"
        private val ORBOT_PLAYSTORE_URI: Uri = Uri.parse("market://details?id=$URI_ORBOT")
        private const val ACTION_START_TOR: String = "org.torproject.android.START_TOR"

        @JvmField
        val INSTALL_INTENT: Intent = Intent(Intent.ACTION_VIEW, ORBOT_PLAYSTORE_URI)

        @JvmField val LAUNCH_INTENT: Intent = Intent(ACTION_START_TOR)

        const val ACTION_STATUS: String = "org.torproject.android.intent.action.STATUS"
        const val EXTRA_STATUS: String = "org.torproject.android.intent.extra.STATUS"

        @JvmStatic
        fun isOrbotInstalled(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(URI_ORBOT, PackageManager.GET_ACTIVITIES)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

        @JvmStatic
        fun downloadOrbot(activity: Activity, requestCode: Int) {
            try {
                activity.startActivityForResult(INSTALL_INTENT, requestCode)
            } catch (e: ActivityNotFoundException) {
                ToastCompat.makeText(
                        activity,
                        R.string.no_market_app_installed,
                        ToastCompat.LENGTH_SHORT,
                    )
                    .show()
            }
        }

        @JvmStatic
        fun startOrbot(activity: Activity, requestCode: Int) {
            try {
                activity.startActivityForResult(LAUNCH_INTENT, requestCode)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.install_orbot, Toast.LENGTH_LONG).show()
            }
        }
    }
}
