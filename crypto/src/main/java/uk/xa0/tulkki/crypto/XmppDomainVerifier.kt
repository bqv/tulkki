package uk.xa0.tulkki.crypto

import android.util.Log
import android.util.Pair
import com.google.common.base.MoreObjects
import com.google.common.collect.ImmutableList
import java.io.IOException
import java.net.IDN
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import org.bouncycastle.asn1.ASN1Object
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERIA5String
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.DLSequence
import org.bouncycastle.asn1.x500.RDN
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder

class XmppDomainVerifier {

    @Throws(SSLPeerUnverifiedException::class)
    fun verify(unicodeDomain: String, unicodeHostname: String?, sslSession: SSLSession): Boolean {
        val domain = IDN.toASCII(unicodeDomain)
        val hostname = if (unicodeHostname == null) null else IDN.toASCII(unicodeHostname)
        val chain = sslSession.peerCertificates
        if (chain.isEmpty() || chain[0] !is X509Certificate) {
            return false
        }
        val certificate = chain[0] as X509Certificate
        val commonNames = getCommonNames(certificate)
        if (isSelfSigned(certificate)) {
            if (commonNames.size == 1 && matchDomain(domain, commonNames)) {
                Log.d(LOGTAG, "accepted CN in self signed cert as work around for $domain")
                return true
            }
        }
        return try {
            val validDomains = parseValidDomains(certificate)
            Log.d(LOGTAG, "searching for $domain in $validDomains")
            if (hostname != null) {
                Log.d(LOGTAG, "also trying to verify hostname $hostname")
            }
            validDomains.xmppAddresses.contains(domain) ||
                validDomains.srvNames.contains("_xmpp-client.$domain") ||
                matchDomain(domain, validDomains.domains) ||
                (hostname != null && matchDomain(hostname, validDomains.domains))
        } catch (e: Exception) {
            false
        }
    }

    private fun isSelfSigned(certificate: X509Certificate): Boolean {
        return try {
            certificate.verify(certificate.publicKey)
            true
        } catch (e: Exception) {
            false
        }
    }

    class ValidDomains internal constructor(
        internal val xmppAddresses: List<String>,
        internal val srvNames: List<String>,
        internal val domains: List<String>
    ) {

        fun all(): List<String> {
            val all = ImmutableList.Builder<String>()
            all.addAll(xmppAddresses)
            all.addAll(srvNames)
            all.addAll(domains)
            return all.build()
        }

        override fun toString(): String = MoreObjects.toStringHelper(this)
            .add("xmppAddresses", xmppAddresses)
            .add("srvNames", srvNames)
            .add("domains", domains)
            .toString()
    }

    companion object {

        private const val LOGTAG = "XmppDomainVerifier"

        private const val SRV_NAME = "1.3.6.1.5.5.7.8.7"
        private const val XMPP_ADDR = "1.3.6.1.5.5.7.8.5"

        private fun getCommonNames(certificate: X509Certificate): List<String> {
            val domains = ArrayList<String>()
            return try {
                val x500name = JcaX509CertificateHolder(certificate).subject
                val rdns = x500name.getRDNs(BCStyle.CN)
                for (i in rdns.indices) {
                    domains.add(
                        IETFUtils.valueToString(
                            x500name.getRDNs(BCStyle.CN)[i].first.value
                        )
                    )
                }
                domains
            } catch (e: CertificateEncodingException) {
                domains
            }
        }

        private fun parseOtherName(otherName: ByteArray): Pair<String, String>? {
            return try {
                val asn1Primitive = ASN1Primitive.fromByteArray(otherName)
                if (asn1Primitive is ASN1TaggedObject) {
                    val inner: ASN1Object = asn1Primitive.baseObject
                    if (inner is DLSequence) {
                        if (inner.size() >= 2) {
                            val evenInner = inner.getObjectAt(1)
                            if (evenInner is ASN1TaggedObject) {
                                val oid = inner.getObjectAt(0).toString()
                                val value = evenInner.baseObject
                                if (value is DERUTF8String) {
                                    Pair(oid, value.string)
                                } else if (value is DERIA5String) {
                                    Pair(oid, value.string)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: IOException) {
                null
            }
        }

        @JvmStatic
        fun matchDomain(needle: String, haystack: List<String>): Boolean {
            for (entry in haystack) {
                if (entry.startsWith("*.")) {
                    // https://www.rfc-editor.org/rfc/rfc6125#section-6.4.3
                    // wild cards can only be in the left most label and don't match '.'
                    val i = needle.indexOf('.')
                    if (i != -1 && needle.substring(i).equals(entry.substring(1), ignoreCase = true)) {
                        return true
                    }
                } else {
                    if (entry.equals(needle, ignoreCase = true)) {
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        @Throws(CertificateParsingException::class)
        fun parseValidDomains(certificate: X509Certificate): ValidDomains {
            val commonNames = getCommonNames(certificate)
            val alternativeNames = certificate.subjectAlternativeNames
            val xmppAddrs = ArrayList<String>()
            val srvNames = ArrayList<String>()
            val domains = ArrayList<String>()
            if (alternativeNames != null) {
                for (san in alternativeNames) {
                    val type = san[0] as Int
                    if (type == 0) {
                        val otherName = parseOtherName(san[1] as ByteArray)
                        if (otherName != null && otherName.first != null && otherName.second != null) {
                            when (otherName.first) {
                                SRV_NAME ->
                                    srvNames.add(otherName.second.lowercase(Locale.US))
                                XMPP_ADDR ->
                                    xmppAddrs.add(otherName.second.lowercase(Locale.US))
                                else ->
                                    Log.d(LOGTAG, "oid: ${otherName.first} value: ${otherName.second}")
                            }
                        }
                    } else if (type == 2) {
                        val value = san[1]
                        if (value is String) {
                            domains.add(value.lowercase(Locale.US))
                        }
                    }
                }
            }
            if (srvNames.isEmpty() && xmppAddrs.isEmpty() && domains.isEmpty()) {
                domains.addAll(commonNames)
            }
            return ValidDomains(xmppAddrs, srvNames, domains)
        }
    }
}
