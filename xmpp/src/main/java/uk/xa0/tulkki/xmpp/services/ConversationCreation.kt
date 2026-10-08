package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.concurrent.Executor

/**
 * Tulkki: finding or creating a conversation, and the room registration pair, lifted out of
 * `XmppConnectionService`.
 *
 * The chunk owns no field. Two values travel in: the private `conversationList` (C29), whose lock
 * and `add` the Java took while it held the monitor, and C02's private `mDatabaseReaderExecutor`,
 * which carries the deferred post-processing. Everything else is a public service member — `find`,
 * the public `databaseBackend` slot, the public static `dataStatics()` and `updateConversationUi` —
 * or an earlier Kotlin home reached directly: C31c's `ConversationRestore.restoreFromArchive` and
 * `ConversationRestore.postProcessConversation`.
 *
 * The Java's order is kept, including the four overloads' nesting and the `find`-before-database
 * short-circuit. `maybeRegisterWithMuc` keeps the Java's own inner-lambda bug — the reply closure
 * compares `response`, the *outer* stanza, not `response2` — and its stream test becomes the
 * equivalent `any` short-circuit. `nickArg` and `password` stay nullable where the Java tested them.
 */
object ConversationCreation {

    @JvmStatic
    fun maybeRegisterWithMuc(service: XmppConnectionService, c: ConversationRef, nickArg: String?) {
        val jid = c.getJid() ?: throw NullPointerException("conversation has no jid")
        val account =
            c.getAccount() ?: throw NullPointerException("conversation has no account")
        val nick =
            if (nickArg == null) {
                (c.getMucOptions().getSelf().getFullJid()
                    ?: throw NullPointerException("muc self has no full jid"))
                    .getResource()
            } else {
                nickArg
            }
        val register = Iq(Iq.Type.GET)
        register.query(Namespace.REGISTER)
        register.setTo(jid.asBareJid())
        service.sendIqPacket(account, register) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val query = response.query(Namespace.REGISTER)
                var username = query.findChildContent("username", Namespace.REGISTER)
                if (username == null) username = query.findChildContent("nick", Namespace.REGISTER)
                if (username != null && username == nick) {
                    // Already registered with this nick, done
                    Log.d(
                        Config.LOGTAG,
                        "Already registered with " + jid.asBareJid() + " as " + username,
                    )
                    return@sendIqPacket
                }
                var form = Data.parse(query.findChild("x", Namespace.DATA))
                if (form != null) {
                    val field = form.getFieldByName("muc#register_roomnick")
                    if (field != null && nick == field.getValue()) {
                        Log.d(
                            Config.LOGTAG,
                            "Already registered with " +
                                jid.asBareJid() +
                                " as " +
                                field.getValue(),
                        )
                        return@sendIqPacket
                    }
                }
                if (form == null ||
                    "form" != form.getFormType() ||
                    !form.getFields().any {
                        it.isRequired() && "muc#register_roomnick" != it.getFieldName()
                    }
                ) {
                    // No form, result form, or no required fields other than nickname, let's just
                    // send nickname
                    if (form == null || "form" != form.getFormType()) {
                        form = Data()
                        form.put("FORM_TYPE", "http://jabber.org/protocol/muc#register")
                    }
                    form.put("muc#register_roomnick", nick)
                    form.submit()
                    val finish = Iq(Iq.Type.SET)
                    finish.query(Namespace.REGISTER).addChild(form)
                    finish.setTo(jid.asBareJid())
                    service.sendIqPacket(account, finish) { response2 ->
                        if (response.getType() == Iq.Type.RESULT) {
                            Log.w(
                                Config.LOGTAG,
                                "Success registering with channel " +
                                    jid.asBareJid() +
                                    "/" +
                                    nick,
                            )
                        } else {
                            Log.w(Config.LOGTAG, "Error registering with channel: " + response2)
                        }
                    }
                } else {
                    // TODO: offer registration form to user
                    Log.d(
                        Config.LOGTAG,
                        "Complex registration form for " + jid.asBareJid() + ": " + response,
                    )
                }
            } else {
                // We said maybe. Guess not
                Log.d(
                    Config.LOGTAG,
                    "Could not register with " + jid.asBareJid() + ": " + response,
                )
            }
        }
    }

    @JvmStatic
    fun deregisterWithMuc(service: XmppConnectionService, c: ConversationRef) {
        val jid = c.getJid() ?: throw NullPointerException("conversation has no jid")
        val account =
            c.getAccount() ?: throw NullPointerException("conversation has no account")
        val register = Iq(Iq.Type.GET)
        register.query(Namespace.REGISTER).addChild("remove")
        register.setTo(jid.asBareJid())
        service.sendIqPacket(account, register) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, "deregistered with " + jid.asBareJid())
            } else {
                Log.w(
                    Config.LOGTAG,
                    "Could not deregister with " + jid.asBareJid() + ": " + response,
                )
            }
        }
    }

    @JvmStatic
    fun findOrCreateConversation(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        muc: Boolean,
        async: Boolean,
        conversationList: MutableList<ConversationRef>,
        databaseReaderExecutor: Executor,
    ): ConversationRef =
        findOrCreateConversation(
            service,
            account,
            jid,
            muc,
            false,
            async,
            conversationList,
            databaseReaderExecutor,
        )

    @JvmStatic
    fun findOrCreateConversation(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        muc: Boolean,
        joinAfterCreate: Boolean,
        async: Boolean,
        conversationList: MutableList<ConversationRef>,
        databaseReaderExecutor: Executor,
    ): ConversationRef =
        findOrCreateConversation(
            service,
            account,
            jid,
            muc,
            joinAfterCreate,
            null,
            async,
            conversationList,
            databaseReaderExecutor,
        )

    @JvmStatic
    fun findOrCreateConversation(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        muc: Boolean,
        joinAfterCreate: Boolean,
        query: MessageArchiveService.Query?,
        async: Boolean,
        conversationList: MutableList<ConversationRef>,
        databaseReaderExecutor: Executor,
    ): ConversationRef =
        findOrCreateConversation(
            service,
            account,
            jid,
            muc,
            joinAfterCreate,
            query,
            async,
            null,
            conversationList,
            databaseReaderExecutor,
        )

    @JvmStatic
    fun findOrCreateConversation(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        muc: Boolean,
        joinAfterCreate: Boolean,
        query: MessageArchiveService.Query?,
        async: Boolean,
        password: String?,
        conversationList: MutableList<ConversationRef>,
        databaseReaderExecutor: Executor,
    ): ConversationRef {
        val dataStatics = XmppConnectionService.dataStatics()
        synchronized(conversationList) {
            val cached = service.find(account, jid)
            if (cached != null) {
                return cached
            }
            val existing = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).findConversation(account, jid)
            val conversation: ConversationRef
            val loadMessagesFromDb: Boolean
            if (existing != null) {
                conversation = existing
                if (password != null) conversation.getMucOptions().setPassword(password)
                loadMessagesFromDb = ConversationRestore.restoreFromArchive(service, conversation, jid, muc)
            } else {
                val contact: ContactRef? = account.getRoster().getContact(jid)
                val conversationName: String =
                    if (contact != null) {
                        contact.getDisplayName()
                    } else {
                        jid.getLocal() ?: throw NullPointerException()
                    }
                if (muc) {
                    conversation =
                        dataStatics.newConversation(
                            conversationName,
                            account,
                            jid,
                            ConversationalRef.MODE_MULTI,
                        )
                } else {
                    conversation =
                        dataStatics.newConversation(
                            conversationName,
                            account,
                            jid.asBareJid(),
                            ConversationalRef.MODE_SINGLE,
                        )
                }
                if (password != null) conversation.getMucOptions().setPassword(password)
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).createConversation(conversation)
                loadMessagesFromDb = false
            }
            if (async) {
                databaseReaderExecutor.execute {
                    ConversationRestore.postProcessConversation(
                        service,
                        conversation,
                        loadMessagesFromDb,
                        joinAfterCreate,
                        query,
                    )
                }
            } else {
                ConversationRestore.postProcessConversation(
                    service,
                    conversation,
                    loadMessagesFromDb,
                    joinAfterCreate,
                    query,
                )
            }
            conversationList.add(conversation)
            service.updateConversationUi()
            return conversation
        }
    }
}
