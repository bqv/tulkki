package uk.xa0.tulkki.xmpp.services

import android.net.ConnectivityManager
import uk.xa0.tulkki.xmpp.R

/**
 * The two questions a transfer asks before it fetches a file by itself: how large a file may be
 * accepted without asking, and whether the app may write to storage at all.
 *
 * <p>Split out of `AbstractConnectionManager`, which keeps the two methods on its own surface as
 * forwarders because Java callers (`HttpDownloadConnection`, `JingleFileTransferConnection`,
 * `MessageParser`, `CryptoStore`) call them there. It holds the service because both answers are
 * the service's own settings and device state - nothing here is cached, so the answers stay as
 * live as the Java's were.
 */
internal class FileTransferPolicy(private val service: XmppConnectionService) {

    fun autoAcceptFileSize(): Long {
        // Tulkki: `Compatibility.isActiveNetworkMetered` is `@NonNull ConnectivityManager` in Java and
        // dereferences it (`connectivityManager.isActiveNetworkMetered()`), so the port member stays
        // non-null and the caller names the `NullPointerException` the Java's own dereference was -
        // never `!!`, and never a widened declaration. The value is the platform's, so Kotlin only sees
        // it as nullable because this local says so.
        val connectivityManager: ConnectivityManager =
                service.getSystemService(ConnectivityManager::class.java)
                    ?: throw NullPointerException("ConnectivityManager is not available")
        val autoAcceptUnmetered =
                service.getBooleanPreference("auto_accept_unmetered", R.bool.auto_accept_unmetered)
        if (autoAcceptUnmetered &&
                !service.compatibility().isActiveNetworkMetered(connectivityManager)) {
            return 20000000 // 20 MB
        }
        val autoAcceptFileSize =
                service.getLongPreference("auto_accept_file_size", R.integer.auto_accept_filesize)
        return if (autoAcceptFileSize <= 0) -1 else autoAcceptFileSize
    }

    fun hasStoragePermission(): Boolean =
            service.compatibility().hasStoragePermission(service)
}
