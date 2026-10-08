package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.libs.Jid

/** What the crypto island needs from a conversation's MUC options. */
interface OmemoMucOptions {

    fun getMembers(includeDomains: Boolean): List<Jid>

    fun getUserCount(): Int

    fun getPgpKeyIds(): LongArray
}
