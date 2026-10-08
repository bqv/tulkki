package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.mam.MamAbort
import uk.xa0.tulkki.xmpp.mam.MamFin
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.MessageArchiveService.PagingOrder
import uk.xa0.tulkki.xmpp.services.MessageArchiveService.Query

/**
 * One MAM query's whole life: minting it from a conversation or an account anchor, sending it,
 * reading its `<fin>`, walking the next RSM page, finalising it, and killing it.
 *
 * <p>It owns no state of its own. The two collections it works through - `queries` and
 * `pendingQueries`, each with its own monitor - stay on [MessageArchiveService], because catch-up
 * policy reads and prunes the same sets; this class is the engine that moves one query along them.
 * Every `==` the Java original wrote on `AccountRef`/`ConversationRef` was reference identity, so
 * every one here is `===`.
 *
 * <p>What it reports, it reports exactly as before: a timeout, an error and a kill reach
 * [MessageArchiveService.syncEvents] through [abortCatchup], and every `<fin>` through the
 * [mamFin] snapshot, so a region's completeness is the ledger's answer rather than an inference
 * from which queries happen to be in flight.
 */
internal class MamPaging(private val owner: MessageArchiveService) {

    private val service: XmppConnectionService
        get() = owner.mXmppConnectionService

    // ------------------------------------------------------------------ the query factory

    fun query(conversation: ConversationRef): Query? =
            if (conversation.getLastMessageTransmitted().getTimestamp() < 0
                    && conversation.countMessages() == 0) {
                query(conversation, MamReference(0), System.currentTimeMillis(), false)
            } else {
                query(
                        conversation,
                        conversation.getLastMessageTransmitted(),
                        ((conversation.getAccount()
                                ?: throw NullPointerException("conversation has no account"))
                            .getXmppConnection()
                            ?: throw NullPointerException("account has no connection"))
                            .getLastSessionEstablished(),
                        false)
            }

    fun query(conversation: ConversationRef, end: Long, allowCatchup: Boolean): Query? =
            query(conversation, conversation.getLastMessageTransmitted(), end, allowCatchup)

    fun query(
            conversation: ConversationRef,
            start: MamReference,
            end: Long,
            allowCatchup: Boolean
    ): Query? {
        synchronized(owner.queries) {
            val query: Query
            if (start.getTimestamp() == 0L) {
                query = Query(conversation, start, end, false)
                query.setReference(conversation.getFirstMamReference())
            } else {
                if (allowCatchup) {
                    val maxCatchup = MamReference.max(
                            start,
                            System.currentTimeMillis() - Config.MAM_MAX_CATCHUP)
                    if (maxCatchup.greaterThan(start)) {
                        val reverseCatchup =
                                Query(conversation, start, maxCatchup.getTimestamp(), false)
                        owner.queries.add(reverseCatchup)
                        execute(reverseCatchup)
                    }
                    query = Query(conversation, maxCatchup, end, true)
                } else {
                    query = Query(conversation, start, end, false)
                }
            }
            if (end != 0L && start.greaterThan(end)) {
                return null
            }
            owner.queries.add(query)
            execute(query)
            return query
        }
    }

    // ------------------------------------------------------------------ execution

    internal fun execute(query: Query) {
        val account = query.getAccount()
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            val conversation = query.getConversation()
            if (conversation != null && conversation.getStatus() == ConversationRef.STATUS_ARCHIVED) {
                throw IllegalStateException("Attempted to run MAM query for archived conversation")
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": running mam query " + query.toString())
            val packet = service.getIqGenerator().queryMessageArchiveManagement(query)
            service.sendIqPacket(account, packet) { p ->
                val fin = p.findChild("fin", query.version.namespace)
                if (p.getType() == Iq.Type.TIMEOUT) {
                    synchronized(owner.queries) {
                        owner.queries.remove(query)
                        if (query.hasCallback()) {
                            query.callback(false)
                        }
                    }
                    // Tulkki: a catch-up that timed out is a region the ledger cannot prove, so it is
                    // recorded DEGRADED rather than vanishing with the query.
                    abortCatchup(query, MamAbort.TIMEOUT)
                } else if (p.getType() == Iq.Type.RESULT && fin != null) {
                    val running: Boolean
                    synchronized(owner.queries) {
                        running = owner.queries.contains(query)
                    }
                    if (running) {
                        processFin(query, fin)
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString()
                                        + ": ignoring MAM iq result because query had been killed")
                    }
                } else if (p.getType() == Iq.Type.RESULT && query.isLegacy()) {
                    // do nothing
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString()
                                    + ": error executing mam: " + p.toString())
                    abortCatchup(query, MamAbort.ERROR)
                    try {
                        finalizeQuery(query, true)
                    } catch (e: IllegalStateException) {
                        // ignored
                    }
                }
            }
        } else {
            synchronized(owner.pendingQueries) {
                owner.pendingQueries.add(query)
            }
        }
    }

    fun executePendingQueries(account: AccountRef) {
        val pending = ArrayList<Query>()
        synchronized(owner.pendingQueries) {
            val iterator = owner.pendingQueries.iterator()
            while (iterator.hasNext()) {
                val query = iterator.next()
                if (query.getAccount() === account) {
                    pending.add(query)
                    iterator.remove()
                }
            }
        }
        for (query in pending) {
            execute(query)
        }
    }

    // ------------------------------------------------------------------ the `<fin>`

    fun processFinLegacy(fin: Element, from: Jid) {
        val query = findQuery(fin.getAttribute("queryid"))
        if (query != null && query.validFrom(from)) {
            processFin(query, fin)
        }
    }

    private fun processFin(query: Query, fin: Element) {
        val complete = fin.getAttributeAsBoolean("complete")
        val set = fin.findChild("set", "http://jabber.org/protocol/rsm")
        val last = set?.findChild("last")
        val count = set?.findChildContent("count")
        val first = set?.findChild("first")
        val relevant = if (query.getPagingOrder() == PagingOrder.NORMAL) last else first
        val abort = (!query.isCatchup() && query.getTotalCount() >= Config.PAGE_SIZE)
                || query.getTotalCount() >= Config.MAM_MAX_MESSAGES
        val conversation = query.getConversation()
        if (conversation != null) {
            conversation.setFirstMamReference(first?.getContent())
        }
        if (complete || relevant == null || abort) {
            var done: Boolean
            if (query.isCatchup()) {
                done = false
            } else {
                if (count != null) {
                    done = try {
                        count.toInt() <= query.getTotalCount()
                    } catch (e: NumberFormatException) {
                        false
                    }
                } else {
                    done = query.getTotalCount() == 0
                }
            }
            done = done || (query.getActualMessageCount() == 0 && !query.isCatchup())
            finalizeQuery(query, done)
            Log.d(
                    Config.LOGTAG,
                    query.getAccount().getJid().asBareJid().toString()
                            + ": finished mam after " + query.getTotalCount() + "("
                            + query.getActualMessageCount() + ") messages. messages left=" + !done
                            + " count=" + count)
            if (query.isCatchup() && query.getActualMessageCount() > 0) {
                service.getNotificationService().finishBacklog(true, query.getAccount())
            }
            if (query.isCatchup()
                    && query.getPagingOrder() == PagingOrder.NORMAL
                    && !complete
                    && conversation != null) {
                // Going forward we stopped without completing due to limits, so we do not have the
                // most recent messages yet.
                synchronized(owner.queries) {
                    val q = Query(
                            conversation,
                            MamReference(System.currentTimeMillis() - Config.MAM_MIN_CATCHUP),
                            0,
                            true,
                            PagingOrder.REVERSE)
                    owner.queries.add(q)
                    execute(q)
                }
            }
            processPostponed(query)
        } else {
            val nextQuery: Query
            if (query.getPagingOrder() == PagingOrder.NORMAL) {
                nextQuery = query.next(last?.getContent())
            } else {
                nextQuery = query.prev(first?.getContent())
            }
            execute(nextQuery)
            finalizeQuery(query, false)
            synchronized(owner.queries) {
                owner.queries.add(nextQuery)
            }
        }
        // Tulkki: every `<fin>` is reported to the sync engine, which matches it to its own region.
        // Nothing here is gated on `isCatchup()` - the reverse region's query is still a region.
        owner.syncEvents?.onMamFin(mamFin(query, complete, first, last, count))
    }

    /**
     * Tulkki: one `<fin>` as the immutable snapshot the sync engine reads, so the engine never
     * touches [Query] - it is mutable and a page rewrites it as the paging walks on.
     */
    private fun mamFin(
            query: Query,
            complete: Boolean,
            first: Element?,
            last: Element?,
            count: String?
    ): MamFin {
        var parsed: Int? = null
        if (count != null) {
            parsed = try {
                Integer.valueOf(count)
            } catch (e: NumberFormatException) {
                null
            }
        }
        return MamFin(
                query.getStart(),
                query.getEnd(),
                query.getReference(),
                query.getTotalCount(),
                query.getActualMessageCount(),
                complete,
                if (first == null) null else MamReference.fromAttribute(first.getContent()),
                if (last == null) null else MamReference.fromAttribute(last.getContent()),
                parsed,
                query.getPagingOrder(),
                query.isCatchup(),
                query.getAccount(),
                query.getConversation())
    }

    /**
     * Tulkki: report a catch-up that reached no `<fin>` - a timeout, an error or a kill - so the
     * ledger can record its region DEGRADED. The event carries no region, so it is reported only
     * for a catch-up query.
     */
    private fun abortCatchup(query: Query, reason: MamAbort) {
        val events = owner.syncEvents
        if (events != null && query.isCatchup()) {
            events.onMamAborted(query.getAccount(), reason)
        }
    }

    // ------------------------------------------------------------------ finalise and kill

    private fun finalizeQuery(query: Query, done: Boolean) {
        synchronized(owner.queries) {
            if (!owner.queries.remove(query)) {
                throw IllegalStateException("Unable to remove query from queries")
            }
        }
        val conversation = query.getConversation()
        if (conversation != null) {
            conversation.sort()
            conversation.setHasMessagesLeftOnServer(!done)
            val displayState = conversation.getDisplayState()
            if (displayState != null) {
                service.markReadUpToStanzaId(conversation, displayState)
            }
        } else {
            for (tmp in service.getConversationList()) {
                if (tmp.getAccount() === query.getAccount()) {
                    tmp.sort()
                    val displayState = tmp.getDisplayState()
                    if (displayState != null) {
                        service.markReadUpToStanzaId(tmp, displayState)
                    }
                }
            }
        }
        if (query.hasCallback()) {
            query.callback(done)
        } else {
            service.updateConversationUi()
        }
    }

    fun kill(conversation: ConversationRef) {
        val toBeKilled = ArrayList<Query>()
        synchronized(owner.pendingQueries) {
            val iterator = owner.pendingQueries.iterator()
            while (iterator.hasNext()) {
                val query = iterator.next()
                if (query.getConversation() === conversation) {
                    iterator.remove()
                    Log.d(
                            Config.LOGTAG,
                            (conversation.getAccount()
                                    ?: throw NullPointerException("conversation has no account"))
                                .getJid().asBareJid().toString()
                                    + ": killed pending MAM query for archived conversation")
                }
            }
        }
        synchronized(owner.queries) {
            for (q in owner.queries) {
                if (q.getConversation() === conversation) {
                    toBeKilled.add(q)
                }
            }
        }
        for (q in toBeKilled) {
            kill(q)
        }
    }

    private fun kill(query: Query) {
        Log.d(
                Config.LOGTAG,
                query.getAccount().getJid().asBareJid().toString() + ": killing mam query prematurely")
        // Tulkki: the third path that reaches no trigger.
        abortCatchup(query, MamAbort.KILLED)
        query.setCallback(null)
        finalizeQuery(query, false)
        if (query.isCatchup() && query.getActualMessageCount() > 0) {
            service.getNotificationService().finishBacklog(true, query.getAccount())
        }
        processPostponed(query)
    }

    private fun processPostponed(query: Query) =
            MamPostponedReceipts.flush(service, query)

    // ------------------------------------------------------------------ lookups

    fun findQuery(id: String?): Query? {
        if (id == null) {
            return null
        }
        synchronized(owner.queries) {
            for (query in owner.queries) {
                if (query.getQueryId() == id) {
                    return query
                }
            }
            return null
        }
    }

    fun queryInProgress(
            conversation: ConversationRef,
            callback: OnMoreMessagesLoaded?
    ): Boolean {
        synchronized(owner.queries) {
            for (query in owner.queries) {
                if (query.getConversation() === conversation) {
                    if (!query.hasCallback() && callback != null) {
                        query.setCallback(callback)
                    }
                    return true
                }
            }
            return false
        }
    }
}
