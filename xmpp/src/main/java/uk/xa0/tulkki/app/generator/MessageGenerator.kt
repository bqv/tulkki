package uk.xa0.tulkki.app.generator

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import net.java.otr4j.OtrException
import net.java.otr4j.session.Session
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.models.correction.Replace
import uk.xa0.tulkki.xmpp.models.reactions.Reaction
import uk.xa0.tulkki.xmpp.models.reactions.Reactions
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.models.unique.OriginId
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the message stanzas this app puts on the wire - the chat, the corrections, the reactions
 * and the jingle call proposals.
 *
 * Ported from `MessageGenerator.java`. It is *ours* rather than the island's, so it is converted
 * in place; every method keeps the signature the callers read.
 *
 * The Java-visible surface, read off the callers and the interfaces it implements:
 *
 *  * `OTR_FALLBACK_MESSAGE` is read by `OtrService.kt:127` as a **field**, so it stays a `const val`
 *    in the companion, which is the same `public static final String` on the JVM;
 *  * `addMessageHints` was `static` and `OtrService.kt:174` calls it as one, so it keeps its
 *    `@JvmStatic` bridge in the companion;
 *  * the three `preparePacket`-consuming entry points keep the Java's nullability: `generateAxolotlChat`
 *    and `generateOtrChat` answer **null** on the paths Java's `return null` proves (no wire message,
 *    no OTR session, `OtrException`), and `axolotlMessage` is nullable because Java tests it;
 *  * `conferenceSubject`'s `subject` is **nullable**, because `ConferenceConfiguration.kt:246` passes
 *    `StringUtils.nullOnEmpty(subject)`;
 *  * `received`'s namespace list is a `List<String>` rather than the Java's `ArrayList<String>` - the
 *    only caller, `MessageParser.kt:2678`, passes an `ArrayList`, and no Java caller exists;
 *  * the cast `(ConversationRef) message.getConversation()` becomes `as ConversationRef`, which is
 *    the same throw when the ref is null and the same success otherwise.
 *
 * Two Java facts are handled rather than inherited: the static `getTimestamp` is reached through
 * `AbstractGenerator.getTimestamp(…)` (Kotlin does not inherit a base's companion into a subclass's
 * scope, the `MucJoin.kt` `PresenceGenerator` fault), and `packet.addChild(el)` for a `Node` answers
 * the node, as in Java.
 */
class MessageGenerator(service: XmppConnectionService) : AbstractGenerator(service) {

    private fun preparePacket(message: MessageRef, legacyEncryption: Boolean): Message {
        val conversation = message.getConversation() as ConversationRef
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val packet = Message()
        packet.setFrom(account.getJid())
        packet.setId(message.getUuid())

        if (message.isDeleted() && message.getRetractId() != null) {
            if (conversation.getMode() == ConversationalRef.MODE_SINGLE ||
                message.isPrivateMessage()
            ) {
                packet.setTo(message.getCounterpart())
                packet.setType(Message.Type.CHAT)
            } else {
                packet.setTo((message.getCounterpart() ?: throw NullPointerException("message has no counterpart")).asBareJid())
                packet.setType(Message.Type.GROUPCHAT)
            }
            if (message.isPrivateMessage()) {
                packet.addChild("x", "http://jabber.org/protocol/muc#user")
            }
            val retract = packet.addChild("retract", "urn:xmpp:message-retract:1")
            retract.setAttribute("id", message.getRetractId())
            val fallback = packet.addChild("fallback", "urn:xmpp:fallback:0")
            fallback.setAttribute("for", "urn:xmpp:message-retract:1")
            val body = Element("body")
            body.setContent("This message has been retracted by the sender.")
            packet.addChild(body)
            packet.addChild("store", "urn:xmpp:hints")
            return packet
        }

        val isWithSelf = conversation.getContact().isSelf()
        if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
            packet.setTo(message.getCounterpart())
            packet.setType(Message.Type.CHAT)
            if (!isWithSelf) {
                packet.addChild("request", "urn:xmpp:receipts")
            }
        } else if (message.isPrivateMessage()) {
            packet.setTo(message.getCounterpart())
            packet.setType(Message.Type.CHAT)
            packet.addChild("x", "http://jabber.org/protocol/muc#user")
            packet.addChild("request", "urn:xmpp:receipts")
        } else {
            packet.setTo((message.getCounterpart() ?: throw NullPointerException("message has no counterpart")).asBareJid())
            packet.setType(Message.Type.GROUPCHAT)
        }
        if (conversation.isSingleOrPrivateAndNonAnonymous() && !message.isPrivateMessage()) {
            packet.addChild("markable", "urn:xmpp:chat-markers:0")
        }
        if (message.getEphemeralTimer() > 0) {
            packet.addChild("ephemeral", Namespace.EPHEMERAL)
                .setAttribute("timer", message.getEphemeralTimer().toString())
            packet.addChild("no-permanent-store", Namespace.HINTS)
        }
        if (message.isEphemeralIWantOut()) {
            packet.addChild("i-want-out", Namespace.EPHEMERAL)
        }
        if (message.getRawBody() == null &&
            (message.getEphemeralTimer() > 0 || message.isEphemeralIWantOut())
        ) {
            packet.addChild("store", "urn:xmpp:hints")
        }
        if (conversation.getMode() == ConversationalRef.MODE_MULTI &&
            !message.isPrivateMessage() &&
            !conversation.getMucOptions().stableId()
        ) {
            message.getUuid()?.let { packet.addExtension(OriginId(it)) }
        } else if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
            message.getUuid()?.let { packet.addExtension(OriginId(it)) }
        }
        if (message.edited() && !message.isDeleted()) {
            packet.addExtension(Replace(message.getEditedIdWireFormat()))
        }

        if (!legacyEncryption) {
            val subject = message.getSubject()
            if (subject != null && subject.length > 0) {
                packet.addChild("subject").setContent(subject)
            }
            // Legacy encryption can't handle advanced payloads
            for (el in message.getPayloads()) {
                packet.addChild(el)
            }
        } else {
            for (el in message.getPayloads()) {
                // Allow <thread>, XEP-0461 <reply>, and XEP-0461 <fallback for reply> elements
                if ("thread" == el.getName() ||
                    ("reply" == el.getName() && "urn:xmpp:reply:0" == el.getNamespace()) ||
                    ("fallback" == el.getName() &&
                        "urn:xmpp:fallback:0" == el.getNamespace() &&
                        "urn:xmpp:reply:0" == el.getAttribute("for") &&
                        !message.hasFileOnRemoteHost())
                ) {
                    packet.addChild(el)
                }
            }
        }
        return packet
    }

    fun addDelay(packet: Message, timestamp: Long) {
        val mDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        mDateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val delay = packet.addChild("delay", "urn:xmpp:delay")
        val date = Date(timestamp)
        delay.setAttribute("stamp", mDateFormat.format(date))
    }

    fun generateAxolotlChat(message: MessageRef, axolotlMessage: OmemoWire?): Message? {
        val packet = preparePacket(message, true)
        if (axolotlMessage == null) {
            return null
        }
        packet.setAxolotlMessage(axolotlMessage.toElement())
        packet.setBody(OMEMO_FALLBACK_MESSAGE)
        packet.addChild("store", "urn:xmpp:hints")
        packet.addChild("encryption", "urn:xmpp:eme:0")
            .setAttribute("name", "OMEMO")
            .setAttribute("namespace", OmemoSessionPort.PEP_PREFIX)
        return packet
    }

    fun generateKeyTransportMessage(to: Jid, axolotlMessage: OmemoWire): Message {
        val packet = Message()
        packet.setType(Message.Type.CHAT)
        packet.setTo(to)
        packet.setAxolotlMessage(axolotlMessage.toElement())
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun generateChat(message: MessageRef): Message {
        var packet = preparePacket(message, false)
        if (message.hasFileOnRemoteHost()) {
            val fileParams = message.getFileParams()
            val url = fileParams.url()

            if (message.getFallbacks(Namespace.OOB).isEmpty()) {
                if (message.getBody() == "") {
                    message.setBody(url)
                    val fallback =
                        Element("fallback", "urn:xmpp:fallback:0")
                            .setAttribute("for", Namespace.OOB)
                    fallback.addChild("body", "urn:xmpp:fallback:0")
                    message.addPayload(fallback)
                } else {
                    val existingBody =
                        message.getRawBody() ?: throw NullPointerException("message has no raw body")
                    val start =
                        existingBody.codePointCount(0, existingBody.length)
                    // Tulkki: Java's `+=` rendered the literal text "null" into the body when the
                    // url was absent; that is a bug, not an intent, so nothing is appended instead.
                    message.appendBody(url ?: "")
                    val fallback =
                        Element("fallback", "urn:xmpp:fallback:0")
                            .setAttribute("for", Namespace.OOB)
                    fallback
                        .addChild("body", "urn:xmpp:fallback:0")
                        .setAttribute("start", start.toString())
                        .setAttribute("end", (start + (url?.length ?: 0)).toString())
                    message.addPayload(fallback)
                }
            }

            packet = preparePacket(message, false)
            packet.addChild("x", Namespace.OOB).addChild("url").setContent(url)
        }
        val rawBody = message.getRawBody()
        if (rawBody != null && !message.isDeleted()) {
            packet.setBody(rawBody)
        }
        return packet
    }

    fun generatePgpChat(message: MessageRef): Message {
        val packet = preparePacket(message, true)
        if (message.hasFileOnRemoteHost()) {
            val fileParams = message.getFileParams()
            val url = fileParams.url() ?: throw NullPointerException("file params have no url")
            packet.setBody(url)
            packet.addChild("x", Namespace.OOB).addChild("url").setContent(url)
            packet.addChild("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", Namespace.OOB)
                .addChild("body", "urn:xmpp:fallback:0")
        } else {
            if (Config.supportUnencrypted()) {
                packet.setBody(PGP_FALLBACK_MESSAGE)
            }
            if (message.getEncryption() == MessageRef.ENCRYPTION_DECRYPTED) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getEncryptedBody())
            } else if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
                packet.addChild("x", "jabber:x:encrypted").setContent(message.getBody())
            }
            packet.addChild("encryption", "urn:xmpp:eme:0")
                .setAttribute("namespace", "jabber:x:encrypted")
        }
        return packet
    }

    fun generateOtrChat(message: MessageRef): Message? {
        val conversation = message.getConversation() as ConversationRef
        val otrSession = conversation.getOtrSession()
        if (otrSession == null) {
            return null
        }
        val packet = preparePacket(message, true)
        addMessageHints(packet)
        try {
            val content: String = if (message.hasFileOnRemoteHost()) {
                message.getFileParams().url().toString()
            } else {
                message.getBody()
            }
            packet.setBody(otrSession.transformSending(content)[0])
            packet.addChild("encryption", "urn:xmpp:eme:0")
                .setAttribute("namespace", "urn:xmpp:otr:0")
            return packet
        } catch (e: OtrException) {
            return null
        }
    }

    fun generateChatState(conversation: ConversationRef): Message {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val packet = Message()
        packet.setType(
            if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                Message.Type.GROUPCHAT
            } else {
                Message.Type.CHAT
            }
        )
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.setFrom(account.getJid())
        packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()))
        packet.addChild("no-store", "urn:xmpp:hints")
        packet.addChild("no-storage", "urn:xmpp:hints") // wrong! don't copy this. Its *store*
        return packet
    }

    fun confirm(message: MessageRef): Message {
        val groupChat =
            (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                .getMode() ==
                ConversationalRef.MODE_MULTI
        val to = message.getCounterpart() ?: throw NullPointerException("message has no counterpart")
        val packet = Message()
        packet.setType(if (groupChat) Message.Type.GROUPCHAT else Message.Type.CHAT)
        packet.setTo(if (groupChat) to.asBareJid() else to)
        val displayed = packet.addChild("displayed", "urn:xmpp:chat-markers:0")
        if (groupChat) {
            val stanzaId = message.getServerMsgId()
            if (stanzaId != null) {
                displayed.setAttribute("id", stanzaId)
            } else {
                displayed.setAttribute("sender", to.toString())
                displayed.setAttribute("id", message.getRemoteMsgId())
            }
        } else {
            displayed.setAttribute("id", message.getRemoteMsgId())
        }
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun reaction(
        to: Jid,
        groupChat: Boolean,
        inReplyTo: MessageRef,
        reactingTo: String?,
        ourReactions: Collection<String>,
    ): Message {
        val packet = Message()
        packet.setType(if (groupChat) Message.Type.GROUPCHAT else Message.Type.CHAT)
        packet.setTo(to)
        val reactions = packet.addExtension(Reactions())
        reactions.setId(reactingTo ?: throw NullPointerException("reactingTo"))
        for (ourReaction in ourReactions) {
            reactions.addExtension(Reaction(ourReaction))
        }

        val thread = inReplyTo.getThread()
        if (thread != null) {
            packet.addChild(thread)
        }

        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun conferenceSubject(conversation: ConversationRef, subject: String?): Message {
        val packet = Message()
        packet.setType(Message.Type.GROUPCHAT)
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.addChild("subject").setContent(subject)
        packet.setFrom((conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
            .getJid()
            .asBareJid())
        return packet
    }

    fun requestVoice(jid: Jid): Message {
        val packet = Message()
        packet.setType(Message.Type.NORMAL)
        packet.setTo(jid.asBareJid())
        val form = Data()
        form.setFormType("http://jabber.org/protocol/muc#request")
        form.put("muc#role", "participant")
        form.submit()
        packet.addChild(form)
        return packet
    }

    fun directInvite(conversation: ConversationRef, contact: Jid): Message {
        val packet = Message()
        packet.setType(Message.Type.NORMAL)
        packet.setTo(contact)
        packet.setFrom((conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid())
        val x = packet.addChild("x", "jabber:x:conference")
        x.setAttribute("jid", (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        val password = conversation.getMucOptions().getPassword()
        if (password != null) {
            x.setAttribute("password", password)
        }
        if (contact.isFullJid()) {
            packet.addChild("no-store", "urn:xmpp:hints")
            packet.addChild("no-copy", "urn:xmpp:hints")
        }
        return packet
    }

    fun invite(conversation: ConversationRef, contact: Jid): Message {
        val packet = Message()
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.setFrom((conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid())
        val x = Element("x")
        x.setAttribute("xmlns", "http://jabber.org/protocol/muc#user")
        val invite = Element("invite")
        invite.setAttribute("to", contact.asBareJid())
        x.addChild(invite)
        packet.addChild(x)
        return packet
    }

    fun received(
        account: AccountRef,
        from: Jid,
        id: String?,
        namespaces: List<String>,
        type: Message.Type,
    ): Message {
        val receivedPacket = Message()
        receivedPacket.setType(type)
        receivedPacket.setTo(from)
        receivedPacket.setFrom(account.getJid())
        for (namespace in namespaces) {
            receivedPacket.addChild("received", namespace).setAttribute("id", id)
        }
        receivedPacket.addChild("store", "urn:xmpp:hints")
        return receivedPacket
    }

    fun received(account: AccountRef, to: Jid, id: String): Message {
        val packet = Message()
        packet.setFrom(account.getJid())
        packet.setTo(to)
        packet.addChild("received", "urn:xmpp:receipts").setAttribute("id", id)
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun sessionFinish(with: Jid, sessionId: String, reason: Reason): Message {
        val packet = Message()
        packet.setType(Message.Type.CHAT)
        packet.setTo(with)
        val finish = packet.addChild("finish", Namespace.JINGLE_MESSAGE)
        finish.setAttribute("id", sessionId)
        val reasonElement = finish.addChild("reason", Namespace.JINGLE)
        reasonElement.addChild(reason.toString())
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun generateOtrError(to: Jid, id: String, errorText: String): Message {
        val packet = Message()
        packet.setType(Message.Type.ERROR)
        packet.setAttribute("id", id)
        packet.setTo(to)
        val error = packet.addChild("error")
        error.setAttribute("code", "406")
        error.setAttribute("type", "modify")
        error.addChild("not-acceptable", "urn:ietf:params:xml:ns:xmpp-stanzas")
        error.addChild("text").setContent("?OTR Error:" + errorText)
        return packet
    }

    fun sessionProposal(proposal: JingleConnectionManager.RtpSessionProposal): Message {
        val packet = Message()
        packet.setType(Message.Type.CHAT) // we want to carbon copy those
        packet.setTo(proposal.with)
        packet.setId(AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX + proposal.sessionId)
        val propose = packet.addChild("propose", Namespace.JINGLE_MESSAGE)
        propose.setAttribute("id", proposal.sessionId)
        for (media in proposal.media) {
            propose.addChild("description", Namespace.JINGLE_APPS_RTP)
                .setAttribute("media", media.toString())
        }
        packet.addChild("request", "urn:xmpp:receipts")
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun sessionRetract(proposal: JingleConnectionManager.RtpSessionProposal): Message {
        val packet = Message()
        packet.setType(Message.Type.CHAT) // we want to carbon copy those
        packet.setTo(proposal.with)
        val propose = packet.addChild("retract", Namespace.JINGLE_MESSAGE)
        propose.setAttribute("id", proposal.sessionId)
        propose.addChild("description", Namespace.JINGLE_APPS_RTP)
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    fun sessionReject(with: Jid, sessionId: String): Message {
        val packet = Message()
        packet.setType(Message.Type.CHAT) // we want to carbon copy those
        packet.setTo(with)
        val propose = packet.addChild("reject", Namespace.JINGLE_MESSAGE)
        propose.setAttribute("id", sessionId)
        propose.addChild("description", Namespace.JINGLE_APPS_RTP)
        packet.addChild("store", "urn:xmpp:hints")
        return packet
    }

    companion object {

        const val OTR_FALLBACK_MESSAGE: String =
            "I would like to start a private (OTR encrypted) conversation but your client doesn’t seem to support that"

        private const val OMEMO_FALLBACK_MESSAGE: String =
            "I sent you an OMEMO encrypted message but your client doesn’t seem to support that."

        private const val PGP_FALLBACK_MESSAGE: String =
            "I sent you a PGP encrypted message but your client doesn’t seem to support that."

        @JvmStatic
        fun addMessageHints(packet: Message) {
            packet.addChild("private", "urn:xmpp:carbons:2")
            packet.addChild("no-copy", "urn:xmpp:hints")
            packet.addChild("no-permanent-store", "urn:xmpp:hints")
            packet.addChild("no-permanent-storage", "urn:xmpp:hints") // do not copy this. this is wrong. it is *store*
        }
    }
}
