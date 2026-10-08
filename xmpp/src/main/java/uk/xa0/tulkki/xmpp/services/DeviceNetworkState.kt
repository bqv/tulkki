package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import uk.xa0.tulkki.xmpp.Config

/**
 * Tulkki: the device's network state, lifted out of `XmppConnectionService`
 *.
 *
 * The one read is of the platform, so it takes the service as a `Context` and holds no state: the
 * service keeps no field here, and the answer is the one the Java body gave. It runs on whatever
 * thread calls it (the connection thread, `onStartCommand`'s main thread and the UI thread all
 * do), so it must stay stateless.
 */
object DeviceNetworkState {

    /**
     * Whether the device currently looks online. A missing `ConnectivityManager` and a failed
     * check both answer **true** — the fail-open "try anyway" convention the callers rely on — and
     * a `RuntimeException` from the platform is caught rather than propagated.
     */
    @JvmStatic
    fun hasInternetConnection(context: Context): Boolean {
        val connectivityManager =
            ContextCompat.getSystemService(context, ConnectivityManager::class.java)
                ?: return true // if internet connection can not be checked it is probably best to just
        // try
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities =
                    if (activeNetwork == null) null
                    else connectivityManager.getNetworkCapabilities(activeNetwork)
                capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                val legacyOnline = networkInfo != null
                        && (networkInfo.isConnected
                        || networkInfo.type == ConnectivityManager.TYPE_ETHERNET)
                legacyOnline
            }
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to check for internet connection", e)
            true // if internet connection can not be checked it is probably best to just
            // try
        }
    }
}
