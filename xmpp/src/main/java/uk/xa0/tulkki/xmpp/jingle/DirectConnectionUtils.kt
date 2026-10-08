package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ImmutableList
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Enumeration

/**
 * The device's non-loopback, non-link-local addresses, for a direct SOCKS5 connection.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **It is an `object`**: Java's only members were the private constructor and the static
 *    `getLocalAddresses`, and no caller constructed it.
 * 2. **`getLocalAddresses` answers `MutableList`**, the module's spelling for a Java `List` return;
 *    `SocksByteStreamsTransport:563` still gets a `java.util.List` and calls
 *    `.toArray(new InetAddress[0])`.
 * 3. Java's getter calls become Kotlin's synthesised properties (`isLoopbackAddress`,
 *    `isLinkLocalAddress`, `address`, `inetAddresses`), and `Inet6Address.getByAddress` keeps its
 *    checked `UnknownHostException` in the same try/catch.
 */
object DirectConnectionUtils {

    @JvmStatic
    fun getLocalAddresses(): MutableList<InetAddress> {
        val inetAddresses = ImmutableList.builder<InetAddress>()
        val interfaces: Enumeration<NetworkInterface>
        try {
            interfaces = NetworkInterface.getNetworkInterfaces()
        } catch (e: SocketException) {
            return inetAddresses.build()
        }
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val inetAddressEnumeration = networkInterface.inetAddresses
            while (inetAddressEnumeration.hasMoreElements()) {
                val inetAddress = inetAddressEnumeration.nextElement()
                if (inetAddress.isLoopbackAddress || inetAddress.isLinkLocalAddress) {
                    continue
                }
                if (inetAddress is Inet6Address) {
                    // let's get rid of scope
                    try {
                        inetAddresses.add(Inet6Address.getByAddress(inetAddress.address))
                    } catch (e: UnknownHostException) {
                        // ignored
                    }
                } else {
                    inetAddresses.add(inetAddress)
                }
            }
        }
        return inetAddresses.build()
    }
}
