package uk.xa0.tulkki.xmpp.services

import android.app.PendingIntent
import android.net.ConnectivityManager
import android.os.Build
import com.google.common.collect.ImmutableSet
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Element

/**
 * Tulkki: push, the data-saver reading and the notification quick reply, lifted out of
 * `XmppConnectionService`.
 *
 * The push broker is reached through the service's public getter; the channel-discovery port, the
 * platform-compatibility port and the interpreter's `SendGate` and `TulkkiPorts` are private state
 * of other groups and arrive by hand. `directReply` asks the gate before building anything, exactly
 * as the Java body did, and its PGP branch keeps the Java NPE by evaluating the engine where the
 * Java did.
 */
object PushReply {

    @JvmStatic
    fun toggleSoftDisabled(service: XmppConnectionService, softDisabled: Boolean) {
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.isEnabled()) {
                if (account.setOption(AccountRef.OPTION_SOFT_DISABLED, softDisabled)) {
                    service.updateAccount(account)
                }
            }
        }
    }

    @JvmStatic
    fun processUnifiedPushMessage(
        service: XmppConnectionService,
        accountRef: AccountRef,
        transport: Jid,
        push: Element,
    ): Boolean {
        // Tulkki: 3.7 pair 9 - the island's parameter is the ref now (the parsers' call sites pass
        // one); the body still works in the model type and casts once, which cluster (f) removes.
        val account = accountRef
        return service.getUnifiedPushBroker().processPushMessage(account, transport, push)
    }

    @JvmStatic
    fun reinitializeMuclumbusService(channelDiscovery: ChannelDiscoveryPort) {
        channelDiscovery.initializeMuclumbusService()
    }

    @JvmStatic
    fun isDataSaverDisabled(
        service: XmppConnectionService,
        compatibility: CompatibilityPort,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true
        }
        val connectivityManager = service.getSystemService(ConnectivityManager::class.java)
        return !compatibility.isActiveNetworkMetered(connectivityManager) ||
            compatibility.getRestrictBackgroundStatus(connectivityManager) ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
    }

    @JvmStatic
    fun getMessagesCountGroupByDay(
        service: XmppConnectionService,
        conversationUuid: String,
        year: Int,
        month: Int,
    ): Map<Int, Int> =
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessagesCountGroupByDay(conversationUuid, year, month)

    @JvmStatic
    fun directReply(
        service: XmppConnectionService,
        conversation: ConversationRef,
        body: String,
        lastMessageUuid: String?,
        dismissAfterReply: Boolean,
        sendGate: SendGate,
        tulkkiPorts: TulkkiPorts,
    ) {
        // Tulkki: the quick reply is the one send that never passes the composer, so the composer's
        // refusal is repeated here - a reply written in another language does not send, and its words
        // go into the conversation's draft for the owner to fix where the normal gate will see them.
        // It is asked before anything is built, so a refused reply makes no message, no unsent bubble
        // and no database row. Nothing is sent from here, so the caller must stop.
        if (sendGate.refuseQuickReply(conversation, body)) {
            return
        }
        val inReplyTo =
            if (lastMessageUuid == null) null else conversation.findMessageWithUuid(lastMessageUuid)
        var message =
            XmppConnectionService.dataStatics()
                .newMessage(conversation, body, conversation.getNextEncryption())
        if (inReplyTo != null) {
            if (XmppConnectionService.dataStatics().isEmoji(body.replace("\\s".toRegex(), ""))) {
                val aggregated = inReplyTo.getAggregatedOurReactions()
                val reactionBuilder = ImmutableSet.builder<String>()
                reactionBuilder.addAll(aggregated)
                reactionBuilder.add(body.replace("\\s".toRegex(), ""))
                service.sendReactions(inReplyTo, reactionBuilder.build())
                return
            } else {
                message = inReplyTo.reply()
            }
            message.clearFallbacks("urn:xmpp:reply:0")
            message.setBody(body)
            message.setEncryption(conversation.getNextEncryption())
        }
        if (inReplyTo != null && inReplyTo.isPrivateMessage()) {
            XmppConnectionService.dataStatics()
                .configurePrivateMessage(
                    message,
                    inReplyTo.getCounterpart()
                        ?: throw NullPointerException("reply has no counterpart"),
                )
        }
        message.markUnread()
        if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
            // Pair 11 (D4): the continuation is this island's own, and the slot it goes into is
            // `:crypto`'s callback type, which this file may not name. `pgpCallback` is the
            // composition root's one-line adapter between the two; the three methods and the
            // behaviour are exactly what was here.
            (service.getPgpEngine() ?: throw NullPointerException())
                .encrypt(
                    message,
                    tulkkiPorts.pgpCallback(
                        object : UiCallbackPort<MessageRef> {
                            override fun success(message: MessageRef) {
                                if (dismissAfterReply) {
                                    service.markRead(
                                        message.getConversation() as ConversationRef,
                                        true,
                                    )
                                } else {
                                    service.getNotificationService().pushFromDirectReply(message)
                                }
                            }

                            override fun error(errorCode: Int, message: MessageRef?) {}

                            override fun userInputRequired(pi: PendingIntent?, message: MessageRef) {}
                        },
                    ),
                )
        } else {
            service.sendMessage(message)
            if (dismissAfterReply) {
                service.markRead(conversation, true)
            } else {
                service.getNotificationService().pushFromDirectReply(message)
            }
        }
    }
}
