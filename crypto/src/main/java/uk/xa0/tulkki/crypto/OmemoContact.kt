package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.libs.Jid

/** What the crypto island needs from a contact; implemented by `uk.xa0.tulkki.data.model.Contact`. */
interface OmemoContact {

    fun getAccount(): OmemoAccount

    fun getJid(): Jid

    fun getPgpKeyId(): Long

    fun isSelf(): Boolean

    fun showInRoster(): Boolean

    fun setCommonName(name: String?)

    fun addOtrFingerprint(fingerprint: String): Boolean
}
