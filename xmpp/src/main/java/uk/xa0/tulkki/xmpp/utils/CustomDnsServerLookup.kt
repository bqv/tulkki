package uk.xa0.tulkki.xmpp.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.minidns.dnsserverlookup.AbstractDnsServerLookupMechanism
import org.minidns.util.InetAddressUtil

/**
 * The owner's custom DNS servers, read from the settings screen's `dns_server_ipv4` and
 * `dns_server_ipv6` preferences.
 *
 * This is the one piece of the fork's `DnsClient` delta that could not be left in a library: the
 * fork's `getServerAddresses()` returned the two preferences **exclusively** when they were set and
 * fell back to the system's servers otherwise, and the release's `DnsClient` reads no preference at
 * all. Expressing it as a lookup mechanism is what the returned list means under the release's
 * `findDNS()`: the mechanisms are tried in ascending priority and the first one that answers
 * **non-empty** wins and ends the scan, so a set preference is exclusive exactly as that branch was,
 * and an unset or invalid one answers `null` and lets the next mechanism (the getprop and
 * `LinkProperties` readers, then the reflection and `/etc/resolv.conf` ones) supply the system's
 * servers.
 *
 * [PRIORITY] is negative on purpose. `AndroidUsingExecLowPriority.INSTANCE` is constructed before
 * its `PRIORITY` companion property has been assigned, so that mechanism registers at priority `0`
 * rather than the `1001` it declares — the port kept that initialisation order as the behaviour it
 * is. `0` therefore sits below everything else, and a preference must sort *under* it to be
 * exclusive. The brief's "priority < 999" is necessary but not sufficient here; `-1` is what the
 * two mechanisms' actual priorities require.
 */
internal class CustomDnsServerLookup(context: Context) :
    AbstractDnsServerLookupMechanism(CustomDnsServerLookup::class.java.simpleName, PRIORITY) {

    private val context = context.applicationContext

    override fun isAvailable(): Boolean = true

    /**
     * The two preferences in the order the fork's `v4v6` branch used: IPv4 first, then IPv6. `null`
     * when neither names a valid address, which is `findDNS()`'s "no answer, try the next
     * mechanism" and not an empty list, whose warning the release logs.
     */
    override fun getDnsServerAddresses(): List<String>? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val servers = ArrayList<String>(2)
        addIfValid(servers, preferences.getString(IPV4_KEY, null), ipv4 = true)
        addIfValid(servers, preferences.getString(IPV6_KEY, null), ipv4 = false)
        return if (servers.isEmpty()) null else servers
    }

    /**
     * The preference is first checked against the IP-literal patterns and only then parsed, because
     * `InetAddressUtil.ipv4From`/`ipv6From` parse through `InetAddress.getByName`, which would
     * resolve a hostname-shaped preference with the system resolver — the lookup `findDNS()`'s
     * contract exists to keep out of MiniDNS. A well-formed address is normalised through them as
     * the deleted `DnsClient` normalised it; a bracketed IPv6 literal, which that parser accepted
     * and the patterns do not, is skipped, falling back to the system's servers like any other
     * invalid value.
     */
    private fun addIfValid(servers: MutableList<String>, value: String?, ipv4: Boolean) {
        if (value.isNullOrEmpty()) {
            return
        }
        val isLiteral =
            if (ipv4) InetAddressUtil.isIpV4Address(value) else InetAddressUtil.isIpV6Address(value)
        if (!isLiteral) {
            return
        }
        try {
            val address =
                if (ipv4) InetAddressUtil.ipv4From(value) else InetAddressUtil.ipv6From(value)
            // `hostAddress` is nullable on Android; the same guard the low-priority getprop
            // mechanism carries, and for the same reason.
            val hostAddress = address.hostAddress
            if (hostAddress != null) {
                servers.add(hostAddress)
            }
        } catch (e: IllegalArgumentException) {
            // An invalid preference is not a reason to stop resolving: the next mechanism runs.
        }
    }

    companion object {

        private const val IPV4_KEY = "dns_server_ipv4"
        private const val IPV6_KEY = "dns_server_ipv6"

        @JvmField val PRIORITY: Int = -1
    }
}
