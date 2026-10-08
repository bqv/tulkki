package uk.xa0.tulkki.xmpp.utils

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.common.base.Strings
import java.lang.reflect.Method
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.NoSuchAlgorithmException
import java.util.Collections
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.conscrypt.Conscrypt
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * The conscrypt-or-reflection layer over an [SSLSocket]: protocols, SNI, ALPN, the TLS version the
 * channel binding needs, and one log line.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **Every public method is `@JvmStatic`**, because the callers are Java: `XmppConnection.java`
 *    `:661` (`getSSLContext`), `:1563-1565` (`setSecurity`/`setHostname`/`setApplicationProtocol`)
 *    and `:634-637`, `:1510` (`log`, `version`), plus `ChannelBinding` and the SASL mechanisms
 *    reading `SSLSockets.Version`.
 * 2. **`getSSLContext` carries `@Throws(NoSuchAlgorithmException::class)`.** Java declared it and
 *    `XmppConnection.getSSLSocketFactory` propagates it through its own `throws` clause; without the
 *    annotation javac would reject that caller's catch-or-declare.
 * 3. **`Version.of` moves into this file's companion as a private `of`.** Java's was a `private
 *    static` method on the enum; Kotlin enums cannot hold static members without a companion, and
 *    since the method is private and called only from [version], the companion is its home without
 *    changing any visible surface. The `Strings.nullToEmpty` switch is the Java switch.
 * 4. **`Collections.singletonList<SNIServerName>(…)` names its type argument**, because Java inferred
 *    `List<SNIServerName>` from the assignment and Kotlin has no target typing for the generic;
 *    without it the list would be `List<SNIHostName>` and would not fit `setServerNames`.
 * 5. **The reflection call passes the `ByteArray` as one argument** (`method.invoke(socket, bytes)`),
 *    which is what Java's explicit `new Object[] {lengthPrefixedProtocols}` was doing.
 */
class SSLSockets {

    companion object {

        @JvmStatic
        fun setSecurity(sslSocket: SSLSocket, requireTlsV13: Boolean) {
            if (requireTlsV13) {
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")
            } else {
                sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            }
        }

        @JvmStatic
        fun setHostname(socket: SSLSocket, hostname: String) {
            if (Conscrypt.isConscrypt(socket)) {
                Conscrypt.setHostname(socket, hostname)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setHostnameNougat(socket, hostname)
            } else {
                setHostnameReflection(socket, hostname)
            }
        }

        private fun setHostnameReflection(socket: SSLSocket, hostname: String) {
            try {
                socket.javaClass.getMethod("setHostname", String::class.java).invoke(socket, hostname)
            } catch (e: Throwable) {
                Log.e(Config.LOGTAG, "unable to set SNI name on socket ($hostname)", e)
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.N)
        private fun setHostnameNougat(socket: SSLSocket, hostname: String) {
            val parameters = SSLParameters()
            parameters.serverNames = Collections.singletonList<SNIServerName>(SNIHostName(hostname))
            socket.sslParameters = parameters
        }

        private fun setApplicationProtocolReflection(socket: SSLSocket, protocol: String) {
            try {
                val method = socket.javaClass.getMethod("setAlpnProtocols", ByteArray::class.java)
                // the concatenation of 8-bit, length prefixed protocol names, just one in our case...
                // http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
                val protocolUTF8Bytes = protocol.toByteArray(StandardCharsets.UTF_8)
                val lengthPrefixedProtocols = ByteArray(protocolUTF8Bytes.size + 1)
                lengthPrefixedProtocols[0] = protocol.length.toByte() // cannot be over 255 anyhow
                System.arraycopy(
                    protocolUTF8Bytes,
                    0,
                    lengthPrefixedProtocols,
                    1,
                    protocolUTF8Bytes.size,
                )
                method.invoke(socket, lengthPrefixedProtocols)
            } catch (e: Throwable) {
                Log.e(Config.LOGTAG, "unable to set ALPN on socket", e)
            }
        }

        @JvmStatic
        fun setApplicationProtocol(socket: SSLSocket, protocol: String) {
            if (Conscrypt.isConscrypt(socket)) {
                Conscrypt.setApplicationProtocols(socket, arrayOf(protocol))
            } else {
                setApplicationProtocolReflection(socket, protocol)
            }
        }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        fun getSSLContext(): SSLContext {
            return try {
                SSLContext.getInstance("TLSv1.3")
            } catch (e: NoSuchAlgorithmException) {
                SSLContext.getInstance("TLSv1.2")
            }
        }

        @JvmStatic
        fun log(account: AccountRef, socket: SSLSocket) {
            val session: SSLSession = socket.session
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": protocol=" +
                    session.protocol +
                    " cipher=" +
                    session.cipherSuite,
            )
        }

        @JvmStatic
        fun version(socket: Socket): Version {
            if (socket is SSLSocket) {
                return if (Conscrypt.isConscrypt(socket)) {
                    of(socket.session.protocol)
                } else {
                    Version.TLS_UNSUPPORTED_VERSION
                }
            } else {
                return Version.NONE
            }
        }

        private fun of(protocol: String): Version =
            when (Strings.nullToEmpty(protocol)) {
                "TLSv1" -> Version.TLS_1_0
                "TLSv1.1" -> Version.TLS_1_1
                "TLSv1.2" -> Version.TLS_1_2
                "TLSv1.3" -> Version.TLS_1_3
                else -> Version.TLS_UNSUPPORTED_VERSION
            }
    }

    enum class Version {
        TLS_1_0,
        TLS_1_1,
        TLS_1_2,
        TLS_1_3,
        TLS_UNSUPPORTED_VERSION,
        NONE,
    }
}
