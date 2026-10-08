package uk.xa0.tulkki.app.http

import java.net.URI
import java.net.URISyntaxException
import okhttp3.HttpUrl

/**
 * Tulkki: the URL schemes this app accepts, and the two shapes an upload anchor travels in.
 *
 * Ported from `URL.java`. It is *our*
 * code rather than the island's, so it is converted in place instead of being reproduced and
 * dissolved: the class was already a stateless holder and only its spelling changes.
 *
 * The Java-visible surface is the contract, read off the callers, not off the types:
 *
 *  * `WELL_KNOWN_SCHEMES` is read as a **field** by `MessageUtils.kt:85,137`, so it stays a
 *    `@JvmField` (`public static final List<String>`); `Arrays.asList` was fixed-size and
 *    `listOf` is immutable, which is stricter but unobservable — nothing mutates it.
 *  * `stripFragment` is called statically by `HttpDownloadConnection.java:379,456`, which is the
 *    only Java caller, so it keeps its `@JvmStatic` bridge.
 *  * `tryParse` answers **null** for a URL whose scheme is unknown or unparseable, and
 *    `Message.kt:1539` reads it as `URL.tryParse(address) ?: return null`; the Kotlin return is
 *    therefore `String?`, and the caller's `address`/`parts[0]` are non-null, so the parameter
 *    stays non-null.
 *
 * The Java's `URISyntaxException` catch maps to a Kotlin `try`/`catch`, and its `else null` maps
 * to the `if` expression — no behaviour is added or dropped.
 */
object URL {

    @JvmField
    val WELL_KNOWN_SCHEMES: List<String> =
        listOf("http", "https", AesGcmURL.PROTOCOL_NAME, "cid")

    @JvmStatic
    fun tryParse(url: String): String? {
        val uri: URI = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        return if (WELL_KNOWN_SCHEMES.contains(uri.scheme)) uri.toString() else null
    }

    @JvmStatic
    fun stripFragment(url: HttpUrl): HttpUrl = url.newBuilder().fragment(null).build()
}
