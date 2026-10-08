package uk.xa0.tulkki.crypto

import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

import javax.net.ssl.X509TrustManager

class BundledTrustManager private constructor(keyStore: KeyStore?) : X509TrustManager {

    private val delegate: X509TrustManager = TrustManagers.createTrustManager(keyStore)

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        this.delegate.checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        this.delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = this.delegate.acceptedIssuers

    class Builder internal constructor() {

        private var keyStore: KeyStore? = null

        @Throws(
            CertificateException::class,
            IOException::class,
            NoSuchAlgorithmException::class,
            KeyStoreException::class
        )
        fun loadKeyStore(inputStream: InputStream, password: String): Builder {
            if (this.keyStore != null) {
                throw IllegalStateException("KeyStore has already been loaded")
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(inputStream, password.toCharArray())
            this.keyStore = keyStore
            return this
        }

        @Throws(NoSuchAlgorithmException::class, KeyStoreException::class)
        fun build(): BundledTrustManager = BundledTrustManager(keyStore)
    }

    companion object {
        @JvmStatic
        @Throws(KeyStoreException::class)
        fun builder(): Builder = Builder()
    }
}
