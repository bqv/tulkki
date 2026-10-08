package uk.xa0.tulkki.crypto.axolotl

import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.crypto.PgpStore

import android.util.Log
import android.util.LruCache
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import java.security.cert.X509Certificate
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.state.IdentityKeyStore.Direction
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.util.KeyHelper

class SQLiteAxolotlStore(
    private val account: OmemoAccount,
    private val mXmppConnectionService: XmppConnectionService
) : SignalProtocolStore {

    private var identityKeyPair: IdentityKeyPair? = null
    private var localRegistrationId: Int = 0
    private var currentPreKeyId: Int = 0

    private val preKeysMarkedForRemoval: HashSet<Int> = HashSet()

    private val trustCache: LruCache<String, FingerprintStatus> =
        object : LruCache<String, FingerprintStatus>(NUM_TRUSTS_TO_CACHE) {
            override fun create(fingerprint: String): FingerprintStatus? =
                store().getFingerprintStatus(fingerprint)
        }

    init {
        this.localRegistrationId = loadRegistrationId()
        this.currentPreKeyId = loadCurrentPreKeyId()
    }

    private fun store(): PgpStore =
        this.account.getPgpStore() ?: throw NullPointerException("pgpStore")

    fun getCurrentPreKeyId(): Int = currentPreKeyId

    // --------------------------------------
    // IdentityKeyStore
    // --------------------------------------

    private fun loadIdentityKeyPair(): IdentityKeyPair {
        synchronized(mXmppConnectionService) {
            val ownKey = store().loadOwnIdentityKeyPair()

            if (ownKey != null) {
                return ownKey
            } else {
                Log.i(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account) + "Could not retrieve own IdentityKeyPair"
                )
                val generated = generateIdentityKeyPair()
                store().storeOwnIdentityKeyPair(generated)
                return generated
            }
        }
    }

    private fun loadRegistrationId(): Int = loadRegistrationId(false)

    private fun loadRegistrationId(regenerate: Boolean): Int {
        val regIdString = this.account.getKey(JSONKEY_REGISTRATION_ID)
        val regId: Int
        if (!regenerate && regIdString != null) {
            regId = Integer.valueOf(regIdString)
        } else {
            Log.i(
                Config.LOGTAG,
                AxolotlService.getLogprefix(account) +
                    "Could not retrieve axolotl registration id for account " +
                    account.getJid()
            )
            regId = generateRegistrationId()
            val success =
                this.account.setKey(JSONKEY_REGISTRATION_ID, regId.toString())
            if (success) {
                store().updateAccount()
            } else {
                Log.e(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account) +
                        "Failed to write new key to the database!"
                )
            }
        }
        return regId
    }

    private fun loadCurrentPreKeyId(): Int {
        val prekeyIdString = this.account.getKey(JSONKEY_CURRENT_PREKEY_ID)
        val prekeyId: Int
        if (prekeyIdString != null) {
            prekeyId = Integer.valueOf(prekeyIdString)
        } else {
            Log.w(
                Config.LOGTAG,
                AxolotlService.getLogprefix(account) +
                    "Could not retrieve current prekey id for account " +
                    account.getJid()
            )
            prekeyId = 0
        }
        return prekeyId
    }

    fun regenerate() {
        store().wipeAxolotlDb()
        trustCache.evictAll()
        account.setKey(JSONKEY_CURRENT_PREKEY_ID, 0.toString())
        identityKeyPair = loadIdentityKeyPair()
        localRegistrationId = loadRegistrationId(true)
        currentPreKeyId = 0
        mXmppConnectionService.updateAccountUi()
    }

    /**
     * Get the local client's identity key pair.
     *
     * @return The local client's persistent identity key pair.
     */
    override fun getIdentityKeyPair(): IdentityKeyPair {
        val existing = identityKeyPair
        if (existing != null) {
            return existing
        }
        val loaded = loadIdentityKeyPair()
        identityKeyPair = loaded
        return loaded
    }

    /**
     * Return the local client's registration ID.
     *
     * Clients should maintain a registration ID, a random number between 1 and 16380 that's
     * generated once at install time.
     *
     * @return the local client's registration ID.
     */
    override fun getLocalRegistrationId(): Int = localRegistrationId

    /**
     * Save a remote client's identity key
     *
     * Store a remote client's identity key as trusted.
     *
     * @param address The address of the remote client.
     * @param identityKey The remote client's identity key.
     * @return true on success
     */
    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): Boolean {
        if (!store().loadIdentityKeys(address.getName()).contains(identityKey)) {
            val fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize())
            val current = getFingerprintStatus(fingerprint)
            val status: FingerprintStatus
            if (current == null) {
                val axolotlService = account.getAxolotlService()
                    ?: throw NullPointerException("axolotlService")
                if (store().isBtbvEnabled() &&
                    !axolotlService.hasVerifiedKeys(address.getName())
                ) {
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString() +
                            ": blindly trusted " +
                            fingerprint +
                            " of " +
                            address.getName()
                    )
                    status = FingerprintStatus.createActiveTrusted()
                } else {
                    status = FingerprintStatus.createActiveUndecided()
                }
            } else {
                status = current.toActive()
            }
            store().storeIdentityKey(address.getName(), identityKey, status)
            trustCache.remove(fingerprint)
        }
        return true
    }

    /**
     * Verify a remote client's identity key.
     *
     * Determine whether a remote client's identity is trusted. Convention is that the TextSecure
     * protocol is 'trust on first use.' This means that an identity key is considered 'trusted' if
     * there is no entry for the recipient in the local store, or if it matches the saved key for a
     * recipient in the local store. Only if it mismatches an entry in the local store is it
     * considered 'untrusted.'
     *
     * @param identityKey The identity key to verify.
     * @return true if trusted, false if untrusted.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: Direction
    ): Boolean = true

    fun getFingerprintStatus(fingerprint: String?): FingerprintStatus? =
        if (fingerprint == null) null else trustCache.get(fingerprint)

    fun setFingerprintStatus(fingerprint: String, status: FingerprintStatus) {
        store().setIdentityKeyTrust(fingerprint, status)
        trustCache.remove(fingerprint)
    }

    fun setFingerprintCertificate(fingerprint: String, x509Certificate: X509Certificate) {
        store().setIdentityKeyCertificate(fingerprint, x509Certificate)
    }

    fun getFingerprintCertificate(fingerprint: String): X509Certificate? =
        store().getIdentityKeyCertifcate(fingerprint)

    fun getContactKeysWithTrust(bareJid: String, status: FingerprintStatus): Set<IdentityKey> =
        store().loadIdentityKeys(bareJid, status)

    fun getContactNumTrustedKeys(bareJid: String): Long = store().numTrustedKeys(bareJid)

    // --------------------------------------
    // SessionStore
    // --------------------------------------

    /**
     * Returns a copy of the [SessionRecord] corresponding to the recipientId + deviceId
     * tuple, or a new SessionRecord if one does not currently exist.
     *
     * It is important that implementations return a copy of the current durable information. The
     * returned SessionRecord may be modified, but those changes should not have an effect on the
     * durable session state (what is returned by subsequent calls to this method) without the store
     * method being called here first.
     *
     * @param address The name and device ID of the remote client.
     * @return a copy of the SessionRecord corresponding to the recipientId + deviceId tuple, or a
     *     new SessionRecord if one does not currently exist.
     */
    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val session = store().loadSession(address)
        return session ?: SessionRecord()
    }

    /**
     * Returns all known devices with active sessions for a recipient
     *
     * @param name the name of the client.
     * @return all known sub-devices with active sessions.
     */
    override fun getSubDeviceSessions(name: String): List<Int> =
        store().getSubDeviceSessions(SignalProtocolAddress(name, 0))

    fun getKnownAddresses(): List<String> = store().getKnownSignalAddresses()

    /**
     * Commit to storage the [SessionRecord] for a given recipientId + deviceId tuple.
     *
     * @param address the address of the remote client.
     * @param record the current SessionRecord for the remote client.
     */
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        store().storeSession(address, record)
    }

    /**
     * Determine whether there is a committed [SessionRecord] for a recipientId + deviceId
     * tuple.
     *
     * @param address the address of the remote client.
     * @return true if a [SessionRecord] exists, false otherwise.
     */
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        store().containsSession(address)

    /**
     * Remove a [SessionRecord] for a recipientId + deviceId tuple.
     *
     * @param address the address of the remote client.
     */
    override fun deleteSession(address: SignalProtocolAddress) {
        store().deleteSession(address)
    }

    /**
     * Remove the [SessionRecord]s corresponding to all devices of a recipientId.
     *
     * @param name the name of the remote client.
     */
    override fun deleteAllSessions(name: String) {
        val address = SignalProtocolAddress(name, 0)
        store().deleteAllSessions(address)
    }

    // --------------------------------------
    // PreKeyStore
    // --------------------------------------

    /**
     * Load a local PreKeyRecord.
     *
     * @param preKeyId the ID of the local PreKeyRecord.
     * @return the corresponding PreKeyRecord.
     * @throws InvalidKeyIdException when there is no corresponding PreKeyRecord.
     */
    @Throws(InvalidKeyIdException::class)
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val record = store().loadPreKey(preKeyId)
        if (record == null) {
            throw InvalidKeyIdException("No such PreKeyRecord: $preKeyId")
        }
        return record
    }

    /**
     * Store a local PreKeyRecord.
     *
     * @param preKeyId the ID of the PreKeyRecord to store.
     * @param record the PreKeyRecord.
     */
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        store().storePreKey(record)
        currentPreKeyId = preKeyId
        val success =
            this.account.setKey(JSONKEY_CURRENT_PREKEY_ID, preKeyId.toString())
        if (success) {
            store().updateAccount()
        } else {
            Log.e(
                Config.LOGTAG,
                AxolotlService.getLogprefix(account) +
                    "Failed to write new prekey id to the database!"
            )
        }
    }

    /**
     * @param preKeyId A PreKeyRecord ID.
     * @return true if the store has a record for the preKeyId, otherwise false.
     */
    override fun containsPreKey(preKeyId: Int): Boolean = store().containsPreKey(preKeyId)

    /**
     * Delete a PreKeyRecord from local storage.
     *
     * @param preKeyId The ID of the PreKeyRecord to remove.
     */
    override fun removePreKey(preKeyId: Int) {
        Log.d(Config.LOGTAG, "mark prekey for removal $preKeyId")
        synchronized(preKeysMarkedForRemoval) {
            preKeysMarkedForRemoval.add(preKeyId)
        }
    }

    fun flushPreKeys(): Boolean {
        Log.d(Config.LOGTAG, "flushing pre keys")
        var count = 0
        synchronized(preKeysMarkedForRemoval) {
            for (preKeyId in preKeysMarkedForRemoval) {
                count += store().deletePreKey(preKeyId)
            }
            preKeysMarkedForRemoval.clear()
        }
        return count > 0
    }

    // --------------------------------------
    // SignedPreKeyStore
    // --------------------------------------

    /**
     * Load a local SignedPreKeyRecord.
     *
     * @param signedPreKeyId the ID of the local SignedPreKeyRecord.
     * @return the corresponding SignedPreKeyRecord.
     * @throws InvalidKeyIdException when there is no corresponding SignedPreKeyRecord.
     */
    @Throws(InvalidKeyIdException::class)
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val record = store().loadSignedPreKey(signedPreKeyId)
        if (record == null) {
            throw InvalidKeyIdException("No such SignedPreKeyRecord: $signedPreKeyId")
        }
        return record
    }

    /**
     * Load all local SignedPreKeyRecords.
     *
     * @return All stored SignedPreKeyRecords.
     */
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = store().loadSignedPreKeys()

    fun getSignedPreKeysCount(): Int = store().getSignedPreKeysCount()

    /**
     * Store a local SignedPreKeyRecord.
     *
     * @param signedPreKeyId the ID of the SignedPreKeyRecord to store.
     * @param record the SignedPreKeyRecord.
     */
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        store().storeSignedPreKey(record)
    }

    /**
     * @param signedPreKeyId A SignedPreKeyRecord ID.
     * @return true if the store has a record for the signedPreKeyId, otherwise false.
     */
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        store().containsSignedPreKey(signedPreKeyId)

    /**
     * Delete a SignedPreKeyRecord from local storage.
     *
     * @param signedPreKeyId The ID of the SignedPreKeyRecord to remove.
     */
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        store().deleteSignedPreKey(signedPreKeyId)
    }

    fun preVerifyFingerprint(account: OmemoAccount, name: String, fingerprint: String) {
        store().storePreVerification(name, fingerprint, FingerprintStatus.createInactiveVerified())
    }

    companion object {

        const val PREKEY_TABLENAME = "prekeys"
        const val SIGNED_PREKEY_TABLENAME = "signed_prekeys"
        const val SESSION_TABLENAME = "sessions"
        const val IDENTITIES_TABLENAME = "identities"
        const val ACCOUNT = "account"
        const val DEVICE_ID = "device_id"
        const val ID = "id"
        const val KEY = "key"
        const val FINGERPRINT = "fingerprint"
        const val NAME = "name"
        const val TRUSTED = "trusted" // no longer used
        const val TRUST = "trust"
        const val ACTIVE = "active"
        const val LAST_ACTIVATION = "last_activation"
        const val OWN = "ownkey"
        const val CERTIFICATE = "certificate"

        const val JSONKEY_REGISTRATION_ID = "axolotl_reg_id"
        const val JSONKEY_CURRENT_PREKEY_ID = "axolotl_cur_prekey_id"

        private const val NUM_TRUSTS_TO_CACHE = 100

        private fun generateIdentityKeyPair(): IdentityKeyPair {
            Log.i(
                Config.LOGTAG,
                OmemoSessionPort.LOGPREFIX + " : " + "Generating axolotl IdentityKeyPair..."
            )
            val identityKeyPairKeys: ECKeyPair = Curve.generateKeyPair()
            return IdentityKeyPair(
                IdentityKey(identityKeyPairKeys.getPublicKey()),
                identityKeyPairKeys.getPrivateKey()
            )
        }

        private fun generateRegistrationId(): Int {
            Log.i(
                Config.LOGTAG,
                OmemoSessionPort.LOGPREFIX + " : " + "Generating axolotl registration ID..."
            )
            return KeyHelper.generateRegistrationId(true)
        }
    }
}
