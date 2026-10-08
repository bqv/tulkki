package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.libs.Jid

/**
 * What the crypto island needs from a message, in island-owned vocabulary.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6); implemented by
 * `uk.xa0.tulkki.data.model.Message`. The encryption/status/type constants live here and the
 * model class points at them, so there is exactly one spelling of each value the database stores.
 */
interface OmemoMessage {

    fun getEncryption(): Int

    fun setEncryption(encryption: Int)

    fun getType(): Int

    fun getBody(): String

    fun getRawBody(): String?

    fun setBody(body: String?)

    fun setEncryptedBody(body: String?)

    fun setRelativeFilePath(path: String?)

    fun getUuid(): String?

    fun getFileUrl(): String?

    fun hasFileOnRemoteHost(): Boolean

    fun isFileOrImage(): Boolean

    fun isPrivateMessage(): Boolean

    fun needsUploading(): Boolean

    fun trusted(): Boolean

    fun treatAsDownloadable(): Boolean

    fun getTrueCounterpart(): Jid?

    fun getOmemoConversation(): OmemoConversation?

    companion object {
        const val ENCRYPTION_NONE = 0
        const val ENCRYPTION_PGP = 1
        const val ENCRYPTION_DECRYPTED = 3
        const val ENCRYPTION_DECRYPTION_FAILED = 4
        const val ENCRYPTION_AXOLOTL = 5

        const val TYPE_TEXT = 0

        const val STATUS_SEND_FAILED = 3
    }
}
