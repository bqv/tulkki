package uk.xa0.tulkki.xmpp.services

import android.os.PowerManager
import androidx.core.content.ContextCompat
import okhttp3.RequestBody
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.libs.Transferable
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.regex.Pattern

/**
 * The base class the HTTP and Jingle connection managers extend. It holds the service, a wake-lock
 * factory and the Java-facing shell; every other responsibility it used to carry has moved to its
 * own class in this package.
 *
 * <p><strong>Why a shell at all.</strong> The callers are Java in other packages - `HttpUpload`,
 * `HttpDownload`, `JingleFileTransferConnection`, `MessageParser`, `CryptoStore`, `BobTransfer` -
 * and the file protocol gives this lane the `services` package only, so the surface they compile
 * against is frozen. Every static below is a `@JvmStatic` forwarder whose body lives elsewhere:
 * [TransferCipher] (the AES-GCM streams), [TransferRequestBody] (the upload body),
 * [FileTransferPolicy] (the auto-accept size and storage permission) and [ConversationUiRefresh]
 * (the 250 ms UI throttle, whose `static` sharing it preserves). Dissolving the shell is the next
 * step, once the callers move with it.
 *
 * <p>Two members are held exactly as Java exposed them and could not be a Kotlin property:
 * `mXmppConnectionService` is a `@JvmField` because `HttpConnectionManager` and
 * `JingleConnectionManager` read the field directly, and the nested [Extension] keeps its two
 * `@JvmField`s for the same reason. [Extension] is nested rather than extracted because Java names
 * it `AbstractConnectionManager.Extension`.
 */
open class AbstractConnectionManager(service: XmppConnectionService) {

    @JvmField
    protected var mXmppConnectionService: XmppConnectionService = service

    private val fileTransferPolicy = FileTransferPolicy(service)

    fun getXmppConnectionService(): XmppConnectionService = mXmppConnectionService

    fun getAutoAcceptFileSize(): Long = fileTransferPolicy.autoAcceptFileSize()

    fun hasStoragePermission(): Boolean = fileTransferPolicy.hasStoragePermission()

    fun updateConversationUi(force: Boolean) =
            ConversationUiRefresh.notifyChange(mXmppConnectionService, force)

    fun createWakeLock(name: String): PowerManager.WakeLock {
        val powerManager = ContextCompat.getSystemService(
                mXmppConnectionService,
                PowerManager::class.java
        ) ?: throw NullPointerException("PowerManager is not available")
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name)
    }

    /**
     * A path's last two dot-separated segments, with the crypto extension taking precedence.
     *
     * <p>`main` and `secondary` are both nullable - a name with no dot has no `main` -
     * and the Java callers pass `main` straight to `VALID_CRYPTO_EXTENSIONS.contains(...)`, so a
     * non-null field here would have been the "non-null where a caller passes null" defect. The
     * interface's own static list is qualified (`Transferable.VALID_CRYPTO_EXTENSIONS`), because
     * a Kotlin implementor inherits none of its interface's static fields.
     */
    class Extension private constructor(
            @JvmField val main: String?,
            @JvmField val secondary: String?
    ) {

        fun getExtension(): String? =
                if (Transferable.VALID_CRYPTO_EXTENSIONS.contains(main)) secondary else main

        companion object {
            @JvmStatic
            fun of(path: String): Extension {
                val pos = path.lastIndexOf('/')
                // Two Java details the Kotlin defaults do not share: `toLowerCase()` is the
                // *default* locale's, not `lowercase()`'s invariant one, and `String.split` drops
                // trailing empty segments where Kotlin's `split` keeps them - so "name." must still
                // have no `main`.
                val filename = path.substring(pos + 1).lowercase(Locale.getDefault())
                val parts = Pattern.compile("\\.").split(filename)
                val main = if (parts.size >= 2) parts[parts.size - 1] else null
                val secondary = if (parts.size >= 3) parts[parts.size - 2] else null
                return Extension(main, secondary)
            }
        }
    }

    /** The upload callback, kept nested because `HttpUploadConnection` implements it by this name. */
    interface ProgressListener {
        fun onProgress(progress: Long)
    }

    companion object {

        @JvmStatic
        fun upgrade(file: DownloadableFileRef, input: InputStream): InputStream =
                TransferCipher.upgrade(file, input)

        @JvmStatic
        fun requestBody(
                file: DownloadableFileRef,
                progressListener: ProgressListener
        ): RequestBody = TransferRequestBody.create(file, progressListener)

        @JvmStatic
        fun createOutputStream(
                file: DownloadableFileRef,
                append: Boolean,
                decrypt: Boolean
        ): OutputStream? = TransferCipher.createOutputStream(file, append, decrypt)
    }
}
