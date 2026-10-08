package uk.xa0.tulkki.xmpp.services

import android.content.Context
import java.io.IOException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Tulkki: the TLS trust family, in island vocabulary.
 *
 * Pair 7 of the module cycle plan (`docs/MIGRATION.md` "The cycle rules" §3, D1); un-nested by chunk
 * C66 and converted from the Java. The four classes that do the work are JDK-only and live in
 * `:crypto`, so `CryptoTrustPort` - already Kotlin - is the only implementation, and it is where
 * every nullability reading here comes from rather than from taste:
 *
 *  * `verify`'s `unicodeHostname` is **nullable**. `CryptoTrustPort.kt:42` already declares
 *    `unicodeHostname: String?`, because a DANE/xmpp-addr verification may have no hostname to
 *    match (`XmppDomainVerifier.verify` handles the null case explicitly). A non-null parameter
 *    would be an invalid override.
 *  * Everything else is non-null: `matchDomain`'s domain and domain list, `parseValidDomains`'s
 *    certificate and its answer, the two trust-manager factories and their `Context`.
 *  * The wildcard question is settled by the type argument's finality, measured with kotlinc 2.3.21:
 *    `List<String>`'s argument is `String`, which is **final**, so Kotlin emits the Java's invariant
 *    `List<String>` and no `@JvmSuppressWildcards` is owed here. `parseValidDomains`'s answer is a
 *    return, where Kotlin emits no wildcard anyway.
 *  * The four checked `throws` clauses are re-declared with `@Throws`, which Kotlin needs because it
 *    has no checked exceptions: `:crypto`'s implementation and the island callers both see them.
 */
interface TrustPort {

    fun matchDomain(needle: String, haystack: List<String>): Boolean

    @Throws(CertificateParsingException::class)
    fun parseValidDomains(certificate: X509Certificate): List<String>

    @Throws(SSLPeerUnverifiedException::class)
    fun verify(unicodeDomain: String, unicodeHostname: String?, session: SSLSession): Boolean

    @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
    fun defaultTrustManager(): X509TrustManager

    @Throws(
        NoSuchAlgorithmException::class,
        KeyStoreException::class,
        CertificateException::class,
        IOException::class,
    )
    fun defaultWithBundledLetsEncrypt(context: Context): X509TrustManager
}
