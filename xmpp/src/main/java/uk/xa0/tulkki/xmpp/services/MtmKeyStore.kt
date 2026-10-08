package uk.xa0.tulkki.xmpp.services

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Enumeration
import java.util.logging.Level
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager.Companion.LOGGER

/**
 * The app's own certificate key store - the "memorise" half of the trust manager.
 *
 * <p>It owns the file, the `KeyStore` and the `X509TrustManager` built from it; a memorised
 * certificate lives here and is what makes the next connection trust it without asking. The Kotlin
 * `KeyStore?` is Java's nullable field, and every dereference that Java would have NPE'd on keeps
 * that NPE rather than inventing a different failure.
 *
 * <p>`load` keeps the Java password (`MTM`), the default store type and the close in `finally`
 * through `XmppConnectionService.dataStatics()`, which is the island's own Closeable seam.
 */
internal class MtmKeyStore(private val keyStoreFile: File) {

    private var appKeyStore: KeyStore? = null

    /** The trust manager the app key store yields; `checkCertTrusted` tries it first. */
    var trustManager: X509TrustManager? = null
        private set

    init {
        appKeyStore = load()
        trustManager = getTrustManager(appKeyStore)
    }

    /** Java's `loadAppKeyStore`: `null` only when the default store type cannot be obtained. */
    private fun load(): KeyStore? {
        val ks: KeyStore = try {
            KeyStore.getInstance(KeyStore.getDefaultType())
        } catch (e: KeyStoreException) {
            LOGGER.log(Level.SEVERE, "getAppKeyStore()", e)
            return null
        }
        var fileInputStream: FileInputStream? = null
        try {
            ks.load(null, null)
            fileInputStream = FileInputStream(keyStoreFile)
            ks.load(fileInputStream, "MTM".toCharArray())
        } catch (e: FileNotFoundException) {
            LOGGER.log(Level.INFO, "getAppKeyStore($keyStoreFile) - file does not exist")
        } catch (e: Exception) {
            LOGGER.log(Level.SEVERE, "getAppKeyStore($keyStoreFile)", e)
        } finally {
            XmppConnectionService.dataStatics().close(fileInputStream)
        }
        return ks
    }

    /** Guava's `Preconditions.checkNotNull` was the Java line; it is an NPE with no message. */
    private fun getTrustManager(keyStore: KeyStore?): X509TrustManager? {
        val ks = keyStore ?: throw NullPointerException()
        try {
            val tmf = TrustManagerFactory.getInstance("X509")
            tmf.init(ks)
            for (t in tmf.trustManagers) {
                if (t is X509TrustManager) {
                    return t
                }
            }
        } catch (e: Exception) {
            // Here, we are covering up errors. It might be more useful however to throw them out of
            // the constructor so the embedding app knows something went wrong.
            LOGGER.log(Level.SEVERE, "getTrustManager($ks)", e)
        }
        return null
    }

    /** Java's `keyStoreUpdated`: rebuild the app trust manager and write the store back out. */
    fun refresh() {
        trustManager = getTrustManager(appKeyStore)
        val ks = appKeyStore ?: throw NullPointerException()
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(keyStoreFile)
            ks.store(fos, "MTM".toCharArray())
        } catch (e: Exception) {
            LOGGER.log(Level.SEVERE, "storeCert($keyStoreFile)", e)
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (e: IOException) {
                    LOGGER.log(Level.SEVERE, "storeCert($keyStoreFile)", e)
                }
            }
        }
    }

    fun storeCert(alias: String, cert: Certificate) {
        val ks = appKeyStore ?: throw NullPointerException()
        try {
            ks.setCertificateEntry(alias, cert)
        } catch (e: KeyStoreException) {
            LOGGER.log(Level.SEVERE, "storeCert($cert)", e)
            return
        }
        refresh()
    }

    fun storeCert(cert: X509Certificate) {
        storeCert(cert.getSubjectDN().toString(), cert)
    }

    // A certificate already in the app key store is "known", and is accepted without asking.
    fun isCertKnown(cert: X509Certificate): Boolean =
            try {
                val ks = appKeyStore ?: throw NullPointerException()
                ks.getCertificateAlias(cert) != null
            } catch (e: KeyStoreException) {
                false
            }

    fun getCertificates(): Enumeration<String> {
        val ks = appKeyStore ?: throw NullPointerException()
        try {
            return ks.aliases()
        } catch (e: KeyStoreException) {
            // this should never happen, however...
            throw RuntimeException(e)
        }
    }

    fun deleteCertificate(alias: String) {
        val ks = appKeyStore ?: throw NullPointerException()
        ks.deleteEntry(alias)
        refresh()
    }
}
