package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.libs.Jid

/**
 * What the crypto island needs from a conversation, in island-owned vocabulary.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6); implemented by
 * `uk.xa0.tulkki.data.model.Conversation`.
 */
interface OmemoConversation {

    fun getMode(): Int

    fun getAccount(): OmemoAccount?

    fun getContact(): OmemoContact

    fun getJid(): Jid?

    fun getName(): CharSequence

    fun getMucOptions(): OmemoMucOptions

    fun getAcceptedCryptoTargets(): List<Jid>

    fun sentMessagesCount(): Int

    companion object {
        const val MODE_MULTI = 1
        const val MODE_SINGLE = 0
    }
}
