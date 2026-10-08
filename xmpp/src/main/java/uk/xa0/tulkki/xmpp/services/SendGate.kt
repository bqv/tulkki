package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the send path's translation decisions, over the model types this file already speaks.
 *
 * The engine that makes them (`uk.xa0.tulkki.translation.OutgoingTranslation`) is not this module's
 * to name, so the island asks here and the composition root implements; converted from the Java.
 *
 * **`refuseQuickReply`'s two parameters are nullable, and that is the implementation's own
 * reading.** `XmppTulkkiHost.kt:329` - the only implementation - already declares
 * `refuseQuickReply(conversation: ConversationRef?, body: String?)` and delegates with
 * `OutgoingTranslation.refuseQuickReply(service, conversation as Conversation?, body)`, i.e. it
 * deliberately tolerates a null conversation and a blank-or-absent body. Both callers
 * (`PushReply.kt:92` and the notification quick-reply path) pass values they hold, so widening the
 * declaration breaks nobody and a non-null spelling would instead break the implementation.
 *
 * `holdBack` and `alreadyHeld` are non-null on both sides: their implementation
 * (`XmppTulkkiHost.kt:336/339`) declares them non-null and casts the refs before delegating.
 */
interface SendGate {
    fun refuseQuickReply(conversation: ConversationRef?, body: String?): Boolean

    fun holdBack(message: MessageRef): Boolean

    fun alreadyHeld(conversation: ConversationRef, message: MessageRef): Boolean
}
