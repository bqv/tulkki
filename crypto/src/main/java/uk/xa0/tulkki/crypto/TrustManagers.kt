package uk.xa0.tulkki.crypto

import android.content.Context

import androidx.annotation.Nullable

import com.google.common.collect.Iterables

import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.util.Arrays

import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TrustManagers private constructor() {

    companion object {

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
        fun createTrustManager(@Nullable keyStore: KeyStore?): X509TrustManager {
            val trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)
            return Iterables.getOnlyElement(
                Iterables.filter(
                    Arrays.asList(*trustManagerFactory.trustManagers),
                    X509TrustManager::class.java
                )
            )
        }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
        fun createDefaultTrustManager(): X509TrustManager = createTrustManager(null)

        @JvmStatic
        @Throws(
            NoSuchAlgorithmException::class,
            KeyStoreException::class,
            CertificateException::class,
            IOException::class
        )
        fun defaultWithBundledLetsEncrypt(context: Context): X509TrustManager {
            val bundleTrustManager: BundledTrustManager =
                BundledTrustManager.builder()
                    .loadKeyStore(
                        context.resources.openRawResource(R.raw.letsencrypt),
                        "letsencrypt"
                    )
                    .build()
            return CombiningTrustManager.combineWithDefault(bundleTrustManager)
        }
    }
}
