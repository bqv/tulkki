package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.MessageArchiveService.Query

/**
 * The postponed-receipt flush a finished (or killed) MAM query owes.
 *
 * <p>An archived message's receipt request is held on the [Query] and answered once the query ends,
 * so the server is not asked for a receipt for history. This owns the sending; the two receipt sets
 * and their set semantics stay on [Query], which is where `ReceiptRequest.equals` is load-bearing.
 *
 * <p>It also drains the OMEMO session's own postponed queue first, because a catch-up is the moment
 * a session that was offline learns about the messages it missed.
 */
internal object MamPostponedReceipts {

    fun flush(service: XmppConnectionService, query: Query) {
        // Tulkki C5-D: the port, not the :crypto class - see OmemoSessionPort.processPostponed.
        (query.getAccount().getOmemoSession()
                ?: throw NullPointerException("account has no omemo session"))
            .processPostponed()
        val pending = query.takePostponedReceipts()
        Log.d(
                Config.LOGTAG,
                query.getAccount().getJid().asBareJid().toString() + ": found " + pending.size
                        + " pending receipt requests")
        for (receiptRequest in pending) {
            service.sendMessagePacket(
                    query.getAccount(),
                    service.getMessageGenerator().received(
                            query.getAccount(),
                            receiptRequest.getJid(),
                            receiptRequest.getId()))
        }
    }
}
