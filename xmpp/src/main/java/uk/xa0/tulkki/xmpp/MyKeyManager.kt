package uk.xa0.tulkki.xmpp

import android.security.KeyChain
import android.util.Log
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/**
 * Tulkki: the client-certificate key manager, out of `XmppConnection`.
 *
 * The Java held this as a `private class` nested in `XmppConnection` (non-static, so it read the
 * outer `account` and `mXmppConnectionService` directly). Both are handed to the constructor now;
 * the one call site is `getSSLSocketFactory`, which built it with `new MyKeyManager()` and builds
 * it with `MyKeyManager(account, mXmppConnectionService)`.
 *
 * `internal` is the narrowest Kotlin spelling a same-module Java caller can still name, and
 * `getCertificateChain` keeps the Java's nullable answer: the Java returned
 * `KeyChain.getCertificateChain(...)`'s platform value untouched, null included.
 */
internal class MyKeyManager(
    private val account: AccountRef,
    private val service: XmppConnectionService,
) : X509KeyManager {

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = account.getPrivateKeyAlias()

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String): Array<X509Certificate>? {
        Log.d(Config.LOGTAG, "getting certificate chain")
        try {
            return KeyChain.getCertificateChain(service, alias)
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "could not get certificate chain", e)
            return emptyArray()
        }
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
        val alias = account.getPrivateKeyAlias()
        return if (alias != null) arrayOf(alias) else emptyArray()
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        emptyArray()

    override fun getPrivateKey(alias: String): PrivateKey? =
        try {
            KeyChain.getPrivateKey(service, alias)
        } catch (e: Exception) {
            null
        }
}
