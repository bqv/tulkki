package uk.xa0.tulkki.crypto

import android.content.Context

import uk.xa0.tulkki.xmpp.services.XmppConnectionService

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
 * The TLS trust family behind the XMPP island's port.
 *
 * Pair 7 of the module cycle plan (`docs/MIGRATION.md` "The cycle rules" §3, D1). All four
 * classes that do the work (`XmppDomainVerifier`, `TrustManagers`,
 * `BundledTrustManager`, `CombiningTrustManager`) are JDK-only, so by the ruling's own test
 * the implementation belongs in `:xmpp` - but the move is unavailable (a class claim splits
 * `uk.xa0.tulkki.crypto` across two modules and `verdict()` refuses it, while a package
 * rename is barred by the island rule). So the island declares [uk.xa0.tulkki.xmpp.services.TrustPort]
 * and this class implements it by delegating, one line per member.
 * Nothing here is stateful, which is why one instance is the whole of the implementation.
 */
class CryptoTrustPort private constructor() : uk.xa0.tulkki.xmpp.services.TrustPort {

    override fun matchDomain(needle: String, haystack: List<String>): Boolean =
        XmppDomainVerifier.matchDomain(needle, haystack)

    @Throws(CertificateParsingException::class)
    override fun parseValidDomains(certificate: X509Certificate): List<String> =
        XmppDomainVerifier.parseValidDomains(certificate).all()

    @Throws(SSLPeerUnverifiedException::class)
    override fun verify(
        unicodeDomain: String,
        unicodeHostname: String?,
        session: SSLSession
    ): Boolean = XmppDomainVerifier().verify(unicodeDomain, unicodeHostname, session)

    @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
    override fun defaultTrustManager(): X509TrustManager = TrustManagers.createDefaultTrustManager()

    @Throws(
        NoSuchAlgorithmException::class,
        KeyStoreException::class,
        CertificateException::class,
        IOException::class
    )
    override fun defaultWithBundledLetsEncrypt(context: Context): X509TrustManager =
        TrustManagers.defaultWithBundledLetsEncrypt(context)

    companion object {
        @JvmField
        val INSTANCE: CryptoTrustPort = CryptoTrustPort()
    }
}
