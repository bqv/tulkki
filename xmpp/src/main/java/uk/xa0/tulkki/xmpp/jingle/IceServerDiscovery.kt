package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import java.util.Collections
import org.webrtc.PeerConnection
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.disco.external.Services
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: XEP-0215 external service discovery, out of `JingleRtpConnection`.
 *
 * The Java held `discoverIceServers` as a private method reading the outer `id.account` and the
 * outer `xmppConnectionService`, plus the private nested `OnIceServersDiscovered` it took. Both are
 * parameters now: the second and third of the top-level function, so the connection's two call sites
 * become `discoverIceServers(id.account, xmppConnectionService, iceServers -> ...)`.
 *
 * `internal` is the narrowest Kotlin spelling a same-module Java caller can name for what Java
 * declared private nested; `@JvmName` keeps the plain JVM name `discoverIceServers` so the Java
 * resolves it rather than the mangled `discoverIceServers$xmpp`.
 *
 * The two log lines keep Java's `"" + jid` concatenation (the Java compiled them through
 * `String.valueOf`), and the "no server found" branch still passes the parse's answer on untouched.
 */
internal fun interface OnIceServersDiscovered {
    fun onIceServersDiscovered(iceServers: Collection<PeerConnection.IceServer>)
}

@JvmName("discoverIceServers")
internal fun discoverIceServers(
    account: AccountRef,
    xmppConnectionService: XmppConnectionService,
    onIceServersDiscovered: OnIceServersDiscovered,
) {
    if ((account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).getFeatures().externalServiceDiscovery()) {
        val request = Iq(Iq.Type.GET)
        request.setTo(account.getDomain())
        request.addExtension(Services())
        xmppConnectionService.sendIqPacket(account, request) { response ->
            val iceServers = IceServers.parse(response)
            if (iceServers.isEmpty()) {
                Log.w(
                    Config.LOGTAG,
                    "" + account.getJid().asBareJid() + ": no ICE server found " + response,
                )
            }
            onIceServersDiscovered.onIceServersDiscovered(iceServers)
        }
    } else {
        Log.w(
            Config.LOGTAG,
            "" + account.getJid().asBareJid() + ": has no external service discovery",
        )
        onIceServersDiscovered.onIceServersDiscovered(
            Collections.emptySet<PeerConnection.IceServer>()
        )
    }
}
