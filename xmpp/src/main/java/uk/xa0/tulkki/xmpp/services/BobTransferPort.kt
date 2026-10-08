package uk.xa0.tulkki.xmpp.services

import android.net.Uri
import io.ipfs.cid.Cid
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the BOB (XEP-0231) helpers.
 *
 * <p>{@link #attachTo} is the whole of {@code MessageParser}'s second use: it builds the
 * {@code BobTransfer.ForMessage} over the message and starts it. It is one method rather than a
 * factory returning a transferable because the transferable's type is `:app`'s and the island
 * may not name it - the assignment to the message happens on the other side of the boundary.
 */
interface BobTransferPort {

    fun cid(uri: Uri): Cid?

    fun cid(bobCid: String): Cid?

    fun attachTo(message: MessageRef, service: XmppConnectionService)
}
