package uk.xa0.tulkki.xmpp.utils

import android.content.ContentValues
import android.database.Cursor
import android.util.Log
import androidx.annotation.NonNull
import com.google.common.base.Function
import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Lists
import com.google.common.collect.Ordering
import com.google.common.net.InetAddresses
import com.google.common.primitives.Ints
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.Comparator
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.minidns.AbstractDnsClient
import org.minidns.DnsCache
import org.minidns.DnsClient
import org.minidns.cache.LruCache
import org.minidns.dnsname.DnsName
import org.minidns.dnsname.InvalidDnsNameException
import org.minidns.dnssec.DnssecValidationFailedException
import org.minidns.dnsserverlookup.AndroidUsingExec
import org.minidns.hla.DnssecResolverApi
import org.minidns.hla.ResolverApi
import org.minidns.hla.ResolverResult
import org.minidns.iterative.ReliableDnsClient
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.CNAME
import org.minidns.record.Data
import org.minidns.record.InternetAddressRR
import org.minidns.record.SRV
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.libs.IP
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: `_xmpp-client`/`_xmpps-client` SRV resolution, the A/AAAA fallback and the cached
 * `Resolver.Result` the connection stores.
 *
 * Ported from `Resolver.java`. It is *ours*, so it is converted in place. **`org.minidns.*` is a
 * vendored third party and stays Java** - this file only *uses* it.
 *
 * The Java-visible surface, read off the callers:
 *
 *  * every `static` stays reachable as a static, so the object's members carry `@JvmStatic`:
 *    `XmppConnection.java:356,406,441`, `DatabaseBackendRef.java:156`, `Account.kt:238` and the
 *    settings screens call `fromHardCoded`/`useDirectTls`/`resolve`/`XMPP_PORT_STARTTLS`, and
 *    `ServiceConstruction.kt`/`StartCommand.kt`/`EditAccountActivity.kt`/`ManageAccountActivity.kt`
 *    call `init`/`clearCache`/`checkDomain`/`invalidHostname`;
 *  * `XMPP_PORT_STARTTLS` is a `const val`, which is the same `public static final int` on the JVM;
 *  * **`Result` stays a public nested class** - Java names `Resolver.Result` in `XmppConnection.java`
 *    and in `:data`'s `DatabaseBackend`/`DatabaseBackendRef`, and Kotlin names it in
 *    `DiscoveryStore.kt`/`RawTables.kt` - so its members and the seven `DOMAIN`/`IP`/… column-name
 *    constants keep their names and their `public static` shape;
 *  * the Java `List<Result>` returns become `MutableList<Result>` (the island's rule for a Java
 *    collection return); `Collections.singletonList`/`emptyList` and the Guava `Lists.transform`
 *    views are the same objects.
 *
 * Four Java facts are handled rather than inherited:
 *
 *  1. `seeOtherHost`'s trailing `result.port = port;` reads **the receiver's own field** in the else
 *     branch - the `Integer port` local declared inside the `if` shadows it only there - so the
 *     Kotlin `port` in that arm is `this.port` and nothing else;
 *  2. `Result`'s six fields were Java `private`, and the outer class's comparator reads them through
 *     the JVM's synthetic accessors; Kotlin has no such access from the enclosing object, so they are
 *     `internal` - visible inside `:xmpp`, invisible to Java and to every other module;
 *  3. the `Futures.transform`/`transformAsync` lambdas are written as explicit
 *     `Function`/`AsyncFunction` SAM constructors, because the two `Futures.transform` overloads are
 *     otherwise ambiguous for a bare Kotlin lambda;
 *  4. `Futures.successfulAsList` can hand back nulls for failed futures, which is what the Java
 *     `if (list == null) continue` is for; the Kotlin keeps the test.
 */
object Resolver {

    private val RESULT_COMPARATOR: Comparator<Result> =
        Comparator { left, right ->
            if (left.priority == right.priority) {
                if (left.directTls == right.directTls) {
                    val leftIp = left.ip
                    val rightIp = right.ip
                    if (leftIp == null && rightIp == null) {
                        0
                    } else if (leftIp != null && rightIp != null) {
                        val appSettings: AppSettingsRef =
                            XmppConnectionService.dataStatics().settings(
                                SERVICE ?: throw NullPointerException("no service"))
                        if (appSettings.preferIPv6()) {
                            if (leftIp is Inet6Address && rightIp is Inet6Address) {
                                0
                            } else {
                                if (leftIp is Inet6Address) -1 else 1
                            }
                        } else {
                            if (leftIp is Inet4Address && rightIp is Inet4Address) {
                                0
                            } else {
                                if (leftIp is Inet4Address) -1 else 1
                            }
                        }
                    } else {
                        if (leftIp != null) -1 else 1
                    }
                } else {
                    if (left.directTls) 1 else -1
                }
            } else {
                left.priority - right.priority
            }
        }

    // The fork's DnsClient defaulted `askForDnssec` to true; the release's default is false and the
    // accessor is on DnsClient, which neither hla client is. ResolverDnsClient is the subclass that
    // restores the DO bit, so the resolver owns its client instead of using ResolverApi.INSTANCE.
    // See ResolverDnsClient; both clients share AbstractDnsClient.DEFAULT_CACHE.
    private val RESOLVER_API: ResolverApi = ResolverApi(ResolverDnsClient())

    private val DNS_QUERY_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(12)

    const val XMPP_PORT_STARTTLS: Int = 5222

    private const val XMPP_PORT_DIRECT_TLS: Int = 5223

    private const val DIRECT_TLS_SERVICE: String = "_xmpps-client"
    private const val STARTTLS_SERVICE: String = "_xmpp-client"

    private var SERVICE: XmppConnectionService? = null

    private val DNSSECLESS_TLDS: List<String> = listOf(
        "ae",
        "aero",
        "ai",
        "al",
        "ao",
        "aq",
        "as",
        "ba",
        "bb",
        "bd",
        "bf",
        "bi",
        "bj",
        "bn",
        "bo",
        "bs",
        "bw",
        "cd",
        "cf",
        "cg",
        "ci",
        "ck",
        "cm",
        "cu",
        "cv",
        "cw",
        "dj",
        "dm",
        "do",
        "ec",
        "eg",
        "eh",
        "er",
        "et",
        "fj",
        "fk",
        "ga",
        "ge",
        "gf",
        "gh",
        "gm",
        "gp",
        "gq",
        "gt",
        "gu",
        "hm",
        "ht",
        "im",
        "ir",
        "je",
        "jm",
        "jo",
        "ke",
        "kh",
        "km",
        "kn",
        "kp",
        "kz",
        "ls",
        "mg",
        "mh",
        "mk",
        "ml",
        "mm",
        "mo",
        "mp",
        "mq",
        "ms",
        "mt",
        "mu",
        "mv",
        "mw",
        "mz",
        "ne",
        "ng",
        "ni",
        "np",
        "nr",
        "om",
        "pa",
        "pf",
        "pg",
        "pk",
        "pn",
        "ps",
        "py",
        "qa",
        "rw",
        "sd",
        "sl",
        "sm",
        "so",
        "sr",
        "sv",
        "sy",
        "sz",
        "tc",
        "td",
        "tg",
        "tj",
        "to",
        "tr",
        "va",
        "vg",
        "vi",
        "ye",
        "zm",
        "zw",
    )

    /**
     * Tulkki: the Java declared this `protected static`, and a Kotlin `object` has no protected
     * members; it is read by nothing in the tree, so `@JvmField` keeps the same static field shape
     * and the one-step widening from `protected` to `public` is unobservable.
     */
    @JvmField
    val knownSRV: Map<String, String> = ImmutableMap.of(
        "_xmpp-client._tcp.yax.im", "xmpp.yaxim.org",
        "_xmpps-client._tcp.yax.im", "xmpp.yaxim.org",
        "_xmpp-server._tcp.yax.im", "xmpp.yaxim.org",
    )

    @JvmStatic
    fun init(service: XmppConnectionService) {
        SERVICE = service
        DnsClient.removeDNSServerLookupMechanism(AndroidUsingExec.INSTANCE)
        DnsClient.addDnsServerLookupMechanism(AndroidUsingExecLowPriority.INSTANCE)
        DnsClient.addDnsServerLookupMechanism(AndroidUsingLinkProperties(service))
        // The fork read the owner's custom DNS servers inside its own DnsClient; the release reads
        // no preference. This mechanism is where that delta lives now, and its priority makes the
        // list it returns exclusive, as the fork's branch was. See CustomDnsServerLookup.
        DnsClient.addDnsServerLookupMechanism(CustomDnsServerLookup(service))
        val client: AbstractDnsClient = RESOLVER_API.getClient()
        if (client is ReliableDnsClient) {
            client.setUseHardcodedDnsServers(false)
        }
        val dnssecclient: AbstractDnsClient = DnssecResolverApi.INSTANCE.getClient()
        if (dnssecclient is ReliableDnsClient) {
            dnssecclient.setUseHardcodedDnsServers(false)
            // If your DNS server sucks, just don't do DNSSEC
            dnssecclient.setMode(ReliableDnsClient.Mode.recursiveOnly)
        }
    }

    @JvmStatic
    fun fromHardCoded(hostname: String, port: Int): MutableList<Result> {
        val result = Result()
        result.hostname = DnsName.from(hostname)
        result.port = port
        result.directTls = useDirectTls(port)
        result.authenticated = true
        return Collections.singletonList(result)
    }

    @JvmStatic
    fun checkDomain(jid: Jid) {
        DnsName.from(jid.getDomain())
    }

    @JvmStatic
    fun invalidHostname(hostname: String): Boolean =
        try {
            DnsName.from(hostname)
            false
        } catch (e: InvalidDnsNameException) {
            true
        } catch (e: IllegalArgumentException) {
            true
        }

    @JvmStatic
    fun clearCache() {
        val client: AbstractDnsClient = RESOLVER_API.getClient()
        // Tulkki: `getCache()` is minidns's Java `DnsCache getCache()`, which is null when the
        // client carries no cache, and the Java original read it with `instanceof LruCache` -
        // tolerating the null, never dereferencing it. Declaring the local non-null re-added the
        // `checkNotNullExpressionValue` the platform type did not have; the Java nullable stays
        // nullable.
        val dnsCache: DnsCache? = client.getCache()
        if (dnsCache is LruCache) {
            Log.d(Config.LOGTAG, "clearing DNS cache")
            dnsCache.clear()
        }

        val clientSec: AbstractDnsClient = DnssecResolverApi.INSTANCE.getClient()
        val dnsCacheSec: DnsCache? = clientSec.getCache()
        if (dnsCacheSec is LruCache) {
            Log.d(Config.LOGTAG, "clearing DNSSEC cache")
            dnsCacheSec.clear()
        }
    }

    @JvmStatic
    fun useDirectTls(port: Int): Boolean = port == 443 || port == XMPP_PORT_DIRECT_TLS

    @JvmStatic
    fun resolve(domain: String): MutableList<Result> {
        val ipResults = fromIpAddress(domain)
        if (!ipResults.isEmpty()) {
            return ipResults
        }

        val startTls = resolveSrvAsFuture(domain, false)
        val directTls = resolveSrvAsFuture(domain, true)

        val combined = merge(ImmutableList.of(startTls, directTls))

        val combinedWithFallback =
            Futures.transformAsync(
                combined,
                AsyncFunction<MutableList<Result>, MutableList<Result>> { results ->
                    if (results.isEmpty()) {
                        resolveNoSrvAsFuture(DnsName.from(domain), true)
                    } else {
                        Futures.immediateFuture(results)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        val orderedFuture =
            Futures.transform(
                combinedWithFallback,
                Function<MutableList<Result>, MutableList<Result>> { all ->
                    Ordering.from(RESULT_COMPARATOR).immutableSortedCopy(all)
                },
                MoreExecutors.directExecutor(),
            )
        try {
            val ordered = orderedFuture.get()
            Log.d(Config.LOGTAG, "Resolver (" + ordered.size + "): " + ordered)
            return ordered
        } catch (e: ExecutionException) {
            Log.d(Config.LOGTAG, "error resolving DNS", e)
            return Collections.emptyList()
        } catch (e: InterruptedException) {
            Log.d(Config.LOGTAG, "DNS resolution interrupted")
            return Collections.emptyList()
        }
    }

    private fun fromIpAddress(domain: String): MutableList<Result> {
        if (IP.matches(domain)) {
            val inetAddress: InetAddress =
                try {
                    InetAddresses.forString(domain)
                } catch (e: IllegalArgumentException) {
                    return Collections.emptyList()
                }
            return Result.createWithDefaultPorts(null, inetAddress)
        } else {
            return Collections.emptyList()
        }
    }

    private fun resolveSrvAsFuture(
        domain: String,
        directTls: Boolean,
    ): ListenableFuture<MutableList<Result>> {
        val dnsName =
            DnsName.from(
                (if (directTls) DIRECT_TLS_SERVICE else STARTTLS_SERVICE) + "._tcp." + domain
            )
        val resultFuture: ListenableFuture<ResolverResult<SRV>> =
            resolveAsFuture(dnsName, SRV::class.java)
        return Futures.transformAsync(
            resultFuture,
            AsyncFunction<ResolverResult<SRV>, MutableList<Result>> { result ->
                resolveIpsAsFuture(result, directTls)
            },
            MoreExecutors.directExecutor(),
        )
    }

    @NonNull
    private fun resolveIpsAsFuture(
        srvResolverResult: ResolverResult<SRV>,
        directTls: Boolean,
    ): ListenableFuture<MutableList<Result>> {
        val futuresBuilder =
            ImmutableList.Builder<ListenableFuture<MutableList<Result>>>()
        for (record in srvResolverResult.getAnswersOrEmptySet()) {
            if (record.target.length == 0 && record.priority == 0) {
                continue
            }
            val ipv4sRaw =
                resolveIpsAsFuture(record, A::class.java, srvResolverResult.isAuthenticData(), directTls)
            val ipv4s =
                Futures.transform(
                    ipv4sRaw,
                    Function<MutableList<Result>, MutableList<Result>> { results ->
                        if (results.isEmpty()) {
                            val resolverResult = Result.fromRecord(record, directTls)
                            resolverResult.authenticated = srvResolverResult.isAuthenticData()
                            Collections.singletonList(resolverResult)
                        } else {
                            results
                        }
                    },
                    MoreExecutors.directExecutor(),
                )
            val ipv6s =
                resolveIpsAsFuture(record, AAAA::class.java, srvResolverResult.isAuthenticData(), directTls)
            futuresBuilder.add(ipv4s)
            futuresBuilder.add(ipv6s)
        }
        val futures = futuresBuilder.build()
        return merge(futures)
    }

    private fun merge(
        futures: Collection<ListenableFuture<MutableList<Result>>>
    ): ListenableFuture<MutableList<Result>> =
        Futures.transform(
            Futures.successfulAsList(futures),
            Function<MutableList<MutableList<Result>?>, MutableList<Result>> { lists ->
                val builder = ImmutableList.builder<Result>()
                for (list in lists) {
                    if (list == null) {
                        continue
                    }
                    builder.addAll(list)
                }
                builder.build()
            },
            MoreExecutors.directExecutor(),
        )

    private fun <D : InternetAddressRR<*>> resolveIpsAsFuture(
        srv: SRV,
        type: Class<D>,
        authenticated: Boolean,
        directTls: Boolean,
    ): ListenableFuture<MutableList<Result>> {
        val resultFuture = resolveAsFuture(srv.target, type)
        return Futures.transform(
            resultFuture,
            Function<ResolverResult<D>, MutableList<Result>> { result ->
                val builder = ImmutableList.builder<Result>()
                for (record in result.getAnswersOrEmptySet()) {
                    val resolverResult = Result.fromRecord(srv, directTls)
                    resolverResult.authenticated =
                        result.isAuthenticData() && authenticated // TODO technically it does not matter if the IP was authenticated
                    resolverResult.ip = record.getInetAddress()
                    builder.add(resolverResult)
                }
                builder.build()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun resolveNoSrvAsFuture(
        dnsName: DnsName,
        cName: Boolean,
    ): ListenableFuture<MutableList<Result>> {
        val futuresBuilder =
            ImmutableList.Builder<ListenableFuture<MutableList<Result>>>()
        val aRecordResults: ListenableFuture<MutableList<Result>> =
            Futures.transform(
                resolveAsFuture(dnsName, A::class.java),
                Function<ResolverResult<A>, MutableList<Result>> { result ->
                    Lists.transform(
                        ImmutableList.copyOf(result.getAnswersOrEmptySet()),
                        Function<A, Result> { a ->
                            Result.createDefault(dnsName, a.getInetAddress(), result.isAuthenticData())
                        },
                    )
                },
                MoreExecutors.directExecutor(),
            )
        futuresBuilder.add(aRecordResults)
        val aaaaRecordResults: ListenableFuture<MutableList<Result>> =
            Futures.transform(
                resolveAsFuture(dnsName, AAAA::class.java),
                Function<ResolverResult<AAAA>, MutableList<Result>> { result ->
                    Lists.transform(
                        ImmutableList.copyOf(result.getAnswersOrEmptySet()),
                        Function<AAAA, Result> { aaaa ->
                            Result.createDefault(
                                dnsName,
                                aaaa.getInetAddress(),
                                result.isAuthenticData(),
                            )
                        },
                    )
                },
                MoreExecutors.directExecutor(),
            )
        futuresBuilder.add(aaaaRecordResults)
        if (cName) {
            val cNameRecordResults: ListenableFuture<MutableList<Result>> =
                Futures.transformAsync(
                    resolveAsFuture(dnsName, CNAME::class.java),
                    AsyncFunction<ResolverResult<CNAME>, MutableList<Result>> { result ->
                        val test: Collection<ListenableFuture<MutableList<Result>>> =
                            Lists.transform(
                                ImmutableList.copyOf(result.getAnswersOrEmptySet()),
                                Function<CNAME, ListenableFuture<MutableList<Result>>> { cname ->
                                    resolveNoSrvAsFuture(cname.target, false)
                                },
                            )
                        merge(test)
                    },
                    MoreExecutors.directExecutor(),
                )
            futuresBuilder.add(cNameRecordResults)
        }
        val futures = futuresBuilder.build()
        val noSrvFallbacks = merge(futures)
        return Futures.transform(
            noSrvFallbacks,
            Function<MutableList<Result>, MutableList<Result>> { results ->
                if (results.isEmpty()) {
                    Result.createDefaults(dnsName)
                } else {
                    results
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun <D : Data> resolveAsFuture(
        dnsName: DnsName,
        type: Class<D>,
    ): ListenableFuture<ResolverResult<D>> {
        val start = System.currentTimeMillis()
        return Futures.submit(
            Callable<ResolverResult<D>> {
                var resolved: ResolverResult<D>? = null
                if (!DNSSECLESS_TLDS.contains(dnsName.getLabels()[0].toString())) {
                    for (i in 0 until 5) {
                        if (System.currentTimeMillis() - start > 5000) break
                        try {
                            // `ResolverApi.resolve(DnsName, Class<D>)` builds the `Question`
                            // internally and dispatches to this class's own overridden
                            // `resolve(Question)`, so the DNSSEC path is Java's exactly; naming
                            // `Record.TYPE` is what no Kotlin file here can do.
                            val result = DnssecResolverApi.INSTANCE.resolve<D>(dnsName, type)
                            resolved = result
                            if (result.wasSuccessful() && !result.isAuthenticData()) {
                                Log.d(
                                    Config.LOGTAG,
                                    "DNSSEC validation failed for " +
                                        type.getSimpleName() +
                                        " : " +
                                        result.getUnverifiedReasons(),
                                )
                            }
                            break
                        } catch (e: DnssecValidationFailedException) {
                            Log.d(
                                Config.LOGTAG,
                                Resolver::class.java.simpleName +
                                    ": error resolving " +
                                    type.getSimpleName() +
                                    " with DNSSEC. Trying DNS instead.",
                                e,
                            )
                            // Try again, may be transient DNSSEC failure https://github.com/MiniDNS/minidns/issues/132
                        } catch (throwable: Throwable) {
                            Log.d(
                                Config.LOGTAG,
                                Resolver::class.java.simpleName +
                                    ": error resolving " +
                                    type.getSimpleName() +
                                    " with DNSSEC. Trying DNS instead.",
                                throwable,
                            )
                            break
                        }
                    }
                }
                resolved ?: RESOLVER_API.resolve<D>(dnsName, type)
            },
            DNS_QUERY_EXECUTOR,
        )
    }

    class Result {

        internal var ip: InetAddress? = null
        internal var hostname: DnsName? = null
        internal var port: Int = Resolver.XMPP_PORT_STARTTLS
        internal var directTls: Boolean = false
        internal var authenticated: Boolean = false
        internal var priority: Int = 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val result = other as Result
            return port == result.port &&
                directTls == result.directTls &&
                authenticated == result.authenticated &&
                priority == result.priority &&
                Objects.equal(ip, result.ip) &&
                Objects.equal(hostname, result.hostname)
        }

        override fun hashCode(): Int =
            Objects.hashCode(ip, hostname, port, directTls, authenticated, priority)

        fun getIp(): InetAddress? = ip

        fun getPort(): Int = port

        fun getHostname(): DnsName? = hostname

        fun isDirectTls(): Boolean = directTls

        fun isAuthenticated(): Boolean = authenticated

        @NonNull
        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("ip", ip)
                .add("hostname", hostname)
                .add("port", port)
                .add("directTls", directTls)
                .add("authenticated", authenticated)
                .add("priority", priority)
                .toString()

        fun asDestination(): String {
            val ip = this.ip
            if (ip != null) {
                return InetAddresses.toAddrString(ip)
            }
            return (hostname ?: throw NullPointerException("hostname")).toString()
        }

        fun toContentValues(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(IP, ip?.getAddress())
            contentValues.put(HOSTNAME, hostname?.toString())
            contentValues.put(PORT, port)
            contentValues.put(PRIORITY, priority)
            contentValues.put(DIRECT_TLS, if (directTls) 1 else 0)
            contentValues.put(AUTHENTICATED, if (authenticated) 1 else 0)
            return contentValues
        }

        fun seeOtherHost(seeOtherHost: String): Result? {
            val hostname = seeOtherHost.trim()
            if (hostname.isEmpty()) {
                return null
            }
            val result = Result()
            result.directTls = this.directTls
            val portSegmentStart = hostname.lastIndexOf(':')
            if (hostname[hostname.length - 1] != ']' &&
                portSegmentStart >= 0 &&
                hostname.length >= portSegmentStart + 1
            ) {
                val hostPart = hostname.substring(0, portSegmentStart)
                val portPart = hostname.substring(portSegmentStart + 1)
                val port = Ints.tryParse(portPart)
                if (port == null || Strings.isNullOrEmpty(hostPart)) {
                    return null
                }
                val host = uk.xa0.tulkki.libs.IP.unwrapIPv6(hostPart)
                result.port = port
                if (InetAddresses.isInetAddress(host)) {
                    val inetAddress: InetAddress =
                        try {
                            InetAddresses.forString(host)
                        } catch (e: IllegalArgumentException) {
                            return null
                        }
                    result.ip = inetAddress
                } else {
                    if (hostPart.trim().isEmpty()) {
                        return null
                    }
                    try {
                        result.hostname = DnsName.from(hostPart.trim())
                    } catch (e: Exception) {
                        return null
                    }
                }
            } else {
                val host = uk.xa0.tulkki.libs.IP.unwrapIPv6(hostname)
                if (InetAddresses.isInetAddress(host)) {
                    val inetAddress: InetAddress =
                        try {
                            InetAddresses.forString(host)
                        } catch (e: IllegalArgumentException) {
                            return null
                        }
                    result.ip = inetAddress
                } else {
                    try {
                        result.hostname = DnsName.from(hostname)
                    } catch (e: Exception) {
                        return null
                    }
                }
                result.port = port
            }
            return result
        }

        companion object {

            const val DOMAIN: String = "domain"
            const val IP: String = "ip"
            const val HOSTNAME: String = "hostname"
            const val PORT: String = "port"
            const val PRIORITY: String = "priority"
            const val DIRECT_TLS: String = "directTls"
            const val AUTHENTICATED: String = "authenticated"

            internal fun fromRecord(srv: SRV, directTls: Boolean): Result {
                val result = Result()
                result.port = srv.port
                result.hostname = srv.target
                result.directTls = directTls
                result.priority = srv.priority
                return result
            }

            internal fun createWithDefaultPorts(
                hostname: DnsName?,
                ip: InetAddress?,
            ): MutableList<Result> =
                Lists.transform(
                    listOf(Resolver.XMPP_PORT_STARTTLS),
                    Function<Int, Result> { p -> createDefault(hostname, ip, p, false) },
                )

            internal fun createDefault(
                hostname: DnsName?,
                ip: InetAddress?,
                port: Int,
                authenticated: Boolean,
            ): Result {
                val result = Result()
                result.port = port
                result.hostname = hostname
                result.ip = ip
                result.authenticated = authenticated
                return result
            }

            internal fun createDefault(
                hostname: DnsName?,
                ip: InetAddress?,
                authenticated: Boolean,
            ): Result = createDefault(hostname, ip, Resolver.XMPP_PORT_STARTTLS, authenticated)

            internal fun createDefault(hostname: DnsName?): Result =
                createDefault(hostname, null, Resolver.XMPP_PORT_STARTTLS, false)

            internal fun createDefaults(
                hostname: DnsName?,
                inetAddresses: Collection<InetAddress>,
            ): MutableList<Result> {
                val builder = ImmutableList.builder<Result>()
                for (inetAddress in inetAddresses) {
                    builder.addAll(createWithDefaultPorts(hostname, inetAddress))
                }
                return builder.build()
            }

            internal fun createDefaults(hostname: DnsName?): MutableList<Result> =
                createWithDefaultPorts(hostname, null)

            @JvmStatic
            fun fromCursor(cursor: Cursor): Result {
                val result = Result()
                try {
                    result.ip =
                        InetAddress.getByAddress(cursor.getBlob(cursor.getColumnIndexOrThrow(IP)))
                } catch (e: UnknownHostException) {
                    result.ip = null
                }
                val hostname = cursor.getString(cursor.getColumnIndexOrThrow(HOSTNAME))
                result.hostname = if (hostname == null) null else DnsName.from(hostname)
                result.port = cursor.getInt(cursor.getColumnIndexOrThrow(PORT))
                result.priority = cursor.getInt(cursor.getColumnIndexOrThrow(PRIORITY))
                result.authenticated = cursor.getInt(cursor.getColumnIndexOrThrow(AUTHENTICATED)) > 0
                result.directTls = cursor.getInt(cursor.getColumnIndexOrThrow(DIRECT_TLS)) > 0
                return result
            }
        }
    }
}
