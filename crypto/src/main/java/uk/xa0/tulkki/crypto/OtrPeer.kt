package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.xmpp.chatstate.ChatState

/**
 * The OTR half of a conversation, in island-owned vocabulary.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6) names this port
 * `OtrPeer`. It is a refinement of [OmemoConversation] because the OTR engine owns the SMP state
 * that the OMEMO half never reads; `uk.xa0.tulkki.data.model.Conversation` implements both, and
 * `Conversation.Smp` is untouched — the port exposes setters instead of the two public fields the
 * engine used to assign, so no island-internal class changes shape.
 */
interface OtrPeer : OmemoConversation {

    fun setSmpStatus(status: Int)

    fun setSmpHint(hint: String?)

    fun getSmpStatus(): Int

    fun getSmpHint(): String?

    fun getLastReceivedOtrMessageId(): String?

    fun setOutgoingChatState(state: ChatState): Boolean

    fun getOutgoingChatState(): ChatState

    companion object {
        const val SMP_STATUS_NONE = 0
        const val SMP_STATUS_CONTACT_REQUESTED = 1
        const val SMP_STATUS_FAILED = 3
        const val SMP_STATUS_VERIFIED = 4
    }
}
