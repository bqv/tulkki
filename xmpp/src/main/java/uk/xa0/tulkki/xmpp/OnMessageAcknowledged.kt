package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the wire layer asking its host to mark a message acknowledged.
 *
 * Ported from `OnMessageAcknowledged.java`. The two
 * callers are a Java anonymous class in `XmppConnectionService` and `XmppConnection`'s
 * `acknowledgedListener` call, both of which always pass a real account, address and id, so every
 * parameter is non-null; the `boolean` answer is unchanged.
 */
interface OnMessageAcknowledged {
    fun onMessageAcknowledged(account: AccountRef, to: Jid, id: String): Boolean
}
