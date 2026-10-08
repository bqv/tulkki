package uk.xa0.tulkki.xmpp.services

import android.util.Log
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Maps
import java.util.concurrent.Executor
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * Tulkki: the reliable uuid lookup, the archive restore and the post-processing step, lifted out of
 * `XmppConnectionService`.
 *
 * The public lookup and the three privates it drives are the whole chunk. Three things travel in
 * rather than having a visibility widened, and none of them is state this object could own:
 *
 *  * the **database-reader executor** (chunk `C02`), which is where the post-processing runs;
 *  * the **live conversation list** (chunk `C29`), which is written with `getConversationList()`
 *    exactly as the Java wrote `this.conversationList.add(...)`; and
 *  * nothing else — `ensureBookmarkIsAutoJoin` is already public (chunk `C26b`) and the archive
 *    service is read through the service's own public `getMessageArchiveService()`.
 *
 * The `ImmutableMap.copyOf(Maps.uniqueIndex(...))` head is kept as-is: the doc's trap says the
 * immutability, and `uniqueIndex`'s refusal on a duplicate account uuid, are part of the contract.
 * Guard order is the Java's: the cache, then the row, then the account, then the restore and the
 * deferred post-processing.
 */
object ConversationRestore {

    @JvmStatic
    fun findConversationByUuidReliable(
        service: XmppConnectionService,
        uuid: String,
        reader: Executor,
    ): ConversationRef? {
        val cached = service.findConversationByUuid(uuid)
        if (cached != null) {
            return cached
        }
        val existing = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).findConversation(uuid)
        if (existing == null) {
            return null
        }
        Log.d(Config.LOGTAG, "restoring conversation with " + existing.getJid() + " from DB")
        val accounts: ImmutableMap<String, AccountRef> =
            ImmutableMap.copyOf(
                Maps.uniqueIndex(
                    XmppConnectionService.dataStatics().accounts().getAccounts(),
                ) { account -> account.getUuid() }
            )
        val account = accounts[existing.getAccountUuid()]
        if (account == null) {
            Log.d(Config.LOGTAG, "could not find account " + existing.getAccountUuid())
            return null
        }
        existing.setAccount(account)
        val loadMessagesFromDb = restoreFromArchive(service, existing)
        reader.execute {
            postProcessConversation(
                service,
                existing,
                loadMessagesFromDb,
                existing.getMode() == ConversationalRef.MODE_MULTI,
                null,
            )
        }
        service.getConversationList().add(existing)
        if (existing.getMode() == ConversationalRef.MODE_MULTI) {
            service.ensureBookmarkIsAutoJoin(existing)
        }
        service.updateConversationUi()
        return existing
    }

    /** Sets the mode and the contact JID, then stores the row through the one-argument form. */
    @JvmStatic
    fun restoreFromArchive(
        service: XmppConnectionService,
        conversation: ConversationRef,
        jid: Jid,
        muc: Boolean,
    ): Boolean {
        if (muc) {
            conversation.setMode(ConversationalRef.MODE_MULTI)
            conversation.setContactJid(jid)
        } else {
            conversation.setMode(ConversationalRef.MODE_SINGLE)
            conversation.setContactJid(jid.asBareJid())
        }
        return restoreFromArchive(service, conversation)
    }

    /** Marks the row available, writes it and answers whether the messages still load. */
    @JvmStatic
    fun restoreFromArchive(
        service: XmppConnectionService,
        conversation: ConversationRef,
    ): Boolean {
        conversation.setStatus(ConversationRef.STATUS_AVAILABLE)
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
        return conversation.messagesLoaded().compareAndSet(true, false)
    }

    /**
     * Loads the first page when the restore asked for it, queries the archive when the account can,
     * and joins the room last. `query` is the caller's window: a null one starts a fresh query, a
     * window with no conversation starts one for the same span and catch-up setting.
     */
    @JvmStatic
    fun postProcessConversation(
        service: XmppConnectionService,
        c: ConversationRef,
        loadMessagesFromDb: Boolean,
        joinAfterCreate: Boolean,
        query: MessageArchiveService.Query?,
    ) {
        val singleMode = c.getMode() == ConversationalRef.MODE_SINGLE
        val account =
            c.getAccount() ?: throw NullPointerException("conversation has no account")
        if (loadMessagesFromDb) {
            c.addAll(0, (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessages(c, Config.PAGE_SIZE), false)
            service.updateConversationUi()
            c.messagesLoaded().set(true)
        }
        val connection = account.getXmppConnection()
        if (connection != null
            && !c.getContact().isBlocked()
            && connection.getFeatures().mam()
            && singleMode
        ) {
            if (query == null) {
                service.getMessageArchiveService().query(c)
            } else {
                if (query.getConversation() == null) {
                    service.getMessageArchiveService().query(c, query.getStart(), query.isCatchup())
                }
            }
        }
        if (joinAfterCreate) {
            service.joinMuc(c)
        }
    }
}
