package uk.xa0.tulkki.xmpp.utils

import android.content.Context
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * An [SSLSocketFactory] that turns on the app's TLS policy (and, on conscrypt, the SNI/ALPN setup the
 * caller does separately) for every socket it makes.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The constructor keeps `@Throws(KeyManagementException, NoSuchAlgorithmException)`.** Java
 *    declared both and `HttpConnectionManager.java:192/:262` catch them around the `new`; without
 *    the annotation javac would reject those catch clauses as never-thrown.
 * 2. **The constructor body is an `init` block**, so `context.getApplicationContext()` is stored
 *    before the context is initialised, and the `SSLSockets.getSSLContext()` call keeps the same
 *    order and the same `Random.SECURE_RANDOM`. It avoids the Kotlin shadowing trap of a property
 *    initialiser reading a parameter of the same name.
 * 3. **The five `createSocket` overrides keep `@Throws(IOException::class)`**, which is the Java
 *    `throws IOException` carried through Kotlin, whose methods otherwise declare nothing.
 * 4. **`enableTLSOnSocket` stays a private companion function** (Java's `private static`), and its
 *    `instanceof`-and-use is a Kotlin type test, which is the same test with the same smart cast.
 */
class TLSSocketFactory
@Throws(KeyManagementException::class, NoSuchAlgorithmException::class)
constructor(
    trustManager: Array<X509TrustManager>,
    context: Context,
) : SSLSocketFactory() {

    private val context: Context
    private val internalSSLSocketFactory: SSLSocketFactory

    init {
        this.context = context.applicationContext
        val sslContext = SSLSockets.getSSLContext()
        sslContext.init(null, trustManager, Random.SECURE_RANDOM)
        this.internalSSLSocketFactory = sslContext.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> =
        internalSSLSocketFactory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> =
        internalSSLSocketFactory.supportedCipherSuites

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        enableTLSOnSocket(
            internalSSLSocketFactory.createSocket(s, host, port, autoClose),
            context,
        )

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket =
        enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port), context)

    @Throws(IOException::class)
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket =
        enableTLSOnSocket(
            internalSSLSocketFactory.createSocket(host, port, localHost, localPort),
            context,
        )

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket =
        enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port), context)

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        enableTLSOnSocket(
            internalSSLSocketFactory.createSocket(address, port, localAddress, localPort),
            context,
        )

    companion object {

        private fun enableTLSOnSocket(socket: Socket, context: Context): Socket {
            if (socket is SSLSocket) {
                SSLSockets.setSecurity(
                    socket,
                    XmppConnectionService.dataStatics().settings(context).isRequireTlsV13(),
                )
            }
            return socket
        }
    }
}
