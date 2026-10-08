package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the held sub-managers and their getters, lifted out of `XmppConnectionService`
 *.
 *
 * The fields themselves stay on the service: the jingle, HTTP, archive and sub-manager slots are
 * written by `onCreate`/`continueAfterDbInit` and read by chunks that have not moved yet, so they
 * arrive here as private state handed in by hand and the getters answer the same instance back. The
 * two port getters are guarded by chunk `C76`'s shared `require`, which stays where it is; the
 * Java delegation resolves it and passes the non-null port, so the build-fault message is written
 * once and no visibility is widened.
 *
 * The three lookups are the Java's: `findContacts` walks the accounts, `findFirstMuc` walks the live
 * conversation list and picks the multi-mode room whose bare JID matches — with `MODE_MULTI`
 * qualified because Kotlin inherits no constant from an interface — and `findFirstMuc(jid)` passes
 * the Java's `null` account filter. `resendFailedMessages` re-enters chunk `C25b`'s **private**
 * six-argument send through the same [OutgoingStanzaSender] seam `C25a`/`C25c` use, so the hold in
 * that method keeps its place.
 */
object HeldSubManagers {

    @JvmStatic
    fun jingleConnectionManager(manager: JingleConnectionManager): JingleConnectionManager = manager

    @JvmStatic
    fun hasJingleRtpConnection(manager: JingleConnectionManager, account: AccountRef): Boolean =
        manager.hasJingleRtpConnection(account)

    @JvmStatic
    fun messageArchiveService(service: MessageArchiveService): MessageArchiveService = service

    @JvmStatic
    fun contactListSyncService(
        port: ContactListSyncPort,
    ): ContactListSyncPort = port

    @JvmStatic
    fun notificationService(
        port: NotificationPort,
    ): NotificationPort = port

    @JvmStatic
    fun httpConnectionManager(manager: HttpConnectionManager): HttpConnectionManager = manager

    @JvmStatic
    fun findContacts(jid: Jid, accountJid: String?): List<ContactRef> {
        val contacts = ArrayList<ContactRef>()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if ((account.isEnabled() || accountJid != null)
                && (accountJid == null || accountJid == account.getJid().asBareJid().toString())
            ) {
                val contact = account.getRoster().getContactFromContactList(jid)
                if (contact != null) {
                    contacts.add(contact)
                }
            }
        }
        return contacts
    }

    @JvmStatic
    fun findFirstMuc(jid: Jid, conversationRefs: List<ConversationRef>): ConversationRef? =
        findFirstMuc(jid, null, conversationRefs)

    @JvmStatic
    fun findFirstMuc(
        jid: Jid,
        accountJid: String?,
        conversationRefs: List<ConversationRef>,
    ): ConversationRef? {
        for (conversation in conversationRefs) {
            val account =
                conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account")
            if ((account.isEnabled() || accountJid != null)
                && (accountJid == null
                    || accountJid == account.getJid().asBareJid().toString())
                && (conversation.getJid()
                    ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid() == jid.asBareJid()
                && conversation.getMode() == ConversationalRef.MODE_MULTI
            ) {
                return conversation
            }
        }
        return null
    }

    @JvmStatic
    fun resendFailedMessages(
        service: XmppConnectionService,
        message: MessageRef,
        forceP2P: Boolean,
        sender: OutgoingStanzaSender,
    ) {
        message.setTime(System.currentTimeMillis())
        service.markMessage(message, MessageRef.STATUS_WAITING)
        sender.sendMessage(message, true, false, false, null, forceP2P)
        val conversation = message.getConversation()
        if (conversation is ConversationRef) {
            conversation.sort()
        }
        service.updateConversationUi()
    }
}
