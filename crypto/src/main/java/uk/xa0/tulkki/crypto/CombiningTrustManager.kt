package uk.xa0.tulkki.crypto

import android.util.Log

import com.google.common.collect.ImmutableList

import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Arrays

import javax.net.ssl.X509TrustManager

import uk.xa0.tulkki.xmpp.Config

class CombiningTrustManager private constructor(
    private val trustManagers: List<X509TrustManager>
) : X509TrustManager {

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        val iterator = this.trustManagers.iterator()
        while (iterator.hasNext()) {
            val trustManager = iterator.next()
            try {
                trustManager.checkClientTrusted(chain, authType)
            } catch (certificateException: CertificateException) {
                if (iterator.hasNext()) {
                    continue
                }
                throw certificateException
            }
        }
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        Log.d(
            Config.LOGTAG,
            javaClass.simpleName +
                " is configured with " +
                this.trustManagers.size +
                " TrustManagers"
        )
        var i = 0
        val iterator = this.trustManagers.iterator()
        while (iterator.hasNext()) {
            val trustManager = iterator.next()
            try {
                trustManager.checkServerTrusted(chain, authType)
                Log.d(
                    Config.LOGTAG,
                    "certificate check passed on " +
                        trustManager.javaClass.name +
                        ". chain length was " +
                        chain.size
                )
                return
            } catch (certificateException: CertificateException) {
                Log.d(
                    Config.LOGTAG,
                    "failed to verify in [" + i + "]/" + trustManager.javaClass.name,
                    certificateException
                )
                if (iterator.hasNext()) {
                    continue
                }
                throw certificateException
            } finally {
                ++i
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        val certificates = ImmutableList.builder<X509Certificate>()
        for (trustManager in this.trustManagers) {
            for (certificate in trustManager.acceptedIssuers) {
                certificates.add(certificate)
            }
        }
        return certificates.build().toTypedArray()
    }

    companion object {
        @JvmStatic
        @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
        fun combineWithDefault(vararg trustManagers: X509TrustManager): X509TrustManager {
            val builder = ImmutableList.builder<X509TrustManager>()
            builder.addAll(Arrays.asList(*trustManagers))
            builder.add(TrustManagers.createDefaultTrustManager())
            return CombiningTrustManager(builder.build())
        }
    }
}
