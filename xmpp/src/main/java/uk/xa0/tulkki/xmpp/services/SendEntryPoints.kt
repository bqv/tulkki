package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the outgoing send entry points, lifted out of `XmppConnectionService`
 *.
 *
 * The two public `sendMessage` overloads and the four small builders that hand a stanza to the
 * service's own `sendMessagePacket`. Every private field the Java bodies read is reached through the
 * service's public accessor (`getMessageGenerator`, `getFileBackend`, `getHttpConnectionManager`,
 * `getJingleConnectionManager`), so nothing had to be passed in for them.
 *
 * The one real seam is chunk `C25b`'s **private** six-argument `sendMessage`, which is where the
 * translation hold lives and which is not moving with this chunk. It arrives as
 * [OutgoingStanzaSender] — the service implements it with `this::sendMessage` — so its visibility is
 * not widened, and the argument order and the `null` callback of the Java call are preserved
 * exactly. `C25c`'s resend plumbing shares this interface, which is why the two chunks land in one
 * commit.
 */
object SendEntryPoints {

    @JvmStatic
    fun sendChatState(service: XmppConnectionService, conversation: ConversationRef) {
        if (service.sendChatStates()) {
            val packet = service.getMessageGenerator().generateChatState(conversation)
            service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), packet)
        }
    }

    /**
     * Picks the upload connection when the account can take it (or the room is multi), and the
     * jingle transfer otherwise, running the callback on the transfer path only — as before.
     */
    @JvmStatic
    fun sendFileMessage(
        service: XmppConnectionService,
        message: MessageRef,
        delay: Boolean,
        cb: Runnable?,
        forceP2P: Boolean,
    ) {
        val account = (message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
            ?: throw NullPointerException("conversation has no account")
        Log.d(
            Config.LOGTAG,
            "" + account.getJid().asBareJid() + ": send file message. forceP2P=" + forceP2P,
        )
        if ((account.httpUploadAvailable(service.getFileBackend().getFile(message, false).getSize())
                || (message.getConversation() ?: throw NullPointerException("message has no conversation")).getMode() == ConversationalRef.MODE_MULTI)
            && !forceP2P
        ) {
            service.getHttpConnectionManager().createNewUploadConnection(message, delay, cb)
        } else {
            service.getJingleConnectionManager().startJingleFileTransfer(message)
            cb?.run()
        }
    }

    /** The one-argument entry point: no resend, no preview, no delay, no callback, no P2P force. */
    @JvmStatic
    fun sendMessage(message: MessageRef, sender: OutgoingStanzaSender) {
        sender.sendMessage(message, false, false, false, null, false)
    }

    @JvmStatic
    fun sendMessage(
        message: MessageRef,
        cb: Runnable?,
        sender: OutgoingStanzaSender,
    ) {
        sender.sendMessage(message, false, false, false, cb, false)
    }

    @JvmStatic
    fun sendEphemeralImplicitNegotiation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        timer: Int,
    ) {
        val packet = Message()
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.setType(
            if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                Message.Type.CHAT
            } else {
                Message.Type.GROUPCHAT
            }
        )
        packet.addChild("ephemeral", Namespace.EPHEMERAL).setAttribute("timer", timer.toString())
        packet.addChild("store", Namespace.HINTS)
        service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), packet)
    }

    @JvmStatic
    fun sendEphemeralIWantOut(service: XmppConnectionService, conversation: ConversationRef) {
        val packet = Message()
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.setType(
            if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                Message.Type.CHAT
            } else {
                Message.Type.GROUPCHAT
            }
        )
        packet.addChild("i-want-out", Namespace.EPHEMERAL)
        packet.addChild("store", Namespace.HINTS)
        service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), packet)
    }
}

/**
 * Tulkki: chunk `C25b`'s private six-argument `sendMessage`, as the service's `this::sendMessage`
 * method reference. The names and the order are the Java's; `cb` is nullable because the
 * one-argument and previewed-link paths pass `null`.
 */
fun interface OutgoingStanzaSender {
    fun sendMessage(
        message: MessageRef,
        resend: Boolean,
        previewedLinks: Boolean,
        delay: Boolean,
        cb: Runnable?,
        forceP2P: Boolean,
    )
}
