package uk.xa0.tulkki.xmpp.jingle

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.CallIntegrationPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * One live RTP session as `:ui` sees it, implemented by `JingleRtpConnection` and
 * `JingleConnectionManager.RtpSessionProposal`.
 *
 * Ported from Java by lane `C`. Decision taken rather than inherited: **`getMedia` answers
 * `MutableSet`**, the module's spelling for a Java `Set` return, so a Kotlin caller may hand it on
 * to the `MutableSet` consumers on the same path. Both Java implementers keep their
 * `public Set<Media> getMedia()`; the descriptor is `java.util.Set` either way.
 */
interface OngoingRtpSession {
    fun getAccount(): AccountRef

    fun getWith(): Jid

    fun getSessionId(): String

    fun getCallIntegration(): CallIntegrationPort

    fun getMedia(): MutableSet<Media>
}
