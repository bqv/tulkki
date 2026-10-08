package uk.xa0.tulkki.xmpp.services

import android.content.Context
import uk.xa0.tulkki.xmpp.R
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.logging.Level
import com.google.common.base.Joiner
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager.Companion.LOGGER

/**
 * The text an owner reads before accepting a certificate: the subject, its validity window, both
 * digests, the issuer, and - for the leaf - the domains the certificate claims.
 *
 * <p>It is extracted from the trust manager because it is the one part of that class that only
 * formats: nothing here decides. `DATE_FORMAT` stays the Java `SimpleDateFormat` with `Locale.US`,
 * and the hashes stay `MessageDigest` over the DER encoding. The two dead private helpers the Java
 * carried - an IP-literal test and a base64 digest - had no caller and are not ported.
 */
internal class MtmCertificateText(
        private val master: Context,
        private val trustPort: TrustPort
) {

    fun certDetails(si: StringBuffer, c: X509Certificate, showValidFor: Boolean) {
        si.append("\n")
        if (showValidFor) {
            try {
                si.append("Valid for: ")
                si.append(Joiner.on(", ").join(trustPort.parseValidDomains(c)))
            } catch (e: CertificateParsingException) {
                si.append("Unable to parse Certificate")
            }
            si.append("\n")
        } else {
            si.append(c.getSubjectDN())
        }
        si.append("\n")
        si.append(DATE_FORMAT.format(c.getNotBefore()))
        si.append(" - ")
        si.append(DATE_FORMAT.format(c.getNotAfter()))
        si.append("\nSHA-256: ")
        si.append(certHash(c, "SHA-256"))
        si.append("\nSHA-1: ")
        si.append(certHash(c, "SHA-1"))
        si.append("\nSigned by: ")
        si.append(c.getIssuerDN().toString())
        si.append("\n")
    }

    fun certChainMessage(chain: Array<X509Certificate>, cause: CertificateException): String {
        var e: Throwable = cause
        LOGGER.log(Level.FINE, "certChainMessage for $e")
        val si = StringBuffer()
        val causeOfCause = e.cause
        if (causeOfCause != null) {
            e = causeOfCause
            // HACK: there is no sane way to check if the error is a "trust anchor not found", so
            // we use string comparison.
            if (NO_TRUST_ANCHOR == e.message) {
                si.append(master.getString(R.string.mtm_trust_anchor))
            } else {
                si.append(e.localizedMessage)
            }
            si.append("\n")
        }
        si.append("\n")
        si.append(master.getString(R.string.mtm_connect_anyway))
        si.append("\n\n")
        si.append(master.getString(R.string.mtm_cert_details))
        si.append('\n')
        for (i in chain.indices) {
            certDetails(si, chain[i], i == 0)
        }
        return si.toString()
    }

    companion object {

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private const val NO_TRUST_ANCHOR = "Trust anchor for certification path not found."

        private fun hexString(data: ByteArray): String {
            val si = StringBuffer()
            for (i in data.indices) {
                si.append(String.format("%02x", data[i]))
                if (i < data.size - 1) {
                    si.append(":")
                }
            }
            return si.toString()
        }

        private fun certHash(cert: X509Certificate, digest: String): String? =
                try {
                    val md = MessageDigest.getInstance(digest)
                    md.update(cert.getEncoded())
                    hexString(md.digest())
                } catch (e: CertificateEncodingException) {
                    e.message
                } catch (e: NoSuchAlgorithmException) {
                    e.message
                }
    }
}
