package uk.xa0.tulkki.xmpp.utils

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.RouteInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayList
import java.util.stream.Collectors
import org.minidns.dnsserverlookup.AbstractDnsServerLookupMechanism
import org.minidns.dnsserverlookup.AndroidUsingExec

/**
 * The minidns DNS-server lookup over `LinkProperties`: the active network's servers first, VPN
 * servers folded in, everything else appended.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The constructor is `internal`, not package-private.** Kotlin has no package visibility; the
 *    closest is `internal`, and the one caller (`Resolver.java:249`, later `Resolver.kt`) is in this
 *    module. This is the one place in these files where the visibility is wider than Java's, and it
 *    is recorded rather than hidden.
 * 2. **The `networks == null` guard becomes `connectivityManager == null || networks == null`.**
 *    Java's `connectivityManager == null ? null : getAllNetworks()` makes both null at the same time,
 *    and Kotlin needs the explicit test to smart-cast the manager for the loop below; the returned
 *    empty list is the same branch.
 * 3. **`getActiveNetwork` answers `Network?`** because Java's returned the `SDK_INT >= M` result or
 *    `null`, and `isActiveNetwork` compares against that null.
 * 4. **`getIPv4First` and `hasDefaultRoute` are private companion functions** (Java's `private
 *    static`), and the two `@TargetApi` annotations are kept as they were.
 * 5. **The stream pipeline is unchanged**: `.stream().distinct().collect(Collectors.toList())` on the
 *    chosen list, since the order the servers were added in is what the resolver tries.
 */
internal class AndroidUsingLinkProperties
internal constructor(private val context: Context) :
    AbstractDnsServerLookupMechanism(
        AndroidUsingLinkProperties::class.java.simpleName,
        AndroidUsingExec.PRIORITY - 1,
    ) {

    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    @TargetApi(21)
    override fun getDnsServerAddresses(): List<String> {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val networks = connectivityManager?.allNetworks
        if (connectivityManager == null || networks == null) {
            return ArrayList()
        }
        val activeNetwork = getActiveNetwork(connectivityManager)
        val networkServers = ArrayList<String>()
        val otherServers = ArrayList<String>()
        for (network in networks) {
            val linkProperties = connectivityManager.getLinkProperties(network)
            if (linkProperties == null) {
                continue
            }
            val networkInfo = connectivityManager.getNetworkInfo(network)
            val isActiveNetwork = network == activeNetwork
            val isVpn = networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_VPN
            val servers = getIPv4First(linkProperties.dnsServers)
            if (hasDefaultRoute(linkProperties) || isActiveNetwork || activeNetwork == null || isVpn) {
                if (isActiveNetwork) networkServers.addAll(0, servers)
                if (isVpn) networkServers.addAll(servers)
                otherServers.addAll(servers)
            }
        }
        return (if (networkServers.isEmpty()) otherServers else networkServers)
            .stream()
            .distinct()
            .collect(Collectors.toList())
    }

    companion object {

        @TargetApi(23)
        private fun getActiveNetwork(cm: ConnectivityManager): Network? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else null

        private fun getIPv4First(input: List<InetAddress>): List<String> {
            val out = ArrayList<String>()
            for (address in input) {
                if (address is Inet4Address) {
                    out.add(0, address.hostAddress)
                } else {
                    out.add(address.hostAddress)
                }
            }
            return out
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        private fun hasDefaultRoute(linkProperties: LinkProperties): Boolean {
            for (route: RouteInfo in linkProperties.routes) {
                if (route.isDefaultRoute) {
                    return true
                }
            }
            return false
        }
    }
}
