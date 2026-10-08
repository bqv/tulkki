package uk.xa0.tulkki.xmpp.utils

import com.google.common.io.ByteStreams
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.Charset
import uk.xa0.tulkki.xmpp.Config

/**
 * The SOCKS-5 handshake the Tor and I2P sockets are opened through.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`destination.getBytes()` is `toByteArray(Charset.defaultCharset())`**, which is the Java call;
 *    Kotlin's argument-less `toByteArray()` would have pinned UTF-8 instead of the platform default.
 *    `new String(bytes)` is likewise `String(bytes)`, the same default-charset decode.
 * 2. **The byte comparisons name their constant's type** (`0x05.toByte()`), because Kotlin will not
 *    compare a `Byte` to an `Int`; the Java `ver != 0x05` was that comparison after promotion.
 * 3. **Every `String.format` is the `"%s".format(…)` form**, which is the same default-locale
 *    `String.format` call.
 * 4. **The nested exception types stay nested and public where Java had them public**
 *    (`SocksSocketFactory.SocksProxyNotFoundException`, `HostNotFoundException`), because
 *    `XmppConnection.java:582`/`:584` catch them by name. `HostNotFoundException` extends
 *    `IOException` directly: Java routed it through the *private* `SocksConnectionException`, which
 *    Kotlin will not let a public class expose, and that intermediate was never nameable outside the
 *    file.
 * 5. **All `@Throws(IOException::class)` declarations are kept** where Java had `throws IOException`,
 *    because callers (`XmppConnection`) catch it.
 */
class SocksSocketFactory {

    companion object {

        private val LOCALHOST: ByteArray = byteArrayOf(127, 0, 0, 1)

        @JvmStatic
        @Throws(IOException::class)
        fun createSocksConnection(socket: Socket, destination: String, port: Int) {
            // TODO use different Socks Addr Type if destination is IP or IPv6
            val proxyIs = socket.getInputStream()
            val proxyOs = socket.getOutputStream()
            proxyOs.write(byteArrayOf(0x05, 0x01, 0x00))
            proxyOs.flush()
            val handshake = ByteArray(2)
            ByteStreams.readFully(proxyIs, handshake)
            if (handshake[0] != 0x05.toByte() || handshake[1] != 0x00.toByte()) {
                throw SocksConnectionException("Socks 5 handshake failed")
            }
            val dest = destination.toByteArray(Charset.defaultCharset())
            val request = ByteBuffer.allocate(7 + dest.size)
            request.put(byteArrayOf(0x05, 0x01, 0x00, 0x03))
            request.put(dest.size.toByte())
            request.put(dest)
            request.putShort(port.toShort())
            proxyOs.write(request.array())
            proxyOs.flush()
            val response = ByteArray(4)
            ByteStreams.readFully(proxyIs, response)
            val ver = response[0]
            if (ver != 0x05.toByte()) {
                throw IOException("Unknown Socks version %02X ".format(ver))
            }
            val status = response[1]
            val bndAddrType = response[3]
            val bndDestination = readDestination(bndAddrType, proxyIs)
            val bndPort = ByteArray(2)
            if (bndAddrType == 0x03.toByte()) {
                val receivedDestination = String(bndDestination)
                if (!receivedDestination.equals(destination, ignoreCase = true)) {
                    throw IOException(
                        "Destination mismatch. Received %s Expected %s"
                            .format(receivedDestination, destination),
                    )
                }
            }
            ByteStreams.readFully(proxyIs, bndPort)
            if (status != 0x00.toByte()) {
                if (status == 0x04.toByte()) {
                    throw HostNotFoundException("Host unreachable")
                }
                if (status == 0x05.toByte()) {
                    throw HostNotFoundException("Connection refused")
                }
                throw IOException("Unknown status code %02X ".format(status))
            }
        }

        @Throws(IOException::class)
        private fun readDestination(type: Byte, inputStream: InputStream): ByteArray {
            val bndDestination: ByteArray
            if (type == 0x01.toByte()) {
                bndDestination = ByteArray(4)
            } else if (type == 0x03.toByte()) {
                val length = inputStream.read()
                bndDestination = ByteArray(length)
            } else if (type == 0x04.toByte()) {
                bndDestination = ByteArray(16)
            } else {
                throw IOException("Unknown Socks address type %02X ".format(type))
            }
            ByteStreams.readFully(inputStream, bndDestination)
            return bndDestination
        }

        @JvmStatic
        fun contains(needle: Byte, haystack: ByteArray): Boolean {
            for (hay in haystack) {
                if (hay == needle) {
                    return true
                }
            }
            return false
        }

        @Throws(IOException::class)
        private fun createSocket(
            address: InetSocketAddress,
            destination: String,
            port: Int,
        ): Socket {
            val socket = Socket()
            try {
                socket.connect(address, Config.CONNECT_TIMEOUT * 1000)
            } catch (e: IOException) {
                throw SocksProxyNotFoundException()
            }
            createSocksConnection(socket, destination, port)
            return socket
        }

        @JvmStatic
        @Throws(IOException::class)
        fun createSocketOverTor(destination: String, port: Int): Socket =
            createSocket(
                InetSocketAddress(InetAddress.getByAddress(LOCALHOST), 9050),
                destination,
                port,
            )

        @JvmStatic
        @Throws(IOException::class)
        fun createSocketOverI2P(destination: String, port: Int): Socket =
            createSocket(
                InetSocketAddress(InetAddress.getByAddress(LOCALHOST), 4447),
                destination,
                port,
            )
    }

    private class SocksConnectionException(message: String) : IOException(message)

    class SocksProxyNotFoundException : IOException()

    /**
     * Still an `IOException`, as Java's was - but through [IOException] directly rather than through
     * the private `SocksConnectionException`. Kotlin forbids a public class exposing a private
     * supertype, and the private intermediate was never nameable outside this file, so no caller can
     * tell the difference; `XmppConnection.java:582` catches this type and `:584` its sibling by name.
     */
    class HostNotFoundException(message: String) : IOException(message)
}
