package uk.xa0.tulkki.data

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

import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.crypto.OmemoMessage
import uk.xa0.tulkki.crypto.OtrPeer
import uk.xa0.tulkki.crypto.PgpStore
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message as StanzaMessage
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The application side of the crypto island's ports (pair 5, docs/MIGRATION.md "The cycle rules" §3
 * D6).
 *
 * This class is where the island's needs that are *not* model objects are answered: the connection
 * service's message/conversation/file operations, and the OMEMO key/session database. Both are
 * addressed with the concrete [Account] this instance closes over, because the island may not name a
 * `uk.xa0.tulkki.data` type in a port signature.
 *
 * **This puts an `:xmpp` call behind a `:data` class, deliberately.** `XmppConnectionService.find`,
 * `updateMessage`, `sendMessage`, `getFileBackend().getFile` and `createNewDownloadConnection` all
 * take a concrete model object; the island cannot call them once it only holds a port, and widening
 * their parameters would drag `XmppConnectionService` — the boundary's one serialisation point —
 * into this commit. `:data`'s `allow` list already names `:xmpp`, so the call is a legal downward
 * edge, and the delegation keeps the island's own signatures unmoved.
 */
class CryptoStore(
    private val service: XmppConnectionService,
    private val account: Account,
) : PgpStore {

    private fun asMessage(message: OmemoMessage): Message = message as Message

    private fun asAccount(account: OmemoAccount): Account = account as Account

    // -- the connection service -----------------------------------------------------------------

    override fun findConversation(jid: Jid): OtrPeer? = service.find(account, jid) as OtrPeer?

    override fun findConversation(account: OmemoAccount, jid: Jid): OtrPeer? =
        service.find(asAccount(account), jid) as OtrPeer?

    override fun updateConversationUi() {
        service.updateConversationUi()
    }

    override fun updateAccountUi() {
        service.updateAccountUi()
    }

    override fun updateMessage(message: OmemoMessage) {
        service.updateMessage(asMessage(message))
    }

    override fun updateMessage(message: OmemoMessage, includeBody: Boolean) {
        service.updateMessage(asMessage(message), includeBody)
    }

    override fun pushNotification(message: OmemoMessage) {
        service.getNotificationService().push(asMessage(message))
    }

    override fun sendMessage(message: OmemoMessage) {
        service.sendMessage(asMessage(message))
    }

    override fun resendMessage(message: OmemoMessage, delay: Boolean, previewedLinks: Boolean) {
        service.resendMessage(asMessage(message), delay, previewedLinks)
    }

    override fun markMessage(message: OmemoMessage, status: Int) {
        service.markMessage(asMessage(message), status)
    }

    override fun sendMessagePacket(packet: StanzaMessage) {
        service.sendMessagePacket(account, packet)
    }

    override fun publishDisplayName(account: OmemoAccount) {
        service.publishDisplayName(asAccount(account))
    }

    override fun syncRosterToDisk(account: OmemoAccount) {
        service.syncRosterToDisk(asAccount(account))
    }

    override fun updateAccount() {
        DatabaseBackend.get().updateAccount(account)
    }

    override fun sendIqPacket(account: OmemoAccount, packet: Iq, callback: Consumer<Iq>) {
        service.sendIqPacket(asAccount(account), packet, callback)
    }

    override fun pushNodeConfiguration(
        account: OmemoAccount,
        node: String,
        options: Bundle,
        callback: uk.xa0.tulkki.xmpp.services.OnConfigurationPushed,
    ) {
        service.pushNodeConfiguration(asAccount(account), node, options, callback)
    }

    override fun deletePepNode(account: OmemoAccount, node: String) {
        service.deletePepNode(asAccount(account), node)
    }

    // -- files, mime and notifications ----------------------------------------------------------

    override fun getFile(message: OmemoMessage, decrypted: Boolean): File =
        FileBackends.get().getFile(asMessage(message), decrypted)

    override fun getStorageLocation(message: OmemoMessage, filename: String, mime: String?): File =
        FileBackends.get().getStorageLocation(asMessage(message), filename, mime)

    override fun updateFileParams(message: OmemoMessage, url: String?) {
        FileBackends.get().updateFileParams(asMessage(message), url)
    }

    override fun updateMediaScanner(file: File, callback: Runnable) {
        FileBackends.get().updateMediaScanner(file, callback)
    }

    override fun close(stream: Closeable) {
        FileBackend.close(stream)
    }

    override fun relevantExtension(path: String): String? = MimeUtils.extractRelevantExtension(path)

    override fun mimeForExtension(extension: String): String? =
        MimeUtils.guessMimeTypeFromExtension(extension)

    override fun createNewDownloadConnection(message: OmemoMessage) {
        service.getHttpConnectionManager().createNewDownloadConnection(asMessage(message))
    }

    override fun getAutoAcceptFileSize(): Long = service.getHttpConnectionManager().getAutoAcceptFileSize()

    override fun ignoreDeletion(absolutePath: String) {
        synchronized(service.FILENAMES_TO_IGNORE_DELETION) {
            service.FILENAMES_TO_IGNORE_DELETION.add(absolutePath)
        }
    }

    override fun isBtbvEnabled(): Boolean {
        // Tulkki: 3.7 pair 9 cluster (b) retyped the island's accessor to AppSettingsRef; this is
        // `:data` reading its own class back, which needs no import (same package) and no ref member
        // the island does not read.
        return (service.getAppSettings() as AppSettings).isBTBVEnabled()
    }

    // -- the OMEMO database ---------------------------------------------------------------------

    override fun wipeAxolotlDb() {
        DatabaseBackend.get().wipeAxolotlDb(account)
    }

    override fun getFingerprintStatus(fingerprint: String): FingerprintStatus? =
        DatabaseBackend.get().getFingerprintStatus(account, fingerprint)

    override fun loadOwnIdentityKeyPair(): IdentityKeyPair? =
        DatabaseBackend.get().loadOwnIdentityKeyPair(account)

    override fun storeOwnIdentityKeyPair(identityKeyPair: IdentityKeyPair) {
        DatabaseBackend.get().storeOwnIdentityKeyPair(account, identityKeyPair)
    }

    override fun storeIdentityKey(
        name: String,
        identityKey: IdentityKey,
        status: FingerprintStatus,
    ) {
        DatabaseBackend.get().storeIdentityKey(account, name, identityKey, status)
    }

    override fun loadIdentityKeys(name: String): Set<IdentityKey> =
        DatabaseBackend.get().loadIdentityKeys(account, name)

    override fun loadIdentityKeys(name: String, status: FingerprintStatus): Set<IdentityKey> =
        DatabaseBackend.get().loadIdentityKeys(account, name, status)

    override fun numTrustedKeys(name: String): Long =
        DatabaseBackend.get().numTrustedKeys(account, name)

    override fun storePreVerification(name: String, fingerprint: String, status: FingerprintStatus) {
        DatabaseBackend.get().storePreVerification(account, name, fingerprint, status)
    }

    override fun setIdentityKeyTrust(fingerprint: String, status: FingerprintStatus): Boolean =
        DatabaseBackend.get().setIdentityKeyTrust(account, fingerprint, status)

    override fun setIdentityKeyCertificate(
        fingerprint: String,
        certificate: X509Certificate,
    ): Boolean = DatabaseBackend.get().setIdentityKeyCertificate(account, fingerprint, certificate)

    override fun getIdentityKeyCertifcate(fingerprint: String): X509Certificate? =
        DatabaseBackend.get().getIdentityKeyCertifcate(account, fingerprint)

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        DatabaseBackend.get().loadSession(account, address)

    override fun getSubDeviceSessions(address: SignalProtocolAddress): List<Int> =
        DatabaseBackend.get().getSubDeviceSessions(account, address)

    override fun getKnownSignalAddresses(): List<String> =
        DatabaseBackend.get().getKnownSignalAddresses(account)

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        DatabaseBackend.get().containsSession(account, address)

    override fun storeSession(address: SignalProtocolAddress, session: SessionRecord) {
        DatabaseBackend.get().storeSession(account, address, session)
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        DatabaseBackend.get().deleteSession(account, address)
    }

    override fun deleteAllSessions(address: SignalProtocolAddress) {
        DatabaseBackend.get().deleteAllSessions(account, address)
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord? =
        DatabaseBackend.get().loadPreKey(account, preKeyId)

    override fun containsPreKey(preKeyId: Int): Boolean =
        DatabaseBackend.get().containsPreKey(account, preKeyId)

    override fun storePreKey(record: PreKeyRecord) {
        DatabaseBackend.get().storePreKey(account, record)
    }

    override fun deletePreKey(preKeyId: Int): Int = DatabaseBackend.get().deletePreKey(account, preKeyId)

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord? =
        DatabaseBackend.get().loadSignedPreKey(account, signedPreKeyId)

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        DatabaseBackend.get().loadSignedPreKeys(account)

    override fun getSignedPreKeysCount(): Int = DatabaseBackend.get().getSignedPreKeysCount(account)

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        DatabaseBackend.get().containsSignedPreKey(account, signedPreKeyId)

    override fun storeSignedPreKey(record: SignedPreKeyRecord) {
        DatabaseBackend.get().storeSignedPreKey(account, record)
    }

    override fun deleteSignedPreKey(signedPreKeyId: Int) {
        DatabaseBackend.get().deleteSignedPreKey(account, signedPreKeyId)
    }

    override fun getLastTimeFingerprintUsed(fingerprint: String): Long =
        DatabaseBackend.get().getLastTimeFingerprintUsed(account, fingerprint)
}
