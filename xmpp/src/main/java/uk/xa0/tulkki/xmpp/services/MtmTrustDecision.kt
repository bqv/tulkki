package uk.xa0.tulkki.xmpp.services

import android.util.Log
import org.minidns.dane.DaneVerifier
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.IP
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.function.Consumer
import java.util.logging.Level
import javax.net.ssl.X509TrustManager
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager.Companion.LOGGER

/**
 * The asking half: is this chain trusted, and if not, does the owner get asked?
 *
 * <p>The order is the Java one and it is the security decision: DANE first when a verified hostname
 * is available, then the app key store, then a memorised alias, then the system default, and only
 * then - and only for the interactive managers - the owner. The three nested managers differ in
 * nothing but that last bit, which is why they share this object.
 *
 * <p>[onInteractive] is the shell's `interactCert`; it is a lambda because storing a certificate
 * belongs to the key store, not here.
 */
internal class MtmTrustDecision(
        private val keyStore: MtmKeyStore,
        private val defaultTrustManager: X509TrustManager?,
        private val daneVerifier: DaneVerifier,
        private val onInteractive: (
                Array<X509Certificate>,
                String?,
                CertificateException
        ) -> Unit
) {

    fun check(
            chain: Array<X509Certificate>,
            authType: String?,
            domain: String?,
            isServer: Boolean,
            interactive: Boolean,
            verifiedHostname: String?,
            port: Int,
            daneCb: Consumer<Boolean>?,
            enforceDane: Boolean
    ) {
        LOGGER.log(
                Level.FINE,
                "checkCertTrusted(" + chain.toString() + ", " + authType + ", " + isServer + ")")
        try {
            LOGGER.log(Level.FINE, "checkCertTrusted: trying appTrustManager")
            if (isServer) {
                if (verifiedHostname != null && !IP.matches(verifiedHostname)) {
                    try {
                        val matched =
                                daneVerifier.verifyCertificateChain(chain, verifiedHostname, port)
                        daneCb?.accept(matched)
                        if (matched) {
                            return
                        } else if (enforceDane) {
                            throw DaneEnforcementException(
                                    "DANE is enforced but verification did not match for "
                                            + verifiedHostname)
                        }
                    } catch (e: DaneEnforcementException) {
                        throw e
                    } catch (e: CertificateException) {
                        // The fork's four-argument overload reported the outcome through this
                        // callback; the release's three-argument method does not, so it is reported
                        // here. The only CertificateException the verification throws is the
                        // all-TLSA-records-mismatched one, and the fork had already called with
                        // false before it threw.
                        daneCb?.accept(false)
                        Log.d(Config.LOGTAG, "checkCertTrusted DANE failure: $e")
                        if (enforceDane) {
                            throw DaneEnforcementException("DANE failure: " + e.message)
                        }
                        throw e
                    } catch (e: Throwable) {
                        Log.d(Config.LOGTAG, "checkCertTrusted DANE related failure: $e")
                        if (enforceDane) {
                            throw DaneEnforcementException(
                                    "DANE related failure: " + e.message)
                        }
                    }
                } else if (enforceDane) {
                    throw DaneEnforcementException(
                            "DANE is enforced but verified hostname is missing or IP for " + domain)
                }
                appTrustManager().checkServerTrusted(chain, authType)
            } else {
                appTrustManager().checkClientTrusted(chain, authType)
            }
        } catch (ae: CertificateException) {
            if (ae is DaneEnforcementException) {
                throw ae
            }
            LOGGER.log(Level.FINER, "checkCertTrusted: appTrustManager failed", ae)
            if (keyStore.isCertKnown(chain[0])) {
                LOGGER.log(
                        Level.INFO, "checkCertTrusted: accepting cert already stored in keystore")
                return
            }
            try {
                if (defaultTrustManager == null) {
                    throw ae
                }
                LOGGER.log(Level.FINE, "checkCertTrusted: trying defaultTrustManager")
                if (isServer) {
                    defaultTrustManager.checkServerTrusted(chain, authType)
                } else {
                    defaultTrustManager.checkClientTrusted(chain, authType)
                }
            } catch (e: CertificateException) {
                if (interactive) {
                    onInteractive(chain, authType, e)
                } else {
                    throw e
                }
            }
        }
    }

    fun acceptedIssuers(): Array<X509Certificate> =
            defaultTrustManager?.getAcceptedIssuers() ?: emptyArray()

    /** Java dereferenced this field unchecked; a null one was an NPE, and stays one. */
    private fun appTrustManager(): X509TrustManager =
            keyStore.trustManager ?: throw NullPointerException()
}

/** The non-interactive adapter: asks no one, so a stranger's certificate fails. */
internal class MtmNonInteractiveTrustManager(
        private val decision: MtmTrustDecision,
        private val domain: String?,
        private val verifiedHostname: String?,
        private val port: Int,
        private val daneCb: Consumer<Boolean>?,
        private val enforceDane: Boolean
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String?) {
        decision.check(
                chain, authType, domain, false, false, verifiedHostname, port, daneCb, enforceDane)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String?) {
        decision.check(
                chain, authType, domain, true, false, verifiedHostname, port, daneCb, enforceDane)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = decision.acceptedIssuers()
}

/** The interactive adapter: a stranger's certificate opens the decision screen. */
internal class MtmInteractiveTrustManager(
        private val decision: MtmTrustDecision,
        private val domain: String?,
        private val verifiedHostname: String?,
        private val port: Int,
        private val daneCb: Consumer<Boolean>?,
        private val enforceDane: Boolean
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String?) {
        decision.check(
                chain, authType, domain, false, true, verifiedHostname, port, daneCb, enforceDane)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String?) {
        decision.check(
                chain, authType, domain, true, true, verifiedHostname, port, daneCb, enforceDane)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = decision.acceptedIssuers()
}
