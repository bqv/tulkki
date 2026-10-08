package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * Tulkki: conversation archival and the presence request's cancel, lifted out of
 * `XmppConnectionService`.
 *
 * The Java body's guard order, its lock on the live conversation list and the two bookmark shapes
 * are kept. Everything it reaches outside itself is a public service member, so only the service is
 * passed in. `MODE_MULTI` is declared on `ConversationalRef` and only inherited by `ConversationRef`,
 * and Kotlin inherits no static from an interface, so the constant is qualified with the interface
 * that declares it.
 */
object ConversationArchival {

    @JvmStatic
    fun archiveConversation(service: XmppConnectionService, conversation: ConversationRef) {
        archiveConversation(service, conversation, true)
    }

    @JvmStatic
    fun archiveConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        maySynchronizeWithBookmarks: Boolean,
    ) {
        if (service.isOnboarding()) return

        service.getNotificationService().clear(conversation)
        conversation.setStatus(ConversationRef.STATUS_ARCHIVED)
        conversation.setNextMessage(null)
        synchronized(service.getConversationList()) {
            service.getMessageArchiveService().kill(conversation)
            if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                if ((conversation.getAccount()
                        ?: throw NullPointerException("conversation has no account"))
                        .getStatusRef() == AccountRef.StateRef.ONLINE
                ) {
                    val bookmark = conversation.getBookmark()
                    if (maySynchronizeWithBookmarks && bookmark != null) {
                        if (conversation.getMucOptions().error() == MucOptionsRef.ErrorRef.DESTROYED) {
                            val account = bookmark.getAccount()
                            bookmark.setConversation(null)
                            service.deleteBookmark(account, bookmark)
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false)
                            service.createBookmark(bookmark.getAccount(), bookmark)
                        }
                    }
                }
                service.deregisterWithMuc(conversation)
                service.leaveMuc(conversation)
            } else {
                if (conversation
                        .getContact()
                        .getOption(ContactRef.OptionsRef.PENDING_SUBSCRIPTION_REQUEST)
                ) {
                    stopPresenceUpdatesTo(service, conversation.getContact())
                }
            }
            service.updateConversation(conversation)
            service.getConversationList().remove(conversation)
            service.updateConversationUi()
        }
    }

    @JvmStatic
    fun stopPresenceUpdatesTo(service: XmppConnectionService, contact: ContactRef) {
        Log.d(Config.LOGTAG, "Canceling presence request from " + contact.getJid().toString())
        service.sendPresencePacket(
            contact.getAccount(),
            service.getPresenceGenerator().stopPresenceUpdatesTo(contact),
        )
        contact.resetOption(ContactRef.OptionsRef.PENDING_SUBSCRIPTION_REQUEST)
    }
}
