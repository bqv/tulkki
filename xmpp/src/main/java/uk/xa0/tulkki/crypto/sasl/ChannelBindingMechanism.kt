package uk.xa0.tulkki.crypto.sasl

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.conscrypt.Conscrypt

interface ChannelBindingMechanism {

    fun getChannelBinding(): ChannelBinding

    companion object {

        const val EXPORTER_LABEL: String = "EXPORTER-Channel-Binding"

        @JvmStatic
        @Throws(SaslMechanism.AuthenticationException::class)
        fun getChannelBindingData(sslSocket: SSLSocket?, channelBinding: ChannelBinding): ByteArray {
            if (sslSocket == null) {
                throw SaslMechanism.AuthenticationException(
                    "Channel binding attempt on non secure socket",
                )
            }
            if (channelBinding == ChannelBinding.TLS_EXPORTER) {
                if (!Conscrypt.isConscrypt(sslSocket)) {
                    throw SaslMechanism.AuthenticationException(
                        "Channel binding attempt on non supporting socket",
                    )
                }
                val keyingMaterial: ByteArray?
                try {
                    keyingMaterial =
                        Conscrypt.exportKeyingMaterial(sslSocket, EXPORTER_LABEL, ByteArray(0), 32)
                } catch (e: SSLException) {
                    throw SaslMechanism.AuthenticationException("Could not export keying material")
                }
                if (keyingMaterial == null) {
                    throw SaslMechanism.AuthenticationException(
                        "Could not export keying material. Socket not ready",
                    )
                }
                return keyingMaterial
            } else if (channelBinding == ChannelBinding.TLS_UNIQUE) {
                if (!Conscrypt.isConscrypt(sslSocket)) {
                    throw SaslMechanism.AuthenticationException(
                        "Channel binding attempt on non supporting socket",
                    )
                }
                val unique: ByteArray? = Conscrypt.getTlsUnique(sslSocket)
                if (unique == null) {
                    throw SaslMechanism.AuthenticationException(
                        "Could not retrieve tls unique. Socket not ready",
                    )
                }
                return unique
            } else if (channelBinding == ChannelBinding.TLS_SERVER_END_POINT) {
                return getServerEndPointChannelBinding(sslSocket.session)
            } else {
                throw SaslMechanism.AuthenticationException(
                    String.format("%s is not a valid channel binding", channelBinding),
                )
            }
        }

        @JvmStatic
        @Throws(SaslMechanism.AuthenticationException::class)
        fun getServerEndPointChannelBinding(session: SSLSession): ByteArray {
            val certificates: Array<Certificate>
            try {
                certificates = session.peerCertificates
            } catch (e: SSLPeerUnverifiedException) {
                throw SaslMechanism.AuthenticationException("Could not verify peer certificates")
            }
            if (certificates.isEmpty()) {
                throw SaslMechanism.AuthenticationException("Could not retrieve peer certificate")
            }
            val first = certificates[0]
            if (first !is X509Certificate) {
                throw SaslMechanism.AuthenticationException("Certificate was not X509")
            }
            val certificate: X509Certificate = first
            val algorithm = certificate.sigAlgName
            val withIndex = algorithm.indexOf("with")
            if (withIndex <= 0) {
                throw SaslMechanism.AuthenticationException("Unable to parse SigAlgName")
            }
            val hashAlgorithm = algorithm.substring(0, withIndex)
            val messageDigest: MessageDigest
            // https://www.rfc-editor.org/rfc/rfc5929#section-4.1
            if ("MD5".equals(hashAlgorithm, ignoreCase = true) ||
                "SHA1".equals(hashAlgorithm, ignoreCase = true)
            ) {
                messageDigest = SHA256.Digest()
            } else {
                try {
                    messageDigest = MessageDigest.getInstance(hashAlgorithm)
                } catch (e: NoSuchAlgorithmException) {
                    throw SaslMechanism.AuthenticationException(
                        "Could not instantiate message digest for " + hashAlgorithm,
                    )
                }
            }
            val encodedCertificate: ByteArray
            try {
                encodedCertificate = certificate.encoded
            } catch (e: CertificateEncodingException) {
                throw SaslMechanism.AuthenticationException("Could not encode certificate")
            }
            messageDigest.update(encodedCertificate)
            return messageDigest.digest()
        }

        @JvmStatic
        fun getPriority(mechanism: SaslMechanism): Int {
            return if (mechanism is ChannelBindingMechanism) {
                ChannelBinding.priority(mechanism.getChannelBinding())
            } else {
                0
            }
        }
    }
}
