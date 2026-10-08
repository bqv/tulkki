package uk.xa0.tulkki.xmpp.jingle

import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * The single-abstract-method callback `XmppConnection` fires for an incoming Jingle packet.
 *
 * Ported from Java by lane `C`. Decision taken rather than inherited: it is a **`fun interface`**,
 * because `ConnectionScheduling.kt:198` hands it a Kotlin lambda through the Java
 * `XmppConnection.setOnJinglePacketReceivedListener`, and Kotlin only converts a lambda to SAM for
 * a `fun interface` or a Java interface. The JVM shape is the plain interface Java had.
 */
fun interface OnJinglePacketReceived {
    fun onJinglePacketReceived(account: AccountRef, packet: Iq)
}
