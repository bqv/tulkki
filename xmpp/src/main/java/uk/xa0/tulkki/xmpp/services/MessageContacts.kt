package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.os.IBinder
import android.util.Log
import uk.xa0.tulkki.app.generator.MessageGenerator
import uk.xa0.tulkki.app.generator.PresenceGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Namespace
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Tulkki: the binder, message and contact operations, lifted out of `XmppConnectionService`
 *.
 *
 * Every member here reaches outside itself only through public service members or through private
 * state injected on the Java side of the seam: the binder, the one-shot notification handler, the
 * message and presence generators, the jingle manager, the database-writer executor and the
 * scheduled-message map. The chunk owns no field, so nothing but the service has to travel by
 * value. `deleteFileIfUnused` reuses `ConversationHistory.deleteFilesAsync` rather than talking to
 * the private Java helper.
 */
object MessageContacts {

    @JvmStatic fun onBind(binder: IBinder): IBinder = binder

    @JvmStatic
    fun deleteMessage(
        service: XmppConnectionService,
        message: MessageRef,
        scheduledMessages: MutableMap<String, MessageRef>,
    ) {
        scheduledMessages.remove(message.getUuid())
        deleteFileIfUnused(service, message)
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteMessage(message.getUuid())
        (message.getConversation() as ConversationRef).remove(message)
        service.updateConversationUi()
    }

    @JvmStatic
    fun deleteFileIfUnused(service: XmppConnectionService, message: MessageRef) {
        if (service.getAppSettings().isDeleteUnusedFiles()) {
            val exclusivePath = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getExclusiveFilePath(message)
            if (exclusivePath != null) {
                val jid = ((message.getConversation() ?: throw NullPointerException("message has no conversation")).getJid()
                    ?: throw NullPointerException("conversation has no jid")).asBareJid().toString()
                XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute {
                    ConversationHistory.deleteFilesAsync(service, listOf(exclusivePath), jid)
                }
            }
        }
    }

    @JvmStatic
    fun updateMessageGeoPayload(
        service: XmppConnectionService,
        conversationUuid: String,
        messageUuid: String?,
        lat: Double,
        lon: Double,
    ) {
        if (messageUuid == null) {
            return
        }
        val conversation = service.findConversationByUuid(conversationUuid)
        if (conversation != null) {
            val message = conversation.findMessageWithUuid(messageUuid)
            if (message != null) {
                for (el in message.getPayloads()) {
                    if ("live-location" == el.getName() &&
                        Namespace.LIVE_LOCATION == el.getAttribute("xmlns")
                    ) {
                        el.setAttribute("last_lat", lat.toString())
                        el.setAttribute("last_lon", lon.toString())
                        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, false)
                        break
                    }
                }
            }
        }
    }

    @JvmStatic
    fun updateMessage(service: XmppConnectionService, message: MessageRef) {
        updateMessage(service, message, true)
    }

    @JvmStatic
    fun updateMessage(service: XmppConnectionService, message: MessageRef, includeBody: Boolean) {
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, includeBody)
        service.updateConversationUi()
    }

    @JvmStatic
    fun updateMessage(service: XmppConnectionService, message: MessageRef, uuid: String) {
        if (!(service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit")
        }
        service.updateConversationUi()
    }

    @JvmStatic
    fun createMessageAsync(
        service: XmppConnectionService,
        message: MessageRef,
        databaseWriterExecutor: Executor,
    ) {
        databaseWriterExecutor.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message) }
    }

    @JvmStatic
    fun syncDirtyContacts(
        service: XmppConnectionService,
        account: AccountRef,
        defaultIqHandler: Consumer<Iq>,
        presenceGenerator: PresenceGenerator,
    ) {
        for (contact in account.getRoster().getContacts()) {
            if (contact.getOption(ContactRef.OptionsRef.DIRTY_PUSH)) {
                pushContactToServer(service, contact, null, defaultIqHandler, presenceGenerator)
            }
            if (contact.getOption(ContactRef.OptionsRef.DIRTY_DELETE)) {
                service.deleteContactOnServer(contact)
            }
        }
    }

    @JvmStatic
    fun unregisterPhoneAccounts(account: AccountRef, context: Context) {
        for (contact in account.getRoster().getContacts()) {
            if (!contact.showInRoster()) {
                contact.unregisterAsPhoneAccount(context)
            }
        }
    }

    @JvmStatic
    fun createContact(
        service: XmppConnectionService,
        contact: ContactRef,
        autoGrant: Boolean,
        preAuth: String?,
        defaultIqHandler: Consumer<Iq>,
        presenceGenerator: PresenceGenerator,
    ) {
        if (autoGrant) {
            contact.setOption(ContactRef.OptionsRef.PREEMPTIVE_GRANT)
            contact.setOption(ContactRef.OptionsRef.ASKING)
        }
        pushContactToServer(service, contact, preAuth, defaultIqHandler, presenceGenerator)
    }

    @JvmStatic
    fun onOtrSessionEstablished(
        service: XmppConnectionService,
        conversation: ConversationRef,
        jingleConnectionManager: JingleConnectionManager,
        messageGenerator: MessageGenerator,
    ) {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val otrSession = conversation.getOtrSession() ?: throw NullPointerException("no otr session")
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + " otr session established with "
                + conversation.getJid() + "/" + otrSession.getSessionID().getUserID(),
        )
        conversation.findUnsentMessagesWithEncryption(
            MessageRef.ENCRYPTION_OTR,
            object : ConversationRef.OnMessageFound {
                override fun onMessageFound(message: MessageRef) {
                    val id = otrSession.getSessionID()
                    try {
                        message.setCounterpart(Jid.of(id.getAccountID() + "/" + id.getUserID()))
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                    if (message.needsUploading()) {
                        jingleConnectionManager.startJingleFileTransfer(message)
                    } else {
                        val outPacket: Message? = messageGenerator.generateOtrChat(message)
                        if (outPacket != null) {
                            messageGenerator.addDelay(outPacket, message.getTimeSent())
                            message.setStatus(MessageRef.STATUS_SEND)
                            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, false)
                            service.sendMessagePacket(account, outPacket)
                        }
                    }
                    service.updateConversationUi()
                }
            },
        )
    }

    @JvmStatic
    fun pushContactToServer(
        service: XmppConnectionService,
        contact: ContactRef,
        preAuth: String?,
        defaultIqHandler: Consumer<Iq>,
        presenceGenerator: PresenceGenerator,
    ) {
        contact.resetOption(ContactRef.OptionsRef.DIRTY_DELETE)
        contact.setOption(ContactRef.OptionsRef.DIRTY_PUSH)
        val account = contact.getAccount()
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            val ask = contact.getOption(ContactRef.OptionsRef.ASKING)
            val sendUpdates =
                contact.getOption(ContactRef.OptionsRef.PENDING_SUBSCRIPTION_REQUEST) &&
                    contact.getOption(ContactRef.OptionsRef.PREEMPTIVE_GRANT)
            val iq = Iq(Iq.Type.SET)
            iq.query(Namespace.ROSTER).addChild(contact.asElement())
            (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).sendIqPacket(iq, defaultIqHandler)
            if (sendUpdates) {
                service.sendPresencePacket(
                    account,
                    presenceGenerator.sendPresenceUpdatesTo(contact),
                )
            }
            if (ask) {
                service.sendPresencePacket(
                    account,
                    presenceGenerator.requestPresenceUpdatesFrom(contact, preAuth),
                )
            }
        } else {
            service.syncRoster(contact.getAccount())
        }
    }
}
