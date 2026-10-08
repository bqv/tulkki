package uk.xa0.tulkki.app.http

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.util.Consumer
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.apache.http.conn.ssl.StrictHostnameVerifier
import uk.xa0.tulkki.xmpp.BuildConfig
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.TLSSocketFactory

/**
 * Tulkki: the HTTP transfer connection manager - the pool, the shared client and the two lists of
 * live transfers.
 *
 * Ported from `HttpConnectionManager.java`. It is *ours*, so it is converted in place.
 *
 * The Java-visible surface, read off the callers, not off the types:
 *
 *  * every static stays reachable **as a static** - `CustomGlideModule.kt` and
 *    `ChannelDiscoveryService.kt` call `okHttpClient`/`getProxy`, `XmppConnection.java` calls both
 *    `open` overloads, `XmppConnectionService.java` calls `extractFilenameFromResponse` and
 *    `ProviderService.kt` calls `open` - so all seven live in the companion behind `@JvmStatic`.
 *  * `EXECUTOR` is read as a **field** through a static import
 *    (`HttpDownloadConnection.java:35`, `EXECUTOR.execute(...)`), so it stays a `@JvmField`
 *    (`public static final Executor`);
 *  * `open` declares `throws IOException` and its Java callers' `try` blocks catch it, so both
 *    overloads keep the checked clause through `@Throws`;
 *  * `finishConnection`/`finishUploadConnection` were package-private and Java in the same package
 *    calls them; Kotlin has no package-private, and `internal` would mangle the JVM name off that
 *    Java surface, so they become public - a widening no caller can observe.
 *
 * Two Java-isms the Kotlin does not share, spelled out rather than left to a warning: okhttp 4's
 * Kotlin `readTimeout`/`writeTimeout` take a `Long` where the Java widened the `int` literal, so the
 * literals are `30L` and the `int` parameter is `toLong()`-ed - the `Method.kt` `0L` precedent; the
 * log lines
 * concatenate a `Jid` with a `String`, which Java coerces through `String.valueOf` and Kotlin
 * cannot (`asBareJid().toString() + …`), and `extractFilenameFromResponse` answers **null** when
 * neither the header nor the path carries a name, which is the platform type its one Java caller
 * already expects.
 */
class HttpConnectionManager(service: XmppConnectionService) : AbstractConnectionManager(service) {

    private val downloadConnections = ArrayList<HttpDownloadConnection>()
    private val uploadConnections = ArrayList<HttpUploadConnection>()

    companion object {

        @JvmField
        val EXECUTOR: Executor = Executors.newFixedThreadPool(4)

        private val OK_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val modified = original.newBuilder()
                    .header("User-Agent", getUserAgent())
                    .build()
                chain.proceed(modified)
            }
            .build()

        @JvmStatic
        fun getUserAgent(): String =
            String.format("%s/%s", BuildConfig.APP_NAME, BuildConfig.VERSION_NAME)

        @JvmStatic
        fun getProxy(isI2P: Boolean): Proxy {
            val localhost: InetAddress = try {
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            } catch (e: UnknownHostException) {
                throw IllegalStateException(e)
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(localhost, if (isI2P) 4447 else 9050))
            } else {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(localhost, if (isI2P) 4444 else 8118))
            }
        }

        @JvmStatic
        fun newBuilder(tor: Boolean, i2p: Boolean): OkHttpClient.Builder {
            val builder = OK_HTTP_CLIENT.newBuilder()
            builder.writeTimeout(30L, TimeUnit.SECONDS)
            builder.readTimeout(30L, TimeUnit.SECONDS)
            if (tor || i2p) {
                builder.proxy(getProxy(i2p)).build()
            }
            return builder
        }

        @JvmStatic
        @Throws(IOException::class)
        fun open(url: String, tor: Boolean, i2p: Boolean): InputStream =
            open(url.toHttpUrl(), tor, i2p)

        @JvmStatic
        @Throws(IOException::class)
        fun open(httpUrl: HttpUrl, tor: Boolean, i2p: Boolean): InputStream {
            val client = newBuilder(tor, i2p).build()
            val request = Request.Builder().get().url(httpUrl).build()
            val body: ResponseBody? = client.newCall(request).execute().body
            if (body == null) {
                throw IOException("No response body found")
            }
            return body.byteStream()
        }

        @JvmStatic
        fun extractFilenameFromResponse(response: Response): String? {
            var filename: String? = null

            // Try to extract filename from the Content-Disposition header
            val contentDisposition = response.header("Content-Disposition")
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                val parts = contentDisposition.split(";")
                for (part in parts) {
                    if (part.trim().startsWith("filename=")) {
                        filename = part.substring("filename=".length).trim().replace("\"", "")
                        break
                    }
                }
            }

            // If filename is not found in the Content-Disposition header, try to get it from the URL
            if (filename == null || filename.isEmpty()) {
                val httpUrl = response.request.url
                val pathSegments = httpUrl.pathSegments
                if (pathSegments.isNotEmpty()) {
                    filename = pathSegments[pathSegments.size - 1]
                }
            }

            return filename
        }

        @JvmStatic
        fun okHttpClient(context: Context): OkHttpClient {
            val builder = OK_HTTP_CLIENT.newBuilder()
            try {
                val trustManager: X509TrustManager
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                    trustManager =
                        XmppConnectionService.trustPort()
                            .defaultWithBundledLetsEncrypt(context)
                } else {
                    trustManager = XmppConnectionService.trustPort().defaultTrustManager()
                }
                val socketFactory: SSLSocketFactory =
                    TLSSocketFactory(arrayOf(trustManager), context)
                builder.sslSocketFactory(socketFactory, trustManager)
            } catch (e: IOException) {
                notConfiguredForBundledLetsEncrypt(e)
            } catch (e: GeneralSecurityException) {
                notConfiguredForBundledLetsEncrypt(e)
            }
            return builder.build()
        }

        private fun notConfiguredForBundledLetsEncrypt(e: Exception): Nothing {
            Log.d(Config.LOGTAG, "not reconfiguring service to work with bundled LetsEncrypt")
            throw RuntimeException(e)
        }
    }

    // Tulkki: 3.7 pair 9, part 16. Parts 12 and 16 declared ref-typed overloads of the three
    // download and two upload entry points *beside* the model ones; with this file's model name gone
    // the pairs collapse into the ref form. Every caller - `MessageParser` and
    // `XmppConnectionService` (island), `Conversation`, `AttachFileToConversationRunnable`,
    // `PostsAdapter` and `ConversationFragment` (model objects) - still compiles, because a
    // `Message` is a `MessageRef`.

    fun createNewDownloadConnection(message: MessageRef) {
        createNewDownloadConnection(message, false)
    }

    fun createNewDownloadConnection(message: MessageRef, interactive: Boolean) {
        createNewDownloadConnection(message, interactive, null)
    }

    // Tulkki: 3.7 C5-E3 - the callback is the ref, and it carries no wildcard on purpose. All four
    // call sites are implicitly typed lambdas written at the call site (`Conversation:4235`,
    // `PostsAdapter:617`, `AttachFileToConversationRunnable:73`, `XmppConnectionService:2859`), so
    // nothing is passed that the type argument's invariance could reject; the lambda parameter
    // infers under either spelling, so `? super` bought only commit separability and is not written
    // where it is not forced. The ref is spelled **nullable** because the download's own
    // `FileSizeChecker` reports "offer only" through `cb.accept(null)`, exactly as the Java did.
    fun createNewDownloadConnection(
        message: MessageRef,
        interactive: Boolean,
        cb: Consumer<DownloadableFileRef?>?,
    ) {
        synchronized(downloadConnections) {
            for (connection in downloadConnections) {
                if (connection.getMessage() === message) {
                    Log.d(
                        Config.LOGTAG,
                        ((message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
                            ?: throw NullPointerException("conversation has no account")).getJid().asBareJid().toString()
                            + ": download already in progress",
                    )
                    return
                }
            }
            val connection = HttpDownloadConnection(message, this, cb)
            connection.init(interactive)
            downloadConnections.add(connection)
        }
    }

    fun createNewUploadConnection(message: MessageRef, delay: Boolean) {
        createNewUploadConnection(message, delay, null)
    }

    fun createNewUploadConnection(message: MessageRef, delay: Boolean, cb: Runnable?) {
        synchronized(uploadConnections) {
            for (connection in uploadConnections) {
                if (connection.getMessage() === message) {
                    Log.d(
                        Config.LOGTAG,
                        ((message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
                            ?: throw NullPointerException("conversation has no account")).getJid().asBareJid().toString()
                            + ": upload already in progress",
                    )
                    return
                }
            }
            val connection = HttpUploadConnection(
                message,
                Method.determine(
                (message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
                    ?: throw NullPointerException("conversation has no account"),
            ),
                this,
                cb,
            )
            connection.initForMessage(delay)
            uploadConnections.add(connection)
        }
    }

    // Tulkki: 3.7 C5-E3 - the parameter is the ref. `XmppConnectionService.uploadFileForUrl` holds
    // the `getTemporaryFile` result as a `DownloadableFileRef`, and `HttpUploadConnection`'s
    // constructor already takes the ref, so this is the last model name on the upload path.
    fun createNewUploadConnection(
        file: DownloadableFileRef,
        account: AccountRef,
        delay: Boolean,
        callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<String>,
    ) {
        synchronized(uploadConnections) {
            val connection = HttpUploadConnection(
                account,
                file,
                Method.determine(account),
                this,
                callback,
            )
            connection.initForFile()
            uploadConnections.add(connection)
        }
    }

    fun finishConnection(connection: HttpDownloadConnection) {
        synchronized(downloadConnections) {
            downloadConnections.remove(connection)
        }
    }

    fun finishUploadConnection(httpUploadConnection: HttpUploadConnection) {
        synchronized(uploadConnections) {
            uploadConnections.remove(httpUploadConnection)
        }
    }

    // Tulkki: 3.7 C5-E3 - this file names no `:data` type at all now, which is what takes its one
    // `island-imports-ours` line away. C5-B had already replaced every account type with `AccountRef`
    // and re-pointed `Method.determine`; C5-E3 retyped the last two members, and the `Consumer` note
    // that used to sit here is above `createNewDownloadConnection`.

    fun buildHttpClient(url: HttpUrl, account: AccountRef, interactive: Boolean): OkHttpClient =
        buildHttpClient(url, account, 30, interactive)

    fun buildHttpClient(
        url: HttpUrl,
        account: AccountRef,
        readTimeout: Int,
        interactive: Boolean,
    ): OkHttpClient {
        val slotHostname = url.host
        val onionSlot = slotHostname.endsWith(".onion")
        val i2PSlot = slotHostname.endsWith(".i2p")
        val builder = newBuilder(
            mXmppConnectionService.useTorToConnect() || account.isOnion() || onionSlot,
            mXmppConnectionService.useI2PToConnect() || account.isI2P() || i2PSlot,
        )
        builder.readTimeout(readTimeout.toLong(), TimeUnit.SECONDS)
        setupTrustManager(builder, interactive)
        return builder.build()
    }

    private fun setupTrustManager(builder: OkHttpClient.Builder, interactive: Boolean) {
        val trustManager: X509TrustManager = if (interactive) {
            mXmppConnectionService.getMemorizingTrustManager().getInteractive()
        } else {
            mXmppConnectionService.getMemorizingTrustManager().getNonInteractive()
        }
        try {
            val sf: SSLSocketFactory =
                TLSSocketFactory(arrayOf(trustManager), mXmppConnectionService)
            builder.sslSocketFactory(sf, trustManager)
            builder.hostnameVerifier(StrictHostnameVerifier())
        } catch (ignored: KeyManagementException) {
        } catch (ignored: NoSuchAlgorithmException) {
        }
    }
}
