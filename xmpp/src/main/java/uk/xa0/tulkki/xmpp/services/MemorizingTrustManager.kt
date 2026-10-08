package uk.xa0.tulkki.xmpp.services

import android.app.Application
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import org.minidns.dane.DaneVerifier
import uk.xa0.tulkki.xmpp.R
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Enumeration
import java.util.function.Consumer
import java.util.logging.Logger
import javax.net.ssl.X509TrustManager

/**
 * A X509 trust manager implementation which asks the user about invalid certificates and memorizes
 * their decision - the shell.
 *
 * <p>The Java file held five responsibilities; four now own themselves, in this package:
 * [MtmKeyStore] (the memorised certificates, the file and the trust manager built from it),
 * [MtmCertificateText] (what the owner reads), [MtmDecisions] (the process-wide inbox of open
 * decisions and the socket thread that blocks on one) and [MtmTrustDecision] (the order in which a
 * chain is trusted, with the two X509 adapters). This class keeps the public surface, the two
 * constructors and the one `interactResult` entry the decision screen calls.
 *
 * <p><strong>What must not move.</strong> `interactResult` stays a `@JvmStatic` on this class, and
 * the three `DECISION_INTENT_*` names stay `const val`s, because `MemorizingActivity` and
 * `UiTulkkiPorts` read them from Java and Kotlin respectively. `deleteCertificate` declares its
 * checked exception through `@Throws`, or `SecuritySettingsFragment`'s catch would not compile.
 * [DecisionScreenPort] stays nested, because `:app` names it. The two private nested trust managers
 * became top-level `internal` classes: they are returned as `X509TrustManager`, so no Java caller
 * ever named them, and their Java names carried nothing.
 *
 * <p>Two pieces of dead Java state are not ported and are recorded here: `foregroundAct` (read by
 * `getUI`, never assigned anywhere in the tree) and `notificationManager` (assigned, never read).
 */
class MemorizingTrustManager internal constructor(
        context: Context,
        private val defaultTrustManager: X509TrustManager?,
        private val trustPort: TrustPort,
        private val decisionScreen: DecisionScreenPort
) {

    private val master: Context
    private val masterHandler: Handler
    private val keyStore: MtmKeyStore
    private val certificateText: MtmCertificateText
    private val daneVerifier = DaneVerifier()
    private val trustDecision: MtmTrustDecision

    init {
        master = context
        masterHandler = Handler(context.getMainLooper())
        val app = applicationOf(context)
        val dir = app.getDir(KEYSTORE_DIR, Context.MODE_PRIVATE)
        keyStore = MtmKeyStore(File(dir.toString() + File.separator + KEYSTORE_FILE))
        certificateText = MtmCertificateText(master, trustPort)
        trustDecision = MtmTrustDecision(keyStore, defaultTrustManager, daneVerifier) {
                chain,
                authType,
                cause ->
            interactCert(chain, authType, cause)
        }
    }

    /**
     * Creates an instance using the system X509TrustManager, or the bundled Let's Encrypt root on
     * the platforms that need it.
     */
    constructor(context: Context, trustPort: TrustPort, decisionScreen: DecisionScreenPort)
            : this(context, systemDefaultTrustManager(context, trustPort), trustPort, decisionScreen)

    /**
     * Tulkki: the memorized-decision screen, in island vocabulary. `interact` opens
     * `uk.xa0.tulkki.ui.MemorizingActivity` and blocks on the answer; the island may not name that
     * class, so the composition root implements this port over it. Every parameter is what the
     * intent used to carry.
     */
    interface DecisionScreenPort {

        fun open(context: Context, decisionUri: String, decisionId: Int, message: String, titleId: Int)
    }

    /** Get a list of all certificate aliases stored in MTM. */
    fun getCertificates(): Enumeration<String> = keyStore.getCertificates()

    /**
     * Removes the given certificate from MTM's key store.
     *
     * <p><b>WARNING</b>: this does not immediately invalidate the certificate. It is well possible
     * that (a) data is transmitted over still existing connections or (b) new connections are
     * created using TLS renegotiation, without a new cert check.
     */
    @Throws(KeyStoreException::class)
    fun deleteCertificate(alias: String) {
        keyStore.deleteCertificate(alias)
    }

    fun getNonInteractive(
            domain: String?,
            verifiedHostname: String?,
            port: Int,
            daneCb: Consumer<Boolean>?,
            enforceDane: Boolean
    ): X509TrustManager =
            MtmNonInteractiveTrustManager(
                    trustDecision, domain, verifiedHostname, port, daneCb, enforceDane)

    fun getInteractive(
            domain: String?,
            verifiedHostname: String?,
            port: Int,
            daneCb: Consumer<Boolean>?,
            enforceDane: Boolean
    ): X509TrustManager =
            MtmInteractiveTrustManager(
                    trustDecision, domain, verifiedHostname, port, daneCb, enforceDane)

    fun getNonInteractive(): X509TrustManager =
            MtmNonInteractiveTrustManager(trustDecision, null, null, 0, null, false)

    fun getInteractive(): X509TrustManager =
            MtmInteractiveTrustManager(trustDecision, null, null, 0, null, false)

    private fun interactCert(
            chain: Array<X509Certificate>,
            authType: String?,
            cause: CertificateException
    ) {
        when (MtmDecisions.interact(
                masterHandler,
                decisionScreen,
                getUI(),
                certificateText.certChainMessage(chain, cause),
                R.string.mtm_accept_cert)) {
            MtmDecisions.DECISION_ALWAYS -> storeCert(chain[0]) // only the server cert, not the chain
            MtmDecisions.DECISION_ONCE -> {}
            else -> throw cause
        }
    }

    private fun storeCert(cert: X509Certificate) {
        keyStore.storeCert(cert)
    }

    /** Returns the Context of the currently bound UI, or the master context if none is bound. */
    private fun getUI(): Context = master

    companion object {

        internal val LOGGER: Logger = Logger.getLogger(MemorizingTrustManager::class.java.name)

        private const val DECISION_INTENT = "de.duenndns.ssl.DECISION"
        const val DECISION_INTENT_ID = DECISION_INTENT + ".decisionId"
        const val DECISION_INTENT_CERT = DECISION_INTENT + ".cert"
        const val DECISION_TITLE_ID = DECISION_INTENT + ".titleId"

        private const val KEYSTORE_DIR = "KeyStore"
        private const val KEYSTORE_FILE = "KeyStore.bks"

        /** Called by the decision screen with the owner's answer; it wakes the waiting socket. */
        @JvmStatic
        fun interactResult(decisionId: Int, choice: Int) {
            MtmDecisions.resolve(decisionId, choice)
        }

        private fun systemDefaultTrustManager(context: Context, trustPort: TrustPort):
                X509TrustManager =
                try {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                        trustPort.defaultWithBundledLetsEncrypt(context)
                    } else {
                        trustPort.defaultTrustManager()
                    }
                } catch (e: NoSuchAlgorithmException) {
                    throw RuntimeException(e)
                } catch (e: KeyStoreException) {
                    throw RuntimeException(e)
                } catch (e: CertificateException) {
                    throw RuntimeException(e)
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }

        private fun applicationOf(context: Context): Application =
                when (context) {
                    is Application -> context
                    is Service -> context.getApplication()
                    is AppCompatActivity -> context.getApplication()
                    else ->
                        throw ClassCastException(
                                "MemorizingTrustManager context must be either Activity or Service!")
                }
    }
}
