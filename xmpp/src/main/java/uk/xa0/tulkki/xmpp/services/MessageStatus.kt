package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.LocalizedContent

/**
 * Tulkki: the message-status ladder, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk reads two public service members (`getConversationList()`, `updateConversationUi()`) and
 * one public field (`databaseBackend`), so the service itself is the seam. The one genuinely private
 * thing is `mNotificationService` (C70); it is passed to every overload because the whole chain can
 * reach the one branch that dereferences it, and it is declared **nullable** so that the Java's NPE —
 * raised only where the Java raised it, inside that branch — is preserved rather than replaced by a
 * `checkNotNullParameter` at the seam. No `require` was invented here.
 *
 * The seven `markMessage` overloads keep the Java's exact chain: the two `AccountRef` lookups compare
 * the account by **identity** (`===`, as the Java `==` did), the `ConversationRef` overloads answer
 * `false` for an unknown uuid rather than throwing, the deepest overload returns early on the two
 * illegal status transitions **before** it writes anything, and `markMessage(MessageRef, int, String)`
 * hands a null error message down as the row records.
 */
object MessageStatus {

    @JvmStatic
    fun resetSendingToWaiting(
        service: XmppConnectionService,
        account: AccountRef,
        notificationService: NotificationPort?,
    ) {
        for (conversation in service.getConversationList()) {
            if (conversation.getAccount() === account) {
                conversation.findUnsentTextMessages(
                    object : ConversationRef.OnMessageFound {
                        override fun onMessageFound(message: MessageRef) {
                            markMessage(
                                service,
                                message,
                                MessageRef.STATUS_WAITING,
                                notificationService,
                            )
                        }
                    },
                )
            }
        }
    }

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        account: AccountRef,
        recipient: Jid,
        uuid: String?,
        status: Int,
        notificationService: NotificationPort?,
    ): MessageRef? =
        markMessage(service, account, recipient, uuid, status, null, notificationService)

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        account: AccountRef,
        recipient: Jid,
        uuid: String?,
        status: Int,
        errorMessage: String?,
        notificationService: NotificationPort?,
    ): MessageRef? {
        if (uuid == null) {
            return null
        }
        for (conversation in service.getConversationList()) {
            if ((conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid().equals(recipient)
                && conversation.getAccount() === account
            ) {
                val message = conversation.findSentMessageWithUuidOrRemoteId(uuid)
                if (message != null) {
                    markMessage(service, message, status, errorMessage, notificationService)
                }
                return message
            }
        }
        return null
    }

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uuid: String?,
        status: Int,
        serverMessageId: String?,
        notificationService: NotificationPort?,
    ): Boolean = markMessage(
        service,
        conversation,
        uuid,
        status,
        serverMessageId,
        null,
        null,
        null,
        null,
        null,
        notificationService,
    )

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uuid: String?,
        status: Int,
        serverMessageId: String?,
        body: LocalizedContent?,
        html: Element?,
        subject: String?,
        thread: Element?,
        attachments: Set<@JvmSuppressWildcards MessageRef.FileParamsRef>?,
        notificationService: NotificationPort?,
    ): Boolean {
        if (uuid == null) {
            return false
        } else {
            val message = conversation.findSentMessageWithUuid(uuid)
            if (message != null) {
                if (message.getServerMsgId() == null) {
                    message.setServerMsgId(serverMessageId)
                }
                if (message.getEncryption() == MessageRef.ENCRYPTION_NONE
                    && (body != null || html != null || subject != null
                        || thread != null || attachments != null)
                ) {
                    val content = body ?: throw NullPointerException("body is null")
                    message.setBody(content.content)
                    if (content.count > 1) {
                        message.setBodyLanguage(content.language)
                    }
                    message.setHtml(html)
                    message.setSubject(subject)
                    message.setThread(thread)
                    if (attachments != null && attachments.isEmpty()) {
                        message.setRelativeFilePath(null)
                        message.resetFileParams()
                    }
                    markMessage(service, message, status, null, true, notificationService)
                } else {
                    markMessage(service, message, status, notificationService)
                }
                return true
            } else {
                return false
            }
        }
    }

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        message: MessageRef,
        status: Int,
        notificationService: NotificationPort?,
    ) {
        markMessage(service, message, status, null, notificationService)
    }

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        message: MessageRef,
        status: Int,
        errorMessage: String?,
        notificationService: NotificationPort?,
    ) {
        markMessage(service, message, status, errorMessage, false, notificationService)
    }

    @JvmStatic
    fun markMessage(
        service: XmppConnectionService,
        message: MessageRef,
        status: Int,
        errorMessage: String?,
        includeBody: Boolean,
        notificationService: NotificationPort?,
    ) {
        val oldStatus = message.getStatus()
        if (status == MessageRef.STATUS_SEND_FAILED
            && (oldStatus == MessageRef.STATUS_SEND_RECEIVED
                || oldStatus == MessageRef.STATUS_SEND_DISPLAYED)
        ) {
            return
        }
        if (status == MessageRef.STATUS_SEND_RECEIVED
            && oldStatus == MessageRef.STATUS_SEND_DISPLAYED
        ) {
            return
        }
        message.setErrorMessage(errorMessage)
        message.setStatus(status)
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, includeBody)
        service.updateConversationUi()
        if (oldStatus != status && status == MessageRef.STATUS_SEND_FAILED) {
            (notificationService ?: throw NullPointerException("notification service is null"))
                .pushFailedDelivery(message)
        }
    }
}
