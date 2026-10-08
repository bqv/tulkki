package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService.Query

/**
 * The catch-up policy: when a stream feature or a room join earns an archive pass, how far back it
 * reaches, and which in-flight queries answer the three "is a catch-up running?" questions other
 * island code asks.
 *
 * <p>It owns no state of its own. The query sets stay on [MessageArchiveService], and the pass
 * itself is handed to [MamPaging] once the decision is made - this class is the decision.
 *
 * <p>The two sync seams, [MessageArchiveService.syncAnchors] and [MessageArchiveService.syncEvents],
 * are installed by the composition root. When either was never installed the original logged rather
 * than threw, and that is kept: a build fault must not take the connection down, but it must be
 * visible.
 */
internal class MamCatchup(
        private val owner: MessageArchiveService,
        private val paging: MamPaging
) {

    private val service: XmppConnectionService
        get() = owner.mXmppConnectionService

    fun onAdvancedStreamFeaturesAvailable(account: AccountRef) {
        val connection = account.getXmppConnection()
        if (connection != null && connection.getFeatures().mam()) {
            catchup(account)
        }
    }

    fun catchup(account: AccountRef) {
        synchronized(owner.queries) {
            val iterator = owner.queries.iterator()
            while (iterator.hasNext()) {
                val query = iterator.next()
                if (query.getAccount() === account) {
                    iterator.remove()
                }
            }
        }
        val endCatchup = (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).getLastSessionEstablished()
        val anchors = owner.syncAnchors
        val events = owner.syncEvents
        if (anchors == null || events == null) {
            // A build fault, not a runtime state: the composition root installs both in the
            // service's own onCreate. Named here rather than passing as "nothing was missed".
            Log.e(
                    Config.LOGTAG,
                    "Tulkki's sync seams were never installed; the catch-up will not run")
            return
        }
        // The anchor is asked first, because asking it is also what gives an account the migration
        // never seeded its cursor row; the session event then enumerates the ledger from that cursor.
        val mamReference = anchors.anchorFor(account)
        events.onSessionEstablished(account, endCatchup, false)
        val query: Query
        if (mamReference.getTimestamp() == 0L) {
            return
        } else if (endCatchup - mamReference.getTimestamp() >= Config.MAM_MAX_CATCHUP) {
            val startCatchup = endCatchup - Config.MAM_MAX_CATCHUP
            for (conversation in service.getConversationList()) {
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE
                        && conversation.getAccount() === account
                        && startCatchup > anchors.anchorFor(conversation).getTimestamp()) {
                    paging.query(conversation, startCatchup, true)
                }
            }
            query = Query(account, MamReference(startCatchup), 0)
        } else {
            query = Query(account, mamReference, 0)
        }
        synchronized(owner.queries) {
            owner.queries.add(query)
        }
        paging.execute(query)
    }

    fun catchupMUC(conversation: ConversationRef) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0
                && conversation.countMessages() == 0) {
            paging.query(conversation, MamReference(0), 0, true)
        } else {
            paging.query(conversation, conversation.getLastMessageTransmitted(), 0, true)
        }
    }

    fun isCatchingUp(conversation: ConversationRef): Boolean {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        if ((account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).isWaitingForSmCatchup()) {
            return true
        }
        synchronized(owner.queries) {
            for (query in owner.queries) {
                if (query.getAccount() === account
                        && query.isCatchup()
                        && ((conversation.getMode() == ConversationalRef.MODE_SINGLE
                                && query.getWith() == null)
                                || query.getConversation() === conversation)) {
                    return true
                }
            }
        }
        return false
    }

    fun inCatchup(account: AccountRef): Boolean {
        synchronized(owner.queries) {
            for (query in owner.queries) {
                if (query.getAccount() === account
                        && query.isCatchup()
                        && query.getWith() == null) {
                    return true
                }
            }
        }
        return false
    }

    fun isCatchupInProgress(conversation: ConversationRef): Boolean {
        synchronized(owner.queries) {
            for (query in owner.queries) {
                if (query.getAccount() === conversation.getAccount() && query.isCatchup()) {
                    val with = query.getWith()?.asBareJid()
                    if ((conversation.getMode() == ConversationalRef.MODE_SINGLE && with == null)
                            || (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid().equals(with)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
