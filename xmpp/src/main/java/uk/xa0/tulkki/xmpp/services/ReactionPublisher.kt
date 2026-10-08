package uk.xa0.tulkki.xmpp.services

import android.util.Log
import com.google.common.base.Strings
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.ReactionRef

/**
 * Tulkki: the reactions stanza - `sendReactions(MessageRef, Collection<String>)`, lifted out of
 * `XmppConnectionService`.
 *
 * The chunk owns no field: everything it reaches is a **public** service member
 * (`getBooleanPreference`, `getMessageGenerator`, `sendMessagePacket`, `updateMessage`, the public
 * static `dataStatics()`) or the public static `FILE_ATTACHMENT_EXECUTOR`. A private message still
 * **throws** `IllegalArgumentException` as the Java did, an empty collection still means removal
 * rather than a no-op, and the two reaction-set copies stay copies: `existingRaw` and
 * `reactionsAsExistingVariants` are the `ImmutableSet.copyOf` iterations in Kotlin spelling, and
 * `newReactions` is the fresh mutable `HashSet` the fallback path removes from.
 */
object ReactionPublisher {

    @JvmStatic
    fun sendReactions(
        service: XmppConnectionService,
        message: MessageRef,
        reactions: Collection<String>,
    ): Boolean {
        if (message.isPrivateMessage()) {
            throw IllegalArgumentException("Reactions to PM not implemented")
        }
        val conversation = message.getConversation()
        if (conversation !is ConversationRef) {
            return false
        }
        if (service.getBooleanPreference(
                "disable_reactions_fallback",
                R.bool.disable_reactions_fallback,
            )
        ) {
            val isPrivateMessage = message.isPrivateMessage()
            val reactTo: Jid?
            val typeGroupChat: Boolean
            val reactToId: String?
            val combinedReactions: Collection<ReactionRef>
            if (conversation.getMode() == ConversationalRef.MODE_MULTI && !isPrivateMessage) {
                val mucOptions = conversation.getMucOptions()
                if (!mucOptions.participating()) {
                    Log.e(Config.LOGTAG, "not participating in MUC")
                    return false
                }
                val self = mucOptions.getSelf()
                val occupantId = self.getOccupantId()
                if (Strings.isNullOrEmpty(occupantId)) {
                    Log.e(Config.LOGTAG, "occupant id not found for reaction in MUC")
                    return false
                }
                val existingRaw: Set<String> =
                    message.getReactions().mapNotNull { r -> r.reaction() }.toSet()
                val reactionsAsExistingVariants: Set<String> =
                    reactions
                        .map { r ->
                            XmppConnectionService.dataStatics().existingVariant(r, existingRaw)
                        }
                        .toSet()
                if (!reactions.equals(reactionsAsExistingVariants)) {
                    Log.d(Config.LOGTAG, "modified reactions to existing variants")
                }
                reactToId = message.getServerMsgId()
                reactTo = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                typeGroupChat = true
                combinedReactions =
                    XmppConnectionService.dataStatics()
                        .reactionsWithOccupantId(
                            message.getReactions(),
                            reactionsAsExistingVariants,
                            false,
                            self.getFullJid(),
                            (conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid(),
                            occupantId,
                            null,
                        )
            } else {
                if (message.isCarbon() || message.getStatus() == MessageRef.STATUS_RECEIVED) {
                    reactToId = message.getRemoteMsgId()
                } else {
                    reactToId = message.getUuid()
                }
                typeGroupChat = false
                if (isPrivateMessage) {
                    reactTo = message.getCounterpart()
                } else {
                    reactTo = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                }
                combinedReactions =
                    XmppConnectionService.dataStatics()
                        .reactionsWithFrom(
                            message.getReactions(),
                            reactions,
                            false,
                            (conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid(),
                            null,
                        )
            }
            if (reactTo == null || Strings.isNullOrEmpty(reactToId)) {
                Log.e(Config.LOGTAG, "could not find id to react to")
                return false
            }
            val reactionMessage =
                service.getMessageGenerator()
                    .reaction(reactTo, typeGroupChat, message, reactToId, reactions)
            service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), reactionMessage)
            message.replaceReactions(combinedReactions)
            service.updateMessage(message, false)
            return true
        } else {
            val isPrivateMessage = message.isPrivateMessage()
            val reactTo: Jid?
            val typeGroupChat: Boolean
            val reactToId: String?
            val combinedReactions: Collection<ReactionRef>
            val newReactions = HashSet(reactions)
            newReactions.removeAll(message.getAggregatedOurReactions())
            if (conversation.getMode() == ConversationalRef.MODE_MULTI && !isPrivateMessage) {
                val mucOptions = conversation.getMucOptions()
                if (!mucOptions.participating()) {
                    Log.e(Config.LOGTAG, "not participating in MUC")
                    return false
                }
                val self = mucOptions.getSelf()
                val occupantId = self.getOccupantId()
                if (Strings.isNullOrEmpty(occupantId)) {
                    Log.e(Config.LOGTAG, "occupant id not found for reaction in MUC")
                    return false
                }
                val existingRaw: Set<String> =
                    message.getReactions().mapNotNull { r -> r.reaction() }.toSet()
                val reactionsAsExistingVariants: Set<String> =
                    reactions
                        .map { r ->
                            XmppConnectionService.dataStatics().existingVariant(r, existingRaw)
                        }
                        .toSet()
                if (!reactions.equals(reactionsAsExistingVariants)) {
                    Log.d(Config.LOGTAG, "modified reactions to existing variants")
                }
                reactToId = message.getServerMsgId()
                reactTo = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                typeGroupChat = true
                combinedReactions =
                    XmppConnectionService.dataStatics()
                        .reactionsWithOccupantId(
                            message.getReactions(),
                            reactionsAsExistingVariants,
                            false,
                            self.getFullJid(),
                            (conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid(),
                            occupantId,
                            null,
                        )
            } else {
                if (message.isCarbon() || message.getStatus() == MessageRef.STATUS_RECEIVED) {
                    reactToId = message.getRemoteMsgId()
                } else {
                    reactToId = message.getUuid()
                }
                typeGroupChat = false
                if (isPrivateMessage) {
                    reactTo = message.getCounterpart()
                } else {
                    reactTo = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                }
                combinedReactions =
                    XmppConnectionService.dataStatics()
                        .reactionsWithFrom(
                            message.getReactions(),
                            reactions,
                            false,
                            (conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid(),
                            null,
                        )
            }
            if (reactTo == null || Strings.isNullOrEmpty(reactToId)) {
                Log.e(Config.LOGTAG, "could not find id to react to")
                return false
            }

            val packet =
                service.getMessageGenerator()
                    .reaction(reactTo, typeGroupChat, message, reactToId, reactions)

            val quote =
                XmppConnectionService.dataStatics()
                    .quote(XmppConnectionService.dataStatics().prepareQuote(message, 1, 2)) +
                    "\n\n"
            val body = quote + newReactions.joinToString(" ")
            if (conversation.getNextEncryption() == MessageRef.ENCRYPTION_AXOLOTL &&
                newReactions.size > 0
            ) {
                XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute {
                    val axolotlMessage: OmemoWire? =
                        ((conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
                            .getOmemoSession()
                            ?: throw NullPointerException("no omemo session")).encrypt(body, conversation)
                    if (axolotlMessage == null) {
                        return@execute
                    }
                    packet.setAxolotlMessage(axolotlMessage.toElement())
                    packet.addChild("encryption", "urn:xmpp:eme:0")
                        .setAttribute("name", "OMEMO")
                        .setAttribute("namespace", OmemoSessionPort.PEP_PREFIX)
                    service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), packet)
                    message.replaceReactions(combinedReactions)
                    service.updateMessage(message, false)
                }
            } else if (conversation.getNextEncryption() == MessageRef.ENCRYPTION_NONE ||
                newReactions.size < 1
            ) {
                if (newReactions.size > 0) {
                    packet.setBody(body)
                    packet.addChild("reply", "urn:xmpp:reply:0")
                        .setAttribute("to", message.getCounterpart())
                        .setAttribute("id", reactToId)
                    val replyFallback =
                        packet.addChild("fallback", "urn:xmpp:fallback:0")
                            .setAttribute("for", "urn:xmpp:reply:0")
                    replyFallback.addChild("body", "urn:xmpp:fallback:0")
                        .setAttribute("start", "0")
                        .setAttribute(
                            "end",
                            Character.codePointCount(quote, 0, quote.length).toString(),
                        )
                    val fallback =
                        packet.addChild("fallback", "urn:xmpp:fallback:0")
                            .setAttribute("for", "urn:xmpp:reactions:0")
                    fallback.addChild("body", "urn:xmpp:fallback:0")
                }

                service.sendMessagePacket(conversation.getAccount() ?: throw NullPointerException("conversation has no account"), packet)
                message.replaceReactions(combinedReactions)
                service.updateMessage(message, false)
            }

            return true
        }
    }
}
