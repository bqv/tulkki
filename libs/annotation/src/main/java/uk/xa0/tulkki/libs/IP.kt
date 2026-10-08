package uk.xa0.tulkki.libs

import com.google.common.net.InetAddresses
import java.util.regex.Pattern

/**
 * The island's IPv4/IPv6 string tests and the bracketing the external-services DTO needs.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.utils.IP`, and it moved beside
 * `Jid.kt` for one reason: `Jid.ofUserInput` calls `IP.matches` on the ASCII domain, `:libs` sits
 * below `:xmpp` and may name nothing of it, so the literal test had to land on the same side of the
 * wall as the value type that reads it. It is a JDK-plus-Guava utility with no island dependency of
 * its own, so the move is package-only: `matches`, `wrapIPv6` and `unwrapIPv6` keep their exact
 * names, parameters and nullability, and the four namers (`Jid.kt` beside it now, `Resolver.kt`,
 * `MtmTrustDecision.kt` and `Services.kt`) change an import line. The one new build-file price is
 * Guava: `libs/annotation/build.gradle` declares it `implementation`, so nothing downstream
 * inherits the edge.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`matches` takes `String?`**, which is Java's own contract: the body is
 *    `server != null && (…)`, and `MemorizingTrustManager.java:452` is a caller whose read is
 *    already guarded that way. A non-null parameter would throw where Java answered `false`.
 * 2. **`wrapIPv6` takes `String?` and answers `String?`**, because its callers feed it into
 *    `String.format` (`Services.java:58`, `:63`), where a null host printed `null` rather than
 *    crashing; the `matches` guard is the whole of the body's test, as in Java.
 * 3. **`unwrapIPv6` takes `String`**: the first statement is `host.length()`, so Java threw on a
 *    null and every caller (`Resolver.java:688`, `:709`) passes a substring.
 * 4. **The five patterns stay `java.util.regex.Pattern` in a companion**, not Kotlin `Regex`: the
 *    call shape `PATTERN.matcher(server).matches()` is the Java line, and the companion's `private
 *    val`s are the same private static fields.
 * 5. **`[%s]".format(host)` is `String.format`'s own operation** through `kotlin.text.format`, which
 *    uses the default locale exactly as the Java call did.
 */
class IP {

    companion object {

        private val PATTERN_IPV4: Pattern =
            Pattern.compile(
                "\\A(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z"
            )
        private val PATTERN_IPV6_HEX4DECCOMPRESSED: Pattern =
            Pattern.compile(
                "\\A((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?) ::((?:[0-9A-Fa-f]{1,4}:)*)(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z"
            )
        private val PATTERN_IPV6_6HEX4DEC: Pattern =
            Pattern.compile(
                "\\A((?:[0-9A-Fa-f]{1,4}:){6,6})(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z"
            )
        private val PATTERN_IPV6_HEXCOMPRESSED: Pattern =
            Pattern.compile(
                "\\A((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)::((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)\\z"
            )
        private val PATTERN_IPV6: Pattern =
            Pattern.compile("\\A(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\z")

        @JvmStatic
        fun matches(server: String?): Boolean =
            server != null &&
                (PATTERN_IPV4.matcher(server).matches() ||
                    PATTERN_IPV6.matcher(server).matches() ||
                    PATTERN_IPV6_6HEX4DEC.matcher(server).matches() ||
                    PATTERN_IPV6_HEX4DECCOMPRESSED.matcher(server).matches() ||
                    PATTERN_IPV6_HEXCOMPRESSED.matcher(server).matches())

        @JvmStatic
        fun wrapIPv6(host: String?): String? =
            if (matches(host)) {
                "[%s]".format(host)
            } else {
                host
            }

        @JvmStatic
        fun unwrapIPv6(host: String): String {
            if (host.length > 2 && host[0] == '[' && host[host.length - 1] == ']') {
                val ip = host.substring(1, host.length - 1)
                if (InetAddresses.isInetAddress(ip)) {
                    return ip
                }
            }
            return host
        }
    }
}
