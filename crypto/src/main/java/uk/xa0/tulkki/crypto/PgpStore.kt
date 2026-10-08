package uk.xa0.tulkki.crypto

import android.os.Bundle

import java.io.Closeable
import java.io.File
import java.security.cert.X509Certificate
import java.util.function.Consumer

import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord

import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Everything the crypto island needs from the application side that is not a model object.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6) names this port
 * `PgpStore`. It carries three families of operation, all expressed in island-owned
 * vocabulary:
 *
 * - the message/conversation/file operations the engine reaches through the connection service
 *   (`find`, `updateMessage`, `sendMessage`, `getFileBackend().getFile`,
 *   `createNewDownloadConnection`, ...). These are deliberately behind a
 *   `:data`-implemented port so that the island's own signatures do not move and the
 *   `:xmpp` service keeps the parameter types pair 9 will retype;
 * - the OMEMO key/session database, whose concrete parameter is an account the island may not
 *   name — the implementation closes over it instead;
 * - the two static helpers the island used to call on `MimeUtils` and
 *   `FileBackend.close`.
 *
 * Implemented by `uk.xa0.tulkki.data.CryptoStore`, which `Account` hands out.
 */
interface PgpStore {

    // -- the connection service, with the concrete model arguments kept out of the island -------

    fun findConversation(jid: Jid): OtrPeer?

    fun findConversation(account: OmemoAccount, jid: Jid): OtrPeer?

    fun updateConversationUi()

    fun updateAccountUi()

    fun updateMessage(message: OmemoMessage)

    fun updateMessage(message: OmemoMessage, includeBody: Boolean)

    fun pushNotification(message: OmemoMessage)

    fun sendMessage(message: OmemoMessage)

    fun resendMessage(message: OmemoMessage, delay: Boolean, previewedLinks: Boolean)

    fun markMessage(message: OmemoMessage, status: Int)

    fun sendMessagePacket(packet: Message)

    fun publishDisplayName(account: OmemoAccount)

    fun syncRosterToDisk(account: OmemoAccount)

    fun updateAccount()

    fun sendIqPacket(account: OmemoAccount, packet: Iq, callback: Consumer<Iq>)

    fun pushNodeConfiguration(
        account: OmemoAccount,
        node: String,
        options: Bundle,
        callback: uk.xa0.tulkki.xmpp.services.OnConfigurationPushed
    )

    fun deletePepNode(account: OmemoAccount, node: String)

    // -- files, mime and notifications ----------------------------------------------------------

    fun getFile(message: OmemoMessage, decrypted: Boolean): File

    /**
     * `mime` is nullable because the implementation's own `FileBackend.getStorageLocation` opens
     * with `Strings.isNullOrEmpty(mime)`, and because [mimeForExtension] answers null for an
     * extension it does not know.
     */
    fun getStorageLocation(message: OmemoMessage, filename: String, mime: String?): File

    fun updateFileParams(message: OmemoMessage, url: String?)

    fun updateMediaScanner(file: File, callback: Runnable)

    fun close(stream: Closeable)

    /**
     * Null iff the path carries no extension (`MimeUtils.extractRelevantExtension`), and the one
     * caller relies on exactly that: `PgpDecryptionService` renames the decrypted file only when the
     * original has an extension and the output file's name has none. Declared non-null it made that
     * comparison constant, so the rename never happened.
     */
    fun relevantExtension(path: String): String?

    /** Null iff the extension has no registered MIME type (`MimeUtils.guessMimeTypeFromExtension`). */
    fun mimeForExtension(extension: String): String?

    fun createNewDownloadConnection(message: OmemoMessage)

    fun getAutoAcceptFileSize(): Long

    fun ignoreDeletion(absolutePath: String)

    fun isBtbvEnabled(): Boolean

    // -- the OMEMO database ---------------------------------------------------------------------

    fun wipeAxolotlDb()

    fun getFingerprintStatus(fingerprint: String): FingerprintStatus?

    fun loadOwnIdentityKeyPair(): IdentityKeyPair?

    fun storeOwnIdentityKeyPair(identityKeyPair: IdentityKeyPair)

    fun storeIdentityKey(name: String, identityKey: IdentityKey, status: FingerprintStatus)

    fun loadIdentityKeys(name: String): Set<IdentityKey>

    fun loadIdentityKeys(name: String, status: FingerprintStatus): Set<IdentityKey>

    fun numTrustedKeys(name: String): Long

    fun storePreVerification(name: String, fingerprint: String, status: FingerprintStatus)

    fun setIdentityKeyTrust(fingerprint: String, status: FingerprintStatus): Boolean

    fun setIdentityKeyCertificate(fingerprint: String, certificate: X509Certificate): Boolean

    fun getIdentityKeyCertifcate(fingerprint: String): X509Certificate?

    fun loadSession(address: SignalProtocolAddress): SessionRecord?

    fun getSubDeviceSessions(address: SignalProtocolAddress): List<Int>

    fun getKnownSignalAddresses(): List<String>

    fun containsSession(address: SignalProtocolAddress): Boolean

    fun storeSession(address: SignalProtocolAddress, session: SessionRecord)

    fun deleteSession(address: SignalProtocolAddress)

    fun deleteAllSessions(address: SignalProtocolAddress)

    fun loadPreKey(preKeyId: Int): PreKeyRecord?

    fun containsPreKey(preKeyId: Int): Boolean

    fun storePreKey(record: PreKeyRecord)

    fun deletePreKey(preKeyId: Int): Int

    fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord?

    fun loadSignedPreKeys(): List<SignedPreKeyRecord>

    fun getSignedPreKeysCount(): Int

    fun containsSignedPreKey(signedPreKeyId: Int): Boolean

    fun storeSignedPreKey(record: SignedPreKeyRecord)

    fun deleteSignedPreKey(signedPreKeyId: Int)

    fun getLastTimeFingerprintUsed(fingerprint: String): Long
}
