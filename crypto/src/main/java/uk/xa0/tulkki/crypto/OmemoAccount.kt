package uk.xa0.tulkki.crypto

import org.json.JSONObject

import uk.xa0.tulkki.crypto.axolotl.AxolotlService
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection

/**
 * What the crypto island needs from an account, in island-owned vocabulary.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6). `uk.xa0.tulkki.data.model.Account`
 * implements it; every member here already existed on that class, so the `:data` side only carries
 * the `implements` clause and the store accessor.
 */
interface OmemoAccount {

    fun getJid(): Jid

    fun getXmppConnection(): XmppConnection?

    fun getRoster(): OmemoRoster

    fun getKeys(): JSONObject

    fun getKey(name: String): String?

    fun setKey(name: String, value: String?): Boolean

    fun getPgpId(): Long

    fun getPrivateKeyAlias(): String?

    fun isOptionSet(option: Int): Boolean

    fun setOption(option: Int, value: Boolean): Boolean

    fun getAxolotlService(): AxolotlService?

    fun getPgpStore(): PgpStore?

    companion object {
        const val OPTION_REQUIRES_ACCESS_MODE_CHANGE = 5
    }
}
