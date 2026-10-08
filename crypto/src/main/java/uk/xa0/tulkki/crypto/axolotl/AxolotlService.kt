package uk.xa0.tulkki.crypto.axolotl

import android.os.Bundle
import android.security.KeyChain
import android.util.Log
import android.util.Pair

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.UntrustedIdentityException
import org.whispersystems.libsignal.ecc.ECPublicKey
import org.whispersystems.libsignal.state.PreKeyBundle
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.util.KeyHelper

import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Collections
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.crypto.OmemoContact
import uk.xa0.tulkki.crypto.OmemoConversation
import uk.xa0.tulkki.crypto.OmemoMessage
import uk.xa0.tulkki.crypto.PgpStore
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoFailure
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.FetchStatus
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.KeyTransport
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.OmemoVerifiedPayload
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.Plaintext
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.jingle.DescriptionTransport
import uk.xa0.tulkki.xmpp.jingle.OmemoVerification
import uk.xa0.tulkki.xmpp.jingle.OmemoVerifiedRtpContentMap
import uk.xa0.tulkki.xmpp.jingle.RtpContentMap
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.SerialSingleThreadExecutor
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The OMEMO engine, ported from Java by the `axolotl` lane (slice 2; slice 1 lifted the three
 * session maps out to [AxolotlSessionMaps]).
 *
 * Decisions taken rather than inherited, in the `cryptoport` lane's house style:
 *
 * 1. **`getLogprefix` is a `@JvmStatic` companion member.** Java callers across the tree
 *    (`DatabaseBackend`, `ConversationFragment`) spell it `AxolotlService.getLogprefix(account)`.
 * 2. **The constructor's null guard is the non-null parameter contract.** Java threw
 *    `IllegalArgumentException("account and service cannot be null")`; Kotlin rejects null at the
 *    boundary with the intrinsic `NullPointerException`. No caller passes null.
 * 3. **Values Java dereferenced unguarded take `?: throw NullPointerException(...)`** where the
 *    called member is Kotlin-declared non-null (the `cryptoport` precedent), and `!!` nowhere.
 *    Every such site is a place Java would have thrown the same `NullPointerException`.
 * 4. **`OmemoAccount.getPgpStore()` is nullable**, so [store] uses the `?: throw` shape
 *    `SQLiteAxolotlStore` already established for the same accessor.
 * 5. **`buildSessionFromPEP` catches `Exception` and rethrows anything that is neither
 *    `UntrustedIdentityException` nor `InvalidKeyException`**, because Kotlin has no multi-catch and
 *    a plain `catch (Exception)` would swallow runtime failures Java let propagate.
 */
class AxolotlService(
    private val account: OmemoAccount,
    private val mXmppConnectionService: XmppConnectionService,
) : OmemoSessionPort {

    private val axolotlStore: SQLiteAxolotlStore
    private val sessions: SessionMap
    private val deviceIds: MutableMap<Jid, MutableSet<Int>> = HashMap()
    private val messageCache: HashMap<String?, XmppAxolotlMessage> = HashMap()
    private val fetchStatusMap: FetchStatusMap
    private val fetchDeviceListStatus: MutableMap<Jid, Boolean> = HashMap()
    private val fetchDeviceIdsMap: HashMap<Jid, MutableList<OnDeviceIdsFetched>> = HashMap()
    private val executor: SerialSingleThreadExecutor
    private val healingAttempts: MutableSet<SignalProtocolAddress> = HashSet()
    private val cleanedOwnDeviceIds: HashSet<Int> = HashSet()
    private val PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT: MutableSet<Int> = HashSet()
    private var numPublishTriesOnEmptyPep = 0
    private var pepBroken = false
    private var lastDeviceListNotificationHash = 0
    private val postponedSessions: MutableSet<XmppAxolotlSession> = HashSet()
    private val postponedHealing: MutableSet<SignalProtocolAddress> = HashSet()
    private val changeAccessMode = AtomicBoolean(false)

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        this.axolotlStore = SQLiteAxolotlStore(this.account, this.mXmppConnectionService)
        this.sessions = SessionMap(mXmppConnectionService, axolotlStore, account)
        this.fetchStatusMap = FetchStatusMap()
        this.executor = SerialSingleThreadExecutor("Axolotl")
    }

    companion object {
        private const val NUM_KEYS_TO_PUBLISH = 100
        private const val publishTriesThreshold = 3

        @JvmStatic
        fun getLogprefix(account: OmemoAccount): String =
            OmemoSessionPort.LOGPREFIX + " (" + account.getJid().asBareJid().toString() + "): "
    }

    private fun store(): PgpStore =
        this.account.getPgpStore() ?: throw NullPointerException("pgpStore")

    override fun onStreamFeaturesAvailable() {
        val account = this.account
        val connection = account.getXmppConnection()
        if (Config.supportOmemo() && connection != null && connection.getFeatures().pep()) {
            publishBundlesIfNeeded(true, false)
        } else {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": skipping OMEMO initialization",
            )
        }
    }

    private fun hasErrorFetchingDeviceList(jid: Jid): Boolean {
        val status = fetchDeviceListStatus[jid]
        return status != null && !status
    }

    fun hasErrorFetchingDeviceList(jids: List<Jid>): Boolean {
        for (jid in jids) {
            if (hasErrorFetchingDeviceList(jid)) {
                return true
            }
        }
        return false
    }

    fun fetchMapHasErrors(jids: List<Jid>): Boolean {
        for (jid in jids) {
            val ids = deviceIds[jid]
            if (ids != null) {
                for (foreignId in ids) {
                    val address = SignalProtocolAddress(jid.toString(), foreignId)
                    if (fetchStatusMap.getAll(address.name).containsValue(FetchStatus.ERROR)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun preVerifyFingerprint(contact: OmemoContact, fingerprint: String) {
        axolotlStore.preVerifyFingerprint(
            contact.getAccount(),
            contact.getJid().asBareJid().toString(),
            fingerprint,
        )
    }

    fun preVerifyFingerprint(account: OmemoAccount, fingerprint: String) {
        axolotlStore.preVerifyFingerprint(
            account,
            account.getJid().asBareJid().toString(),
            fingerprint,
        )
    }

    override fun hasVerifiedKeys(name: String): Boolean {
        for (session in this.sessions.getAll(name).values) {
            if (session.getTrust().isVerified()) {
                return true
            }
        }
        return false
    }

    override fun getOwnFingerprint(): String =
        CryptoHelper.bytesToHex(axolotlStore.getIdentityKeyPair().getPublicKey().serialize())

    fun getKeysWithTrust(status: FingerprintStatus): Set<IdentityKey> =
        axolotlStore.getContactKeysWithTrust(account.getJid().asBareJid().toString(), status)

    fun getKeysWithTrust(status: FingerprintStatus, jid: Jid): Set<IdentityKey> =
        axolotlStore.getContactKeysWithTrust(jid.asBareJid().toString(), status)

    fun getKeysWithTrust(status: FingerprintStatus, jids: List<Jid>): Set<IdentityKey> {
        val keys = HashSet<IdentityKey>()
        for (jid in jids) {
            keys.addAll(axolotlStore.getContactKeysWithTrust(jid.toString(), status))
        }
        return keys
    }

    override fun findCounterpartsBySourceId(sid: Int): MutableSet<Jid> =
        sessions.findCounterpartsForSourceId(sid)

    fun getNumTrustedKeys(jid: Jid): Long =
        axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString())

    fun anyTargetHasNoTrustedKeys(jids: List<Jid>): Boolean {
        for (jid in jids) {
            if (axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString()) == 0L) {
                return true
            }
        }
        return false
    }

    private fun getAddressForJid(jid: Jid): SignalProtocolAddress =
        SignalProtocolAddress(jid.toString(), 0)

    fun findOwnSessions(): Collection<XmppAxolotlSession> {
        val ownAddress = getAddressForJid(account.getJid().asBareJid())
        val sessions = ArrayList(this.sessions.getAll(ownAddress.name).values)
        Collections.sort(sessions)
        return sessions
    }

    fun findSessionsForContact(contact: OmemoContact): Collection<XmppAxolotlSession> {
        val contactAddress = getAddressForJid(contact.getJid())
        val sessions = ArrayList(this.sessions.getAll(contactAddress.name).values)
        Collections.sort(sessions)
        return sessions
    }

    private fun findSessionsForConversation(
        conversation: OmemoConversation,
    ): Set<XmppAxolotlSession> {
        if (conversation.getContact().isSelf()) {
            //will be added in findOwnSessions()
            return emptySet()
        }
        val sessions = HashSet<XmppAxolotlSession>()
        for (jid in conversation.getAcceptedCryptoTargets()) {
            sessions.addAll(this.sessions.getAll(getAddressForJid(jid).name).values)
        }
        return sessions
    }

    private fun hasAny(jid: Jid): Boolean = sessions.hasAny(getAddressForJid(jid))

    override fun isPepBroken(): Boolean = this.pepBroken

    override fun resetBrokenness() {
        this.pepBroken = false
        this.numPublishTriesOnEmptyPep = 0
        this.lastDeviceListNotificationHash = 0
        this.healingAttempts.clear()
    }

    override fun clearErrorsInFetchStatusMap(jid: Jid) {
        fetchStatusMap.clearErrorFor(jid)
        fetchDeviceListStatus.remove(jid)
    }

    override fun regenerateKeys(wipeOther: Boolean) {
        axolotlStore.regenerate()
        sessions.clear()
        fetchStatusMap.clear()
        fetchDeviceIdsMap.clear()
        fetchDeviceListStatus.clear()
        publishBundlesIfNeeded(true, wipeOther)
    }

    fun destroy() {
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() +
                ": destroying old axolotl service. no longer in use",
        )
        store().wipeAxolotlDb()
    }

    fun makeNew(): AxolotlService {
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + ": make new axolotl service",
        )
        return AxolotlService(this.account, this.mXmppConnectionService)
    }

    override fun getOwnDeviceId(): Int = axolotlStore.getLocalRegistrationId()

    fun getOwnAxolotlAddress(): SignalProtocolAddress =
        SignalProtocolAddress(account.getJid().asBareJid().toString(), getOwnDeviceId())

    fun getOwnDeviceIds(): MutableSet<Int>? = this.deviceIds[account.getJid().asBareJid()]

    override fun registerDevices(jid: Jid, deviceIds: MutableSet<Int>) {
        val hash = deviceIds.hashCode()
        val me = jid.asBareJid() == account.getJid().asBareJid()
        if (me) {
            if (hash != 0 && hash == this.lastDeviceListNotificationHash) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": ignoring duplicate own device id list",
                )
                return
            }
            this.lastDeviceListNotificationHash = hash
        }
        var needsPublishing = me && !deviceIds.contains(getOwnDeviceId())
        if (me) {
            deviceIds.remove(getOwnDeviceId())
        }
        val expiredDevices =
            HashSet(axolotlStore.getSubDeviceSessions(jid.asBareJid().toString()))
        expiredDevices.removeAll(deviceIds)
        for (deviceId in expiredDevices) {
            val address = SignalProtocolAddress(jid.asBareJid().toString(), deviceId)
            val session = sessions.get(address)
            if (session != null && session.getFingerprint() != null) {
                if (session.getTrust().isActive()) {
                    session.setTrust(session.getTrust().toInactive())
                }
            }
        }
        val newDevices = ImmutableSet.copyOf(deviceIds)
        for (deviceId in newDevices) {
            val address = SignalProtocolAddress(jid.asBareJid().toString(), deviceId)
            val session = sessions.get(address)
            if (session != null && session.getFingerprint() != null) {
                if (!session.getTrust().isActive()) {
                    Log.d(
                        Config.LOGTAG,
                        "reactivating device with fingerprint " + session.getFingerprint(),
                    )
                    session.setTrust(session.getTrust().toActive())
                }
            }
        }
        if (me) {
            if (mXmppConnectionService.getOmemoAutoExpiry() != 0L) {
                // Java's `|=` is the non-short-circuit `|`, so the removal always runs.
                val removed = deviceIds.removeAll(getExpiredDevices())
                needsPublishing = needsPublishing || removed
            }
            needsPublishing = needsPublishing || this.changeAccessMode.get()
            for (deviceId in deviceIds) {
                val ownDeviceAddress =
                    SignalProtocolAddress(jid.asBareJid().toString(), deviceId)
                if (sessions.get(ownDeviceAddress) == null) {
                    val status = fetchStatusMap.get(ownDeviceAddress)
                    if (status == null || status == FetchStatus.TIMEOUT) {
                        fetchStatusMap.put(ownDeviceAddress, FetchStatus.PENDING)
                        this.buildSessionFromPEP(ownDeviceAddress)
                    }
                }
            }
            if (needsPublishing) {
                // do not run next device list update notification through de-duplication (might get
                // skipped by CSI)
                this.lastDeviceListNotificationHash = 0
                publishOwnDeviceId(deviceIds)
            }
        }
        val oldSet = this.deviceIds[jid]
        val changed = oldSet == null || oldSet.hashCode() != hash
        this.deviceIds[jid] = deviceIds
        if (changed) {
            mXmppConnectionService.updateConversationUi() //update the lock icon
            mXmppConnectionService.keyStatusUpdated(null)
            if (me) {
                mXmppConnectionService.updateAccountUi()
            }
        } else {
            Log.d(Config.LOGTAG, "skipped device list update because it hasn't changed")
        }
    }

    fun wipeOtherPepDevices() {
        if (pepBroken) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "wipeOtherPepDevices called, but PEP is broken. Ignoring... ",
            )
            return
        }
        val deviceIds = HashSet<Int>()
        deviceIds.add(getOwnDeviceId())
        publishDeviceIdsAndRefineAccessModel(deviceIds)
    }

    fun distrustFingerprint(fingerprint: String) {
        val fp = fingerprint.replace(Regex("\\s"), "")
        val fingerprintStatus =
            axolotlStore.getFingerprintStatus(fp) ?: throw NullPointerException("fingerprintStatus")
        axolotlStore.setFingerprintStatus(fp, fingerprintStatus.toUntrusted())
    }

    private fun publishOwnDeviceIdIfNeeded() {
        if (pepBroken) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) +
                    "publishOwnDeviceIdIfNeeded called, but PEP is broken. Ignoring... ",
            )
            return
        }
        val packet =
            mXmppConnectionService.getIqGenerator().retrieveDeviceIds(account.getJid().asBareJid())
        store().sendIqPacket(account, packet) { response ->
            if (response.getType() == Iq.Type.TIMEOUT) {
                Log.d(
                    Config.LOGTAG,
                    getLogprefix(account) + "Timeout received while retrieving own Device Ids.",
                )
            } else {
                //TODO consider calling registerDevices only after item-not-found to account for broken PEPs
                val item = IqParser.getItem(response)
                val deviceIds = IqParser.deviceIds(item)
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": retrieved own device list: " + deviceIds,
                )
                registerDevices(account.getJid().asBareJid(), deviceIds)
            }
        }
    }

    private fun getExpiredDevices(): MutableSet<Int> {
        val devices = HashSet<Int>()
        for (session in findOwnSessions()) {
            if (session.getTrust().isActive()) {
                val diff = System.currentTimeMillis() - session.getTrust().getLastActivation()
                if (diff > mXmppConnectionService.getOmemoAutoExpiry()) {
                    val fingerprint =
                        session.getFingerprint() ?: throw NullPointerException("fingerprint")
                    val lastMessageDiff =
                        System.currentTimeMillis() -
                            store().getLastTimeFingerprintUsed(fingerprint)
                    val hours = Math.round(lastMessageDiff / (1000 * 60.0 * 60.0))
                    if (lastMessageDiff > mXmppConnectionService.getOmemoAutoExpiry()) {
                        devices.add(session.getRemoteAddress().getDeviceId())
                        session.setTrust(session.getTrust().toInactive())
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": added own device " +
                                fingerprint +
                                " to list of expired devices. Last message received " +
                                hours +
                                " hours ago",
                        )
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": own device " +
                                fingerprint +
                                " was active " +
                                hours +
                                " hours ago",
                        )
                    }
                } //TODO print last activation diff
            }
        }
        return devices
    }

    private fun publishOwnDeviceId(deviceIds: Set<Int>) {
        val deviceIdsCopy = HashSet(deviceIds)
        Log.d(Config.LOGTAG, getLogprefix(account) + "publishing own device ids")
        if (deviceIdsCopy.isEmpty()) {
            if (numPublishTriesOnEmptyPep >= publishTriesThreshold) {
                Log.w(
                    Config.LOGTAG,
                    getLogprefix(account) +
                        "Own device publish attempt threshold exceeded, aborting...",
                )
                pepBroken = true
                return
            } else {
                numPublishTriesOnEmptyPep++
                Log.w(
                    Config.LOGTAG,
                    getLogprefix(account) +
                        "Own device list empty, attempting to publish (try " +
                        numPublishTriesOnEmptyPep +
                        ")",
                )
            }
        } else {
            numPublishTriesOnEmptyPep = 0
        }
        deviceIdsCopy.add(getOwnDeviceId())
        publishDeviceIdsAndRefineAccessModel(deviceIdsCopy)
    }

    private fun publishDeviceIdsAndRefineAccessModel(ids: Set<Int>) {
        publishDeviceIdsAndRefineAccessModel(ids, true)
    }

    private fun publishDeviceIdsAndRefineAccessModel(ids: Set<Int>, firstAttempt: Boolean) {
        val publishOptions =
            if (account.getXmppConnection()!!.getFeatures().pepPublishOptions()) {
                PublishOptions.openAccess()
            } else {
                null
            }
        val publish =
            mXmppConnectionService.getIqGenerator().publishDeviceIds(ids, publishOptions)
        store().sendIqPacket(account, publish) { response ->
            val error =
                if (response.getType() == Iq.Type.ERROR) response.findChild("error") else null
            val preConditionNotMet = PublishOptions.preconditionNotMet(response)
            if (firstAttempt && preConditionNotMet) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": precondition wasn't met for device list. pushing node configuration",
                )
                store().pushNodeConfiguration(
                    account,
                    OmemoSessionPort.PEP_DEVICE_LIST,
                    publishOptions!!,
                    object : uk.xa0.tulkki.xmpp.services.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            publishDeviceIdsAndRefineAccessModel(ids, false)
                        }

                        override fun onPushFailed() {
                            publishDeviceIdsAndRefineAccessModel(ids, false)
                        }
                    },
                )
            } else {
                if (changeAccessMode.compareAndSet(true, false)) {
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString() + ": done changing access mode",
                    )
                    account.setOption(OmemoAccount.OPTION_REQUIRES_ACCESS_MODE_CHANGE, false)
                    store().updateAccount()
                }
                if (response.getType() == Iq.Type.ERROR) {
                    if (preConditionNotMet) {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": device list pre condition still not met on second attempt",
                        )
                    } else if (error != null) {
                        pepBroken = true
                        Log.d(
                            Config.LOGTAG,
                            getLogprefix(account) +
                                "Error received while publishing own device id" +
                                response.findChild("error"),
                        )
                    }
                }
            }
        }
    }

    fun publishDeviceVerificationAndBundle(
        signedPreKeyRecord: SignedPreKeyRecord,
        preKeyRecords: Set<PreKeyRecord>,
        announceAfter: Boolean,
        wipe: Boolean,
    ) {
        try {
            val axolotlPublicKey = axolotlStore.getIdentityKeyPair().getPublicKey()
            // Java handed `getPrivateKeyAlias()`'s possibly-null value straight to KeyChain,
            // which takes a non-null alias; the explicit throw keeps its failure at this line.
            val alias =
                account.getPrivateKeyAlias() ?: throw NullPointerException("privateKeyAlias")
            val x509PrivateKey = KeyChain.getPrivateKey(mXmppConnectionService, alias)
            // The SDK marks `getCertificateChain` nullable (no chain for the alias). Java handed
            // that possibly-null array straight to `publishVerification`, whose Java body read
            // `certificates.length` unguarded, so the callee's non-null declaration is the Java's
            // own contract; the explicit throw keeps its failure at this line.
            val chain =
                KeyChain.getCertificateChain(mXmppConnectionService, alias)
                    ?: throw NullPointerException("certificate chain")
            val verifier = Signature.getInstance("sha256WithRSA")
            verifier.initSign(x509PrivateKey, uk.xa0.tulkki.xmpp.utils.Random.SECURE_RANDOM)
            verifier.update(axolotlPublicKey.serialize())
            val signature = verifier.sign()
            val packet =
                mXmppConnectionService
                    .getIqGenerator()
                    .publishVerification(signature, chain, getOwnDeviceId())
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + ": publish verification for device " + getOwnDeviceId(),
            )
            store().sendIqPacket(account, packet) { _ ->
                val node = OmemoSessionPort.PEP_VERIFICATION + ":" + getOwnDeviceId()
                store().pushNodeConfiguration(
                    account,
                    node,
                    PublishOptions.openAccess(),
                    object : uk.xa0.tulkki.xmpp.services.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Log.d(
                                Config.LOGTAG,
                                getLogprefix(account) +
                                    "configured verification node to be world readable",
                            )
                            publishDeviceBundle(
                                signedPreKeyRecord,
                                preKeyRecords,
                                announceAfter,
                                wipe,
                            )
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                getLogprefix(account) +
                                    "unable to set access model on verification node",
                            )
                            publishDeviceBundle(
                                signedPreKeyRecord,
                                preKeyRecords,
                                announceAfter,
                                wipe,
                            )
                        }
                    },
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun publishBundlesIfNeeded(announce: Boolean, wipe: Boolean) {
        if (pepBroken) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "publishBundlesIfNeeded called, but PEP is broken. Ignoring... ",
            )
            return
        }

        if (account.getXmppConnection()!!.getFeatures().pepPublishOptions()) {
            this.changeAccessMode.set(
                account.isOptionSet(OmemoAccount.OPTION_REQUIRES_ACCESS_MODE_CHANGE),
            )
        } else {
            if (account.setOption(OmemoAccount.OPTION_REQUIRES_ACCESS_MODE_CHANGE, true)) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": server doesn’t support publish-options. setting for later access mode change",
                )
                store().updateAccount()
            }
        }
        if (this.changeAccessMode.get()) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": server gained publish-options capabilities. changing access model",
            )
        }
        val packet =
            mXmppConnectionService
                .getIqGenerator()
                .retrieveBundlesForDevice(account.getJid().asBareJid(), getOwnDeviceId())
        store().sendIqPacket(account, packet) { response ->
            if (response.getType() == Iq.Type.TIMEOUT) {
                return@sendIqPacket //ignore timeout. do nothing
            }

            if (response.getType() == Iq.Type.ERROR) {
                val error = response.findChild("error")
                if (error == null || !error.hasChild("item-not-found")) {
                    pepBroken = true
                    Log.w(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "request for device bundles came back with something other than item-not-found" +
                            response,
                    )
                    return@sendIqPacket
                }
            }

            val keys = IqParser.preKeyPublics(response)
            var flush = false
            var bundle = IqParser.bundle(response)
            if (bundle == null) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Received invalid bundle:" + response)
                bundle = PreKeyBundle(-1, -1, -1, null, -1, null, null, null)
                flush = true
            }
            if (keys == null) {
                Log.w(
                    Config.LOGTAG,
                    getLogprefix(account) + "Received invalid prekeys:" + response,
                )
            }
            try {
                var changed = false
                // Validate IdentityKey
                val identityKeyPair = axolotlStore.getIdentityKeyPair()
                if (flush || !identityKeyPair.getPublicKey().equals(bundle.getIdentityKey())) {
                    Log.i(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Adding own IdentityKey " +
                            identityKeyPair.getPublicKey() +
                            " to PEP.",
                    )
                    changed = true
                }

                // Validate signedPreKeyRecord + ID
                var signedPreKeyRecord: SignedPreKeyRecord
                val numSignedPreKeys = axolotlStore.getSignedPreKeysCount()
                try {
                    signedPreKeyRecord = axolotlStore.loadSignedPreKey(bundle.getSignedPreKeyId())
                    if (flush ||
                        !bundle.getSignedPreKey()
                            .equals(signedPreKeyRecord.getKeyPair().getPublicKey()) ||
                        !Arrays.equals(
                            bundle.getSignedPreKeySignature(),
                            signedPreKeyRecord.getSignature(),
                        )
                    ) {
                        Log.i(
                            Config.LOGTAG,
                            getLogprefix(account) +
                                "Adding new signedPreKey with ID " +
                                (numSignedPreKeys + 1) +
                                " to PEP.",
                        )
                        signedPreKeyRecord =
                            KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1)
                        axolotlStore.storeSignedPreKey(
                            signedPreKeyRecord.getId(),
                            signedPreKeyRecord,
                        )
                        changed = true
                    }
                } catch (e: InvalidKeyIdException) {
                    Log.i(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Adding new signedPreKey with ID " +
                            (numSignedPreKeys + 1) +
                            " to PEP.",
                    )
                    signedPreKeyRecord =
                        KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1)
                    axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord)
                    changed = true
                }

                // Validate PreKeys
                val preKeyRecords = HashSet<PreKeyRecord>()
                if (keys != null) {
                    for (id in keys.keys) {
                        try {
                            val preKeyRecord = axolotlStore.loadPreKey(id)
                            if (preKeyRecord.getKeyPair().getPublicKey().equals(keys[id])) {
                                preKeyRecords.add(preKeyRecord)
                            }
                        } catch (ignored: InvalidKeyIdException) {
                        }
                    }
                }
                val newKeys = NUM_KEYS_TO_PUBLISH - preKeyRecords.size
                if (newKeys > 0) {
                    val newRecords =
                        KeyHelper.generatePreKeys(
                            axolotlStore.getCurrentPreKeyId() + 1,
                            newKeys,
                        )
                    preKeyRecords.addAll(newRecords)
                    for (record in newRecords) {
                        axolotlStore.storePreKey(record.getId(), record)
                    }
                    changed = true
                    Log.i(
                        Config.LOGTAG,
                        getLogprefix(account) + "Adding " + newKeys + " new preKeys to PEP.",
                    )
                }

                if (changed || changeAccessMode.get()) {
                    if (account.getPrivateKeyAlias() != null && Config.X509_VERIFICATION) {
                        store().publishDisplayName(account)
                        publishDeviceVerificationAndBundle(
                            signedPreKeyRecord,
                            preKeyRecords,
                            announce,
                            wipe,
                        )
                    } else {
                        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announce, wipe)
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) + "Bundle " + getOwnDeviceId() + " in PEP was current",
                    )
                    if (wipe) {
                        wipeOtherPepDevices()
                    } else if (announce) {
                        Log.d(
                            Config.LOGTAG,
                            getLogprefix(account) + "Announcing device " + getOwnDeviceId(),
                        )
                        publishOwnDeviceIdIfNeeded()
                    }
                }
            } catch (e: InvalidKeyException) {
                Log.e(
                    Config.LOGTAG,
                    getLogprefix(account) +
                        "Failed to publish bundle " +
                        getOwnDeviceId() +
                        ", reason: " +
                        e.message,
                )
            }
        }
    }

    private fun publishDeviceBundle(
        signedPreKeyRecord: SignedPreKeyRecord,
        preKeyRecords: Set<PreKeyRecord>,
        announceAfter: Boolean,
        wipe: Boolean,
    ) {
        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe, true)
    }

    private fun publishDeviceBundle(
        signedPreKeyRecord: SignedPreKeyRecord,
        preKeyRecords: Set<PreKeyRecord>,
        announceAfter: Boolean,
        wipe: Boolean,
        firstAttempt: Boolean,
    ) {
        val publishOptions =
            if (account.getXmppConnection()!!.getFeatures().pepPublishOptions()) {
                PublishOptions.openAccess()
            } else {
                null
            }
        val publish =
            mXmppConnectionService
                .getIqGenerator()
                .publishBundles(
                    signedPreKeyRecord,
                    axolotlStore.getIdentityKeyPair().getPublicKey(),
                    preKeyRecords,
                    getOwnDeviceId(),
                    publishOptions,
                )
        Log.d(
            Config.LOGTAG,
            getLogprefix(account) +
                ": Bundle " +
                getOwnDeviceId() +
                " in PEP not current. Publishing...",
        )
        store().sendIqPacket(account, publish) { response ->
            val preconditionNotMet = PublishOptions.preconditionNotMet(response)
            if (firstAttempt && preconditionNotMet) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": precondition wasn't met for bundle. pushing node configuration",
                )
                val node = OmemoSessionPort.PEP_BUNDLES + ":" + getOwnDeviceId()
                store().pushNodeConfiguration(
                    account,
                    node,
                    publishOptions!!,
                    object : uk.xa0.tulkki.xmpp.services.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            publishDeviceBundle(
                                signedPreKeyRecord,
                                preKeyRecords,
                                announceAfter,
                                wipe,
                                false,
                            )
                        }

                        override fun onPushFailed() {
                            publishDeviceBundle(
                                signedPreKeyRecord,
                                preKeyRecords,
                                announceAfter,
                                wipe,
                                false,
                            )
                        }
                    },
                )
            } else if (response.getType() == Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    getLogprefix(account) + "Successfully published bundle. ",
                )
                if (wipe) {
                    wipeOtherPepDevices()
                } else if (announceAfter) {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) + "Announcing device " + getOwnDeviceId(),
                    )
                    publishOwnDeviceIdIfNeeded()
                }
            } else if (response.getType() == Iq.Type.ERROR) {
                if (preconditionNotMet) {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "bundle precondition still not met after second attempt",
                    )
                } else {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Error received while publishing bundle: " +
                            response.toString(),
                    )
                }
                pepBroken = true
            }
        }
    }

    override fun deleteOmemoIdentity() {
        store().deletePepNode(account, OmemoSessionPort.PEP_BUNDLES + ":" + getOwnDeviceId())
        val ownDeviceIds = getOwnDeviceIds()
        publishDeviceIdsAndRefineAccessModel(
            ownDeviceIds ?: emptySet(),
        )
    }

    fun getCryptoTargets(conversation: OmemoConversation): List<Jid> {
        if (conversation.getMode() == OmemoConversation.MODE_SINGLE) {
            val jids = ArrayList<Jid>()
            jids.add(conversation.getJid() ?: throw NullPointerException("jid"))
            return jids
        }
        return conversation.getMucOptions().getMembers(false)
    }

    /**
     * The store answers null for a null fingerprint, so the parameter keeps the tolerance Java's
     * unannotated `String` had; `Message.isTrusted` and `MessageAdapter` both pass a nullable one.
     */
    fun getFingerprintTrust(fingerprint: String?): FingerprintStatus? =
        axolotlStore.getFingerprintStatus(fingerprint)

    fun getFingerprintCertificate(fingerprint: String): X509Certificate? =
        axolotlStore.getFingerprintCertificate(fingerprint)

    fun setFingerprintTrust(fingerprint: String, status: FingerprintStatus) {
        axolotlStore.setFingerprintStatus(fingerprint, status)
        // TODO we decided to call this after a fingerprint gets toggled to update the 'your contact
        //  is using unverified devices text'; however this means the entire screen gets redrawn
        //  after a toggle which might be annoying or cause other weird UI glitches
        mXmppConnectionService.updateAccountUi()
    }

    private fun verifySessionWithPEP(
        session: XmppAxolotlSession,
    ): ListenableFuture<XmppAxolotlSession> {
        Log.d(
            Config.LOGTAG,
            "trying to verify fresh session (" + session.getRemoteAddress().getName() + ") with pep",
        )
        val address = session.getRemoteAddress()
        // Kept nullable: Java only dereferenced it inside the verification branch, which the
        // surrounding `catch (Exception)` turned into the same logged failure.
        val identityKey = session.getIdentityKey()
        val jid: Jid
        try {
            jid = Jid.of(address.getName())
        } catch (e: IllegalArgumentException) {
            fetchStatusMap.put(address, FetchStatus.SUCCESS)
            finishBuildingSessionsFromPEP(address)
            return Futures.immediateFuture(session)
        }
        val future = SettableFuture.create<XmppAxolotlSession>()
        val packet =
            mXmppConnectionService
                .getIqGenerator()
                .retrieveVerificationForDevice(jid, address.getDeviceId())
        store().sendIqPacket(account, packet) { response ->
            val verification = IqParser.verification(response)
            if (verification != null) {
                try {
                    val verifier = Signature.getInstance("sha256WithRSA")
                    verifier.initVerify(verification.first[0])
                    verifier.update(
                        (identityKey ?: throw NullPointerException("identityKey")).serialize(),
                    )
                    if (verifier.verify(verification.second)) {
                        try {
                            mXmppConnectionService
                                .getMemorizingTrustManager()
                                .getNonInteractive()
                                .checkClientTrusted(verification.first, "RSA")
                            val fingerprint =
                                session.getFingerprint()
                                    ?: throw NullPointerException("fingerprint")
                            Log.d(
                                Config.LOGTAG,
                                "verified session with x.509 signature. fingerprint was: " +
                                    fingerprint,
                            )
                            setFingerprintTrust(
                                fingerprint,
                                FingerprintStatus.createActiveVerified(true),
                            )
                            axolotlStore.setFingerprintCertificate(
                                fingerprint,
                                verification.first[0],
                            )
                            fetchStatusMap.put(address, FetchStatus.SUCCESS_VERIFIED)
                            val information =
                                CryptoHelper.extractCertificateInformation(verification.first[0])
                            try {
                                val cn = information.getString("subject_cn")
                                val jid1 = Jid.of(address.getName())
                                Log.d(
                                    Config.LOGTAG,
                                    "setting common name for " + jid1 + " to " + cn,
                                )
                                account.getRoster().getContact(jid1).setCommonName(cn)
                            } catch (ignored: IllegalArgumentException) {
                                //ignored
                            }
                            finishBuildingSessionsFromPEP(address)
                            future.set(session)
                            return@sendIqPacket
                        } catch (e: Exception) {
                            Log.d(Config.LOGTAG, "could not verify certificate")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "error during verification " + e.message)
                }
            } else {
                Log.d(Config.LOGTAG, "no verification found")
            }
            fetchStatusMap.put(address, FetchStatus.SUCCESS)
            finishBuildingSessionsFromPEP(address)
            future.set(session)
        }
        return future
    }

    private fun finishBuildingSessionsFromPEP(address: SignalProtocolAddress) {
        val ownAddress = SignalProtocolAddress(account.getJid().asBareJid().toString(), 0)
        val own = fetchStatusMap.getAll(ownAddress.name)
        val remote = fetchStatusMap.getAll(address.name)
        if (!own.containsValue(FetchStatus.PENDING) && !remote.containsValue(FetchStatus.PENDING)) {
            var report: FetchStatus? = null
            if (own.containsValue(FetchStatus.SUCCESS) || remote.containsValue(FetchStatus.SUCCESS)) {
                report = FetchStatus.SUCCESS
            } else if (own.containsValue(FetchStatus.SUCCESS_VERIFIED) ||
                remote.containsValue(FetchStatus.SUCCESS_VERIFIED)
            ) {
                report = FetchStatus.SUCCESS_VERIFIED
            } else if (own.containsValue(FetchStatus.SUCCESS_TRUSTED) ||
                remote.containsValue(FetchStatus.SUCCESS_TRUSTED)
            ) {
                report = FetchStatus.SUCCESS_TRUSTED
            } else if (own.containsValue(FetchStatus.ERROR) || remote.containsValue(FetchStatus.ERROR)) {
                report = FetchStatus.ERROR
            }
            mXmppConnectionService.keyStatusUpdated(report)
        }
        if (Config.REMOVE_BROKEN_DEVICES) {
            val ownDeviceIds =
                HashSet(getOwnDeviceIds() ?: throw NullPointerException("ownDeviceIds"))
            var publish = false
            for ((id, status) in own) {
                if (status == FetchStatus.ERROR &&
                    PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT.add(id) &&
                    ownDeviceIds.remove(id)
                ) {
                    publish = true
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString() +
                            ": error fetching own device with id " +
                            id +
                            ". removing from announcement",
                    )
                }
            }
            if (publish) {
                publishOwnDeviceId(ownDeviceIds)
            }
        }
    }

    override fun hasEmptyDeviceList(jid: Jid): Boolean =
        !hasAny(jid) && (!deviceIds.containsKey(jid) || deviceIds[jid]!!.isEmpty())

    override fun fetchDeviceIds(jid: Jid) {
        fetchDeviceIds(jid, null)
    }

    private fun fetchDeviceIds(jid: Jid, callback: OnDeviceIdsFetched?) {
        val packet: Iq?
        synchronized(this.fetchDeviceIdsMap) {
            val callbacks = this.fetchDeviceIdsMap[jid]
            if (callbacks != null) {
                if (callback != null) {
                    callbacks.add(callback)
                }
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": fetching device ids for " +
                        jid +
                        " already running. adding callback",
                )
                packet = null
            } else {
                val newCallbacks = ArrayList<OnDeviceIdsFetched>()
                if (callback != null) {
                    newCallbacks.add(callback)
                }
                this.fetchDeviceIdsMap[jid] = newCallbacks
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": fetching device ids for " + jid,
                )
                packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(jid)
            }
        }
        if (packet != null) {
            store().sendIqPacket(account, packet) { response ->
                if (response.getType() == Iq.Type.RESULT) {
                    fetchDeviceListStatus[jid] = true
                    val item = IqParser.getItem(response)
                    val deviceIds = IqParser.deviceIds(item)
                    registerDevices(jid, deviceIds)
                    val callbacks: MutableList<OnDeviceIdsFetched>?
                    synchronized(fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid)
                    }
                    if (callbacks != null) {
                        for (c in callbacks) {
                            c.fetched(jid, deviceIds)
                        }
                    }
                } else {
                    if (response.getType() == Iq.Type.TIMEOUT) {
                        fetchDeviceListStatus.remove(jid)
                    } else {
                        fetchDeviceListStatus[jid] = false
                    }
                    val callbacks: MutableList<OnDeviceIdsFetched>?
                    synchronized(fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid)
                    }
                    if (callbacks != null) {
                        for (c in callbacks) {
                            c.fetched(jid, null)
                        }
                    }
                }
            }
        }
    }

    private fun fetchDeviceIds(jids: List<Jid>, callback: OnMultipleDeviceIdFetched?) {
        val unfinishedJids = ArrayList(jids)
        synchronized(unfinishedJids) {
            for (jid in unfinishedJids) {
                fetchDeviceIds(
                    jid,
                    OnDeviceIdsFetched { j, _ ->
                        synchronized(unfinishedJids) {
                            unfinishedJids.remove(j)
                            if (unfinishedJids.size == 0 && callback != null) {
                                callback.fetched()
                            }
                        }
                    },
                )
            }
        }
    }

    private fun buildSessionFromPEP(
        address: SignalProtocolAddress,
    ): ListenableFuture<XmppAxolotlSession> = buildSessionFromPEP(address, null)

    private fun buildSessionFromPEP(
        address: SignalProtocolAddress,
        callback: OnSessionBuildFromPep?,
    ): ListenableFuture<XmppAxolotlSession> {
        val sessionSettableFuture = SettableFuture.create<XmppAxolotlSession>()
        Log.i(
            Config.LOGTAG,
            getLogprefix(account) + "Building new session for " + address.toString(),
        )
        if (address == getOwnAxolotlAddress()) {
            throw AssertionError("We should NEVER build a session with ourselves. What happened here?!")
        }
        val jid = Jid.of(address.getName())
        val oneOfOurs = jid.asBareJid() == account.getJid().asBareJid()
        val bundlesPacket =
            mXmppConnectionService
                .getIqGenerator()
                .retrieveBundlesForDevice(jid, address.getDeviceId())
        store().sendIqPacket(account, bundlesPacket) { packet ->
            if (packet.getType() == Iq.Type.TIMEOUT) {
                fetchStatusMap.put(address, FetchStatus.TIMEOUT)
                sessionSettableFuture.setException(
                    CryptoFailedException("Unable to build session. Timeout"),
                )
            } else if (packet.getType() == Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    getLogprefix(account) + "Received preKey IQ packet, processing...",
                )
                val preKeyBundleList = IqParser.preKeys(packet)
                val bundle = IqParser.bundle(packet)
                if (preKeyBundleList.isEmpty() || bundle == null) {
                    Log.e(
                        Config.LOGTAG,
                        getLogprefix(account) + "preKey IQ packet invalid: " + packet,
                    )
                    fetchStatusMap.put(address, FetchStatus.ERROR)
                    finishBuildingSessionsFromPEP(address)
                    if (callback != null) {
                        callback.onSessionBuildFailed()
                    }
                    sessionSettableFuture.setException(
                        CryptoFailedException("Unable to build session. IQ Packet Invalid"),
                    )
                    return@sendIqPacket
                }
                val random = Random()
                val preKey = preKeyBundleList[random.nextInt(preKeyBundleList.size)]
                if (preKey == null) {
                    //should never happen
                    fetchStatusMap.put(address, FetchStatus.ERROR)
                    finishBuildingSessionsFromPEP(address)
                    if (callback != null) {
                        callback.onSessionBuildFailed()
                    }
                    sessionSettableFuture.setException(
                        CryptoFailedException("Unable to build session. No suitable PreKey found"),
                    )
                    return@sendIqPacket
                }

                val preKeyBundle =
                    PreKeyBundle(
                        0,
                        address.getDeviceId(),
                        preKey.getPreKeyId(),
                        preKey.getPreKey(),
                        bundle.getSignedPreKeyId(),
                        bundle.getSignedPreKey(),
                        bundle.getSignedPreKeySignature(),
                        bundle.getIdentityKey(),
                    )

                try {
                    val builder = SessionBuilder(axolotlStore, address)
                    builder.process(preKeyBundle)
                    val session =
                        XmppAxolotlSession(
                            account,
                            axolotlStore,
                            address,
                            bundle.getIdentityKey(),
                        )
                    sessions.put(address, session)
                    if (Config.X509_VERIFICATION) {
                        sessionSettableFuture.setFuture(
                            verifySessionWithPEP(session),
                        ) //TODO; maybe inject callback in here too
                    } else {
                        val status =
                            getFingerprintTrust(
                                CryptoHelper.bytesToHex(
                                    bundle.getIdentityKey().getPublicKey().serialize(),
                                ),
                            )
                        val fetchStatus: FetchStatus =
                            if (status != null && status.isVerified()) {
                                FetchStatus.SUCCESS_VERIFIED
                            } else if (status != null && status.isTrusted()) {
                                FetchStatus.SUCCESS_TRUSTED
                            } else {
                                FetchStatus.SUCCESS
                            }
                        fetchStatusMap.put(address, fetchStatus)
                        finishBuildingSessionsFromPEP(address)
                        if (callback != null) {
                            callback.onSessionBuildSuccessful()
                        }
                        sessionSettableFuture.set(session)
                    }
                } catch (e: Exception) {
                    // Kotlin has no multi-catch: rethrow anything Java did not catch.
                    if (e !is UntrustedIdentityException && e !is InvalidKeyException) {
                        throw e
                    }
                    Log.e(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Error building session for " +
                            address +
                            ": " +
                            e.javaClass.name +
                            ", " +
                            e.message,
                    )
                    fetchStatusMap.put(address, FetchStatus.ERROR)
                    finishBuildingSessionsFromPEP(address)
                    if (oneOfOurs && cleanedOwnDeviceIds.add(address.getDeviceId())) {
                        removeFromDeviceAnnouncement(address.getDeviceId())
                    }
                    if (callback != null) {
                        callback.onSessionBuildFailed()
                    }
                    sessionSettableFuture.setException(CryptoFailedException(e))
                }
            } else {
                fetchStatusMap.put(address, FetchStatus.ERROR)
                val error = packet.findChild("error")
                val itemNotFound = error != null && error.hasChild("item-not-found")
                Log.d(
                    Config.LOGTAG,
                    getLogprefix(account) +
                        "Error received while building session:" +
                        packet.findChild("error"),
                )
                finishBuildingSessionsFromPEP(address)
                if (oneOfOurs && itemNotFound && cleanedOwnDeviceIds.add(address.getDeviceId())) {
                    removeFromDeviceAnnouncement(address.getDeviceId())
                }
                if (callback != null) {
                    callback.onSessionBuildFailed()
                }
                sessionSettableFuture.setException(
                    CryptoFailedException("Unable to build session. IQ Packet Error"),
                )
            }
        }
        return sessionSettableFuture
    }

    private fun removeFromDeviceAnnouncement(id: Int) {
        val temp = HashSet(getOwnDeviceIds() ?: throw NullPointerException("ownDeviceIds"))
        if (temp.remove(id)) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    " remove own device id " +
                    id +
                    " from announcement. devices left:" +
                    temp,
            )
            publishOwnDeviceId(temp)
        }
    }

    fun findDevicesWithoutSession(
        conversation: OmemoConversation,
    ): MutableSet<SignalProtocolAddress> {
        val addresses = HashSet<SignalProtocolAddress>()
        for (jid in getCryptoTargets(conversation)) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "Finding devices without session for " + jid,
            )
            val ids = deviceIds[jid]
            if (ids != null && ids.isNotEmpty()) {
                for (foreignId in ids) {
                    val address = SignalProtocolAddress(jid.toString(), foreignId)
                    if (sessions.get(address) == null) {
                        val identityKey =
                            axolotlStore
                                .loadSession(address)
                                .getSessionState()
                                .getRemoteIdentityKey()
                        if (identityKey != null) {
                            Log.d(
                                Config.LOGTAG,
                                getLogprefix(account) +
                                    "Already have session for " +
                                    address.toString() +
                                    ", adding to cache...",
                            )
                            val session =
                                XmppAxolotlSession(account, axolotlStore, address, identityKey)
                            sessions.put(address, session)
                        } else {
                            Log.d(
                                Config.LOGTAG,
                                getLogprefix(account) + "Found device " + jid + ":" + foreignId,
                            )
                            if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                                addresses.add(address)
                            } else {
                                Log.d(
                                    Config.LOGTAG,
                                    getLogprefix(account) +
                                        "skipping over " +
                                        address +
                                        " because it's broken",
                                )
                            }
                        }
                    }
                }
            } else {
                mXmppConnectionService.keyStatusUpdated(FetchStatus.ERROR)
                Log.w(
                    Config.LOGTAG,
                    getLogprefix(account) + "Have no target devices in PEP!",
                )
            }
        }
        val ownIds = this.deviceIds[account.getJid().asBareJid()]
        for (ownId in ownIds ?: HashSet<Int>()) {
            val address =
                SignalProtocolAddress(account.getJid().asBareJid().toString(), ownId)
            if (sessions.get(address) == null) {
                val identityKey =
                    axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey()
                if (identityKey != null) {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Already have session for " +
                            address.toString() +
                            ", adding to cache...",
                    )
                    val session = XmppAxolotlSession(account, axolotlStore, address, identityKey)
                    sessions.put(address, session)
                } else {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Found device " +
                            account.getJid().asBareJid() +
                            ":" +
                            ownId,
                    )
                    if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                        addresses.add(address)
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            getLogprefix(account) + "skipping over " + address + " because it's broken",
                        )
                    }
                }
            }
        }

        return addresses
    }

    fun createSessionsIfNeeded(conversation: OmemoConversation): Boolean {
        val jidsWithEmptyDeviceList = ArrayList(getCryptoTargets(conversation))
        val iterator = jidsWithEmptyDeviceList.iterator()
        while (iterator.hasNext()) {
            val jid = iterator.next()
            if (!hasEmptyDeviceList(jid)) {
                iterator.remove()
            }
        }
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() +
                ": createSessionsIfNeeded() - jids with empty device list: " +
                jidsWithEmptyDeviceList,
        )
        return if (jidsWithEmptyDeviceList.size > 0) {
            fetchDeviceIds(
                jidsWithEmptyDeviceList,
                OnMultipleDeviceIdFetched { createSessionsIfNeededActual(conversation) },
            )
            true
        } else {
            createSessionsIfNeededActual(conversation)
        }
    }

    private fun createSessionsIfNeededActual(conversation: OmemoConversation): Boolean {
        Log.i(
            Config.LOGTAG,
            getLogprefix(account) + "Creating axolotl sessions if needed...",
        )
        var newSessions = false
        val addresses = findDevicesWithoutSession(conversation)
        for (address in addresses) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "Processing device: " + address.toString(),
            )
            val status = fetchStatusMap.get(address)
            if (status == null || status == FetchStatus.TIMEOUT) {
                fetchStatusMap.put(address, FetchStatus.PENDING)
                this.buildSessionFromPEP(address)
                newSessions = true
            } else if (status == FetchStatus.PENDING) {
                newSessions = true
            } else {
                Log.d(
                    Config.LOGTAG,
                    getLogprefix(account) + "Already fetching bundle for " + address.toString(),
                )
            }
        }

        return newSessions
    }

    fun trustedSessionVerified(conversation: OmemoConversation): Boolean {
        val sessions = HashSet<XmppAxolotlSession>()
        sessions.addAll(findSessionsForConversation(conversation))
        sessions.addAll(findOwnSessions())
        var verified = false
        for (session in sessions) {
            if (session.getTrust().isTrustedAndActive()) {
                if (session.getTrust().getTrust() == FingerprintStatus.Trust.VERIFIED_X509) {
                    verified = true
                } else {
                    return false
                }
            }
        }
        return verified
    }

    fun hasPendingKeyFetches(jids: List<Jid>): Boolean {
        val ownAddress = SignalProtocolAddress(account.getJid().asBareJid().toString(), 0)
        if (fetchStatusMap.getAll(ownAddress.name).containsValue(FetchStatus.PENDING)) {
            return true
        }
        synchronized(this.fetchDeviceIdsMap) {
            for (jid in jids) {
                val foreignAddress = SignalProtocolAddress(jid.asBareJid().toString(), 0)
                if (fetchStatusMap.getAll(foreignAddress.name)
                        .containsValue(FetchStatus.PENDING) ||
                    this.fetchDeviceIdsMap.containsKey(jid)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun buildHeader(axolotlMessage: XmppAxolotlMessage, c: OmemoConversation): Boolean {
        val remoteSessions = findSessionsForConversation(c)
        val acceptEmpty =
            (c.getMode() == OmemoConversation.MODE_MULTI &&
                c.getMucOptions().getUserCount() == 0) ||
                c.getContact().isSelf()
        val ownSessions = findOwnSessions()
        if (remoteSessions.isEmpty() && !acceptEmpty) {
            return false
        }
        for (session in remoteSessions) {
            axolotlMessage.addDevice(session)
        }
        for (session in ownSessions) {
            axolotlMessage.addDevice(session)
        }

        return true
    }

    //this is being used for private muc messages only
    private fun buildHeader(axolotlMessage: XmppAxolotlMessage, jid: Jid?): Boolean {
        if (jid == null) {
            return false
        }
        val sessions = HashSet<XmppAxolotlSession>()
        sessions.addAll(this.sessions.getAll(getAddressForJid(jid).name).values)
        if (sessions.isEmpty()) {
            return false
        }
        sessions.addAll(findOwnSessions())
        for (session in sessions) {
            axolotlMessage.addDevice(session)
        }
        return true
    }

    fun encrypt(content: String?, counterpart: Jid?): XmppAxolotlMessage? {
        val axolotlMessage =
            XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId())
        try {
            axolotlMessage.encrypt(content)
        } catch (e: CryptoFailedException) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.message)
            return null
        }
        if (!buildHeader(axolotlMessage, counterpart)) {
            return null
        }
        return axolotlMessage
    }

    fun encrypt(content: String?, conversation: OmemoConversation): XmppAxolotlMessage? {
        val axolotlMessage =
            XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId())
        try {
            axolotlMessage.encrypt(content)
        } catch (e: CryptoFailedException) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.message)
            return null
        }
        if (!buildHeader(axolotlMessage, conversation)) {
            return null
        }
        return axolotlMessage
    }

    fun encrypt(message: OmemoMessage): XmppAxolotlMessage? {
        val content: String? =
            if (message.hasFileOnRemoteHost()) {
                message.getFileUrl()
            } else {
                message.getRawBody()
            }

        return if (message.isPrivateMessage()) {
            encrypt(content, message.getTrueCounterpart())
        } else {
            encrypt(
                content,
                message.getOmemoConversation() ?: throw NullPointerException("conversation"),
            )
        }
    }

    fun preparePayloadMessage(message: OmemoMessage, delay: Boolean) {
        executor.execute(
            Runnable {
                val axolotlMessage = encrypt(message)
                if (axolotlMessage == null) {
                    store().markMessage(message, OmemoMessage.STATUS_SEND_FAILED)
                    //mXmppConnectionService.updateConversationUi();
                } else {
                    Log.d(
                        Config.LOGTAG,
                        getLogprefix(account) +
                            "Generated message, caching: " +
                            message.getUuid(),
                    )
                    messageCache.put(message.getUuid(), axolotlMessage)
                    store().resendMessage(message, delay, true)
                }
            },
        )
    }

    @Throws(CryptoFailedException::class)
    private fun encrypt(
        element: IceUdpTransportInfo,
        session: XmppAxolotlSession,
    ): OmemoVerifiedIceUdpTransportInfo {
        val transportInfo = OmemoVerifiedIceUdpTransportInfo()
        transportInfo.setAttributes(element.getAttributes())
        for (child in element.getChildren()) {
            if ("fingerprint" == child.getName() &&
                Namespace.JINGLE_APPS_DTLS == child.getNamespace()
            ) {
                val fingerprint =
                    Element("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)
                fingerprint.setAttribute("setup", child.getAttribute("setup"))
                fingerprint.setAttribute("hash", child.getAttribute("hash"))
                val axolotlMessage =
                    XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId())
                val content = child.getContent()
                axolotlMessage.encrypt(content)
                axolotlMessage.addDevice(session, true)
                fingerprint.addChild(axolotlMessage.toElement())
                transportInfo.addChild(fingerprint)
            } else {
                transportInfo.addChild(child)
            }
        }
        return transportInfo
    }

    fun encrypt(
        rtpContentMap: RtpContentMap,
        jid: Jid,
        deviceId: Int,
    ): ListenableFuture<OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> =
        Futures.transformAsync(
            getSession(jid, deviceId),
            AsyncFunction { session -> encrypt(rtpContentMap, session) },
            MoreExecutors.directExecutor(),
        )

    private fun encrypt(
        rtpContentMap: RtpContentMap,
        session: XmppAxolotlSession,
    ): ListenableFuture<OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> {
        if (Config.REQUIRE_RTP_VERIFICATION) {
            requireVerification(session)
        }
        val descriptionTransportBuilder =
            ImmutableMap.Builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
        val omemoVerification = OmemoVerification()
        omemoVerification.setDeviceId(session.getRemoteAddress().getDeviceId())
        omemoVerification.setSessionFingerprint(session.getFingerprint())
        for (content in rtpContentMap.contents.entries) {
            val descriptionTransport = content.value
            val encryptedTransportInfo: OmemoVerifiedIceUdpTransportInfo
            try {
                encryptedTransportInfo = encrypt(descriptionTransport.transport, session)
            } catch (e: CryptoFailedException) {
                return Futures.immediateFailedFuture(e)
            }
            descriptionTransportBuilder.put(
                content.key,
                DescriptionTransport(
                    descriptionTransport.senders,
                    descriptionTransport.description,
                    encryptedTransportInfo,
                ),
            )
        }
        return Futures.immediateFuture(
            OmemoVerifiedPayload(
                omemoVerification,
                OmemoVerifiedRtpContentMap(
                    rtpContentMap.group,
                    descriptionTransportBuilder.build(),
                ),
            ),
        )
    }

    private fun getSession(jid: Jid, deviceId: Int): ListenableFuture<XmppAxolotlSession> {
        val address = SignalProtocolAddress(jid.asBareJid().toString(), deviceId)
        val session = sessions.get(address)
        if (session == null) {
            return buildSessionFromPEP(address)
        }
        return Futures.immediateFuture(session)
    }

    fun decrypt(
        omemoVerifiedRtpContentMap: OmemoVerifiedRtpContentMap,
        from: Jid,
    ): ListenableFuture<OmemoVerifiedPayload<RtpContentMap>> {
        val descriptionTransportBuilder =
            ImmutableMap.Builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
        val omemoVerification = OmemoVerification()
        val pepVerificationFutures =
            ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>>()
        for (content in omemoVerifiedRtpContentMap.contents.entries) {
            val descriptionTransport = content.value
            val decryptedTransport: OmemoVerifiedPayload<IceUdpTransportInfo>
            try {
                decryptedTransport =
                    decrypt(
                        descriptionTransport.transport as OmemoVerifiedIceUdpTransportInfo,
                        from,
                        pepVerificationFutures,
                    )
            } catch (e: OmemoFailure) {
                return Futures.immediateFailedFuture(e)
            }
            omemoVerification.setOrEnsureEqual(decryptedTransport)
            descriptionTransportBuilder.put(
                content.key,
                DescriptionTransport(
                    descriptionTransport.senders,
                    descriptionTransport.description,
                    decryptedTransport.payload,
                ),
            )
        }
        processPostponed()
        val sessionFutures = pepVerificationFutures.build()
        return Futures.transform(
            Futures.allAsList(sessionFutures),
            Function { sessions ->
                if (Config.REQUIRE_RTP_VERIFICATION) {
                    for (session in sessions) {
                        requireVerification(session)
                    }
                }
                OmemoVerifiedPayload(
                    omemoVerification,
                    RtpContentMap(
                        omemoVerifiedRtpContentMap.group,
                        descriptionTransportBuilder.build(),
                    ),
                )
            },
            MoreExecutors.directExecutor(),
        )
    }

    @Throws(OmemoFailure::class)
    private fun decrypt(
        verifiedIceUdpTransportInfo: OmemoVerifiedIceUdpTransportInfo,
        from: Jid,
        pepVerificationFutures: ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>>,
    ): OmemoVerifiedPayload<IceUdpTransportInfo> {
        val transportInfo = IceUdpTransportInfo()
        transportInfo.setAttributes(verifiedIceUdpTransportInfo.getAttributes())
        val omemoVerification = OmemoVerification()
        for (child in verifiedIceUdpTransportInfo.getChildren()) {
            if ("fingerprint" == child.getName() &&
                Namespace.OMEMO_DTLS_SRTP_VERIFICATION == child.getNamespace()
            ) {
                val fingerprint = Element("fingerprint", Namespace.JINGLE_APPS_DTLS)
                fingerprint.setAttribute("setup", child.getAttribute("setup"))
                fingerprint.setAttribute("hash", child.getAttribute("hash"))
                val encrypted =
                    child.findChildEnsureSingle(
                        XmppAxolotlMessage.CONTAINERTAG,
                        OmemoSessionPort.PEP_PREFIX,
                    )
                        ?: throw NullPointerException("encrypted")
                val xmppAxolotlMessage =
                    XmppAxolotlMessage.fromElement(encrypted, from.asBareJid())
                val session = getReceivingSession(xmppAxolotlMessage)
                val plaintext =
                    xmppAxolotlMessage.decrypt(session, getOwnDeviceId())
                        ?: throw NullPointerException("plaintext")
                val preKeyId = session.getPreKeyIdAndReset()
                if (preKeyId != null) {
                    postponedSessions.add(session)
                }
                if (session.isFresh()) {
                    pepVerificationFutures.add(putFreshSession(session))
                } else if (Config.REQUIRE_RTP_VERIFICATION) {
                    pepVerificationFutures.add(Futures.immediateFuture(session))
                }
                fingerprint.setContent(plaintext.getPlaintext())
                omemoVerification.setDeviceId(session.getRemoteAddress().getDeviceId())
                omemoVerification.setSessionFingerprint(plaintext.getFingerprint())
                transportInfo.addChild(fingerprint)
            } else {
                transportInfo.addChild(child)
            }
        }
        return OmemoVerifiedPayload(omemoVerification, transportInfo)
    }

    private fun requireVerification(session: XmppAxolotlSession) {
        if (session.getTrust().isVerified()) {
            return
        }
        throw NotVerifiedException(
            String.format(
                "session with %s was not verified",
                session.getFingerprint(),
            ),
        )
    }

    fun prepareKeyTransportMessage(
        conversation: OmemoConversation,
    ): ListenableFuture<XmppAxolotlMessage> =
        Futures.submit(
            Callable {
                val axolotlMessage =
                    XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId())
                if (buildHeader(axolotlMessage, conversation)) {
                    axolotlMessage
                } else {
                    throw IllegalStateException("No session to decrypt to")
                }
            },
            executor,
        )

    fun fetchAxolotlMessageFromCache(message: OmemoMessage): XmppAxolotlMessage? {
        val axolotlMessage = messageCache[message.getUuid()]
        if (axolotlMessage != null) {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "Cache hit: " + message.getUuid(),
            )
            messageCache.remove(message.getUuid())
        } else {
            Log.d(
                Config.LOGTAG,
                getLogprefix(account) + "Cache miss: " + message.getUuid(),
            )
        }
        return axolotlMessage
    }

    private fun recreateUncachedSession(address: SignalProtocolAddress): XmppAxolotlSession? {
        val identityKey =
            axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey()
        return if (identityKey != null) {
            XmppAxolotlSession(account, axolotlStore, address, identityKey)
        } else {
            null
        }
    }

    private fun getReceivingSession(message: XmppAxolotlMessage): XmppAxolotlSession {
        val senderAddress =
            SignalProtocolAddress(message.getFrom().toString(), message.getSenderDeviceId())
        return getReceivingSession(senderAddress)
    }

    private fun getReceivingSession(senderAddress: SignalProtocolAddress): XmppAxolotlSession {
        var session = sessions.get(senderAddress)
        if (session == null) {
            session = recreateUncachedSession(senderAddress)
            if (session == null) {
                session = XmppAxolotlSession(account, axolotlStore, senderAddress)
            }
        }
        return session
    }

    @Throws(
        NotEncryptedForThisDeviceException::class,
        BrokenSessionException::class,
        OutdatedSenderException::class,
    )
    fun processReceivingPayloadMessage(
        message: XmppAxolotlMessage,
        postponePreKeyMessageHandling: Boolean,
    ): XmppAxolotlMessage.XmppAxolotlPlaintextMessage? {
        var plaintextMessage: XmppAxolotlMessage.XmppAxolotlPlaintextMessage? = null

        val session = getReceivingSession(message)
        val ownDeviceId = getOwnDeviceId()
        try {
            plaintextMessage = message.decrypt(session, ownDeviceId)
            val preKeyId = session.getPreKeyIdAndReset()
            if (preKeyId != null) {
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling)
            }
        } catch (e: NotEncryptedForThisDeviceException) {
            if (account.getJid().asBareJid() == message.getFrom().asBareJid() &&
                message.getSenderDeviceId() == ownDeviceId
            ) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Reflected omemo message received")
            } else {
                throw e
            }
        } catch (e: BrokenSessionException) {
            throw e
        } catch (e: OutdatedSenderException) {
            Log.e(Config.LOGTAG, account.getJid().asBareJid().toString() + ": " + e.message)
            throw e
        } catch (e: CryptoFailedException) {
            Log.w(
                Config.LOGTAG,
                getLogprefix(account) + "Failed to decrypt message from " + message.getFrom(),
                e,
            )
        }

        if (session.isFresh() && plaintextMessage != null) {
            putFreshSession(session)
        }

        return plaintextMessage
    }

    override fun reportBrokenSessionException(e: OmemoFailure.BrokenSession, postpone: Boolean) {
        val address =
            e.signalProtocolAddress ?: throw NullPointerException("signalProtocolAddress")
        Log.e(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() +
                ": broken session with " +
                address +
                " detected",
            e,
        )
        if (postpone) {
            postponedHealing.add(address)
        } else {
            notifyRequiresHealing(address)
        }
    }

    private fun notifyRequiresHealing(signalProtocolAddress: SignalProtocolAddress) {
        if (healingAttempts.add(signalProtocolAddress)) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": attempt to heal " +
                    signalProtocolAddress,
            )
            buildSessionFromPEP(
                signalProtocolAddress,
                object : OnSessionBuildFromPep {
                    override fun onSessionBuildSuccessful() {
                        Log.d(
                            Config.LOGTAG,
                            "successfully build new session from pep after detecting broken session",
                        )
                        completeSession(getReceivingSession(signalProtocolAddress))
                    }

                    override fun onSessionBuildFailed() {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": unable to build new session from pep after detecting broken session",
                        )
                    }
                },
            )
        } else {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": do not attempt to heal " +
                    signalProtocolAddress +
                    " again",
            )
        }
    }

    private fun postPreKeyMessageHandling(session: XmppAxolotlSession, postpone: Boolean) {
        if (postpone) {
            postponedSessions.add(session)
        } else {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false)
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": nothing to flush. Not republishing key",
                )
            }
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeSession(session)
            }
        }
    }

    override fun processPostponed() {
        if (postponedSessions.size > 0) {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false)
            }
        }
        val iterator = postponedSessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeSession(session)
            }
            iterator.remove()
        }
        val postponedHealingAttemptsIterator = postponedHealing.iterator()
        while (postponedHealingAttemptsIterator.hasNext()) {
            notifyRequiresHealing(postponedHealingAttemptsIterator.next())
            postponedHealingAttemptsIterator.remove()
        }
    }

    private fun trustedOrPreviouslyResponded(session: XmppAxolotlSession): Boolean =
        try {
            trustedOrPreviouslyResponded(Jid.of(session.getRemoteAddress().getName()))
        } catch (e: IllegalArgumentException) {
            false
        }

    override fun trustedOrPreviouslyResponded(jid: Jid): Boolean {
        val contact = account.getRoster().getContact(jid)
        if (contact.showInRoster() || contact.isSelf()) {
            return true
        }
        val conversation = store().findConversation(account, jid)
        return conversation != null && conversation.sentMessagesCount() > 0
    }

    private fun completeSession(session: XmppAxolotlSession) {
        val axolotlMessage =
            XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId())
        axolotlMessage.addDevice(session, true)
        try {
            val jid = Jid.of(session.getRemoteAddress().getName())
            val packet =
                mXmppConnectionService
                    .getMessageGenerator()
                    .generateKeyTransportMessage(jid, axolotlMessage)
            store().sendMessagePacket(packet)
        } catch (e: IllegalArgumentException) {
            throw Error(
                "Remote addresses are created from jid and should convert back to jid",
                e,
            )
        }
    }

    fun processReceivingKeyTransportMessage(
        message: XmppAxolotlMessage,
        postponePreKeyMessageHandling: Boolean,
    ): XmppAxolotlMessage.XmppAxolotlKeyTransportMessage? {
        val session = getReceivingSession(message)
        val keyTransportMessage = try {
            val result = message.getParameters(session, getOwnDeviceId())
            val preKeyId = session.getPreKeyIdAndReset()
            if (preKeyId != null) {
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling)
            }
            result
        } catch (e: OmemoFailure) {
            Log.d(Config.LOGTAG, "could not decrypt keyTransport message " + e.message)
            return null
        }

        if (session.isFresh() && keyTransportMessage != null) {
            putFreshSession(session)
        }

        return keyTransportMessage
    }

    private fun putFreshSession(
        session: XmppAxolotlSession,
    ): ListenableFuture<XmppAxolotlSession> {
        sessions.put(session)
        if (Config.X509_VERIFICATION) {
            if (session.getIdentityKey() != null) {
                return verifySessionWithPEP(session)
            } else {
                Log.e(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": identity key was empty after reloading for x509 verification",
                )
            }
        }
        return Futures.immediateFuture(session)
    }

    // ---------------------------------------------------------------------------------------------
    // OmemoSessionPort: the island's half of this class.
    //
    // Every member the island reaches is adapted here to the port's vocabulary - an Object for a
    // model value that pair 5 already made implement an island-neutral port, OmemoWire for the
    // message wrapper. The two ListenableFuture members are the one place an unchecked cast is
    // honest: the future's element type is erased, and what it actually carries
    // (OmemoVerifiedRtpContentMap) is an RtpContentMap.
    // ---------------------------------------------------------------------------------------------

    override fun logPrefix(): String = getLogprefix(account)

    override fun parseWire(element: Element, from: Jid): OmemoWire =
        XmppAxolotlMessage.fromElement(element, from)

    override fun wireSourceId(element: Element): Int =
        XmppAxolotlMessage.parseSourceId(element)

    @Throws(
        OmemoFailure.BrokenSession::class,
        OmemoFailure.NotEncryptedForThisDevice::class,
        OmemoFailure.OutdatedSender::class,
    )
    override fun processReceivingPayloadMessage(
        message: OmemoWire,
        postpone: Boolean,
    ): Plaintext? = processReceivingPayloadMessage(message as XmppAxolotlMessage, postpone)

    override fun processReceivingKeyTransportMessage(
        message: OmemoWire,
        postpone: Boolean,
    ): KeyTransport? = processReceivingKeyTransportMessage(message as XmppAxolotlMessage, postpone)

    @Suppress("UNCHECKED_CAST")
    override fun prepareKeyTransportMessage(conversation: Any?): ListenableFuture<OmemoWire> =
        prepareKeyTransportMessage(conversation as OmemoConversation)
            as ListenableFuture<OmemoWire>

    override fun encrypt(content: String?, conversation: Any?): OmemoWire? =
        encrypt(content, conversation as OmemoConversation)

    override fun fetchAxolotlMessageFromCache(message: Any?): OmemoWire? =
        fetchAxolotlMessageFromCache(message as OmemoMessage)

    override fun preparePayloadMessage(message: Any?, delay: Boolean) {
        preparePayloadMessage(message as OmemoMessage, delay)
    }

    @Suppress("UNCHECKED_CAST")
    override fun encryptVerified(
        contentMap: RtpContentMap,
        jid: Jid,
        deviceId: Int,
    ): ListenableFuture<OmemoVerifiedPayload<RtpContentMap>> =
        encrypt(contentMap, jid, deviceId)
            as ListenableFuture<OmemoVerifiedPayload<RtpContentMap>>

    override fun decryptVerified(
        verifiedContentMap: Any?,
        from: Jid,
    ): ListenableFuture<OmemoVerifiedPayload<RtpContentMap>> =
        decrypt(verifiedContentMap as OmemoVerifiedRtpContentMap, from)

    override fun hasFingerprintTrust(fingerprint: String): Boolean =
        getFingerprintTrust(fingerprint) != null

    override fun isFingerprintVerified(fingerprint: String): Boolean {
        val status = getFingerprintTrust(fingerprint)
        return status != null && status.isVerified()
    }

    override fun markFingerprintVerified(fingerprint: String) {
        val status = getFingerprintTrust(fingerprint)
        if (status != null) {
            setFingerprintTrust(fingerprint, status.toVerified())
        }
    }

    override fun preVerifyContactFingerprint(contact: Any?, fingerprint: String) {
        preVerifyFingerprint(contact as OmemoContact, fingerprint)
    }

    override fun preVerifyAccountFingerprint(account: Any?, fingerprint: String) {
        preVerifyFingerprint(account as OmemoAccount, fingerprint)
    }

    class NotVerifiedException(message: String) : SecurityException(message)
}
