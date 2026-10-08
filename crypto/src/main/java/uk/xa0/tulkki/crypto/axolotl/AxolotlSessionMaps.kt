package uk.xa0.tulkki.crypto.axolotl

import android.os.Bundle
import android.util.Log

import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.SignalProtocolAddress

import java.security.cert.X509Certificate

import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

// ---------------------------------------------------------------------------------------------
// The session maps, lifted out of AxolotlService so the service itself can be ported.
//
// `cryptoport` refuses `AxolotlService.java` as a normal file-port: at 1,857 lines it wants
// slices, and a class cannot be half-Java. The three `private static` map classes below do not
// name the service and nothing outside `AxolotlService` names them, so they move here first (the
// `axolotl` lane's slice 1) and the service keeps using them by their simple names.
//
// Bounded divergence, recorded so a future upstream patch is not a surprise: they were nested
// (`AxolotlService.AxolotlAddressMap`, `AxolotlService$SessionMap`, ...) and are now top-level in
// the same package. A nested class's binary name cannot be preserved across the move - the
// `AxolotlService$` prefix is gone - while every *member* name and signature is kept. That rename
// is invisible because nothing outside `AxolotlService.java` references them (checked across the
// tree: no other file names `AxolotlAddressMap`, `SessionMap`, `FetchStatusMap`,
// `OnDeviceIdsFetched`, `OnMultipleDeviceIdFetched` or `OnSessionBuildFromPep`). An upstream patch
// editing those classes will now conflict on the deletion, which is the price of the slice.
// ---------------------------------------------------------------------------------------------

/** One device-list fetch's callbacks, all of them. */
internal fun interface OnDeviceIdsFetched {
    fun fetched(jid: Jid, deviceIds: Set<Int>?)
}

/** The single completion callback for a multi-JID device-list fetch. */
internal fun interface OnMultipleDeviceIdFetched {
    fun fetched()
}

/** What a `buildSessionFromPEP` attempt reports. */
internal interface OnSessionBuildFromPep {
    fun onSessionBuildSuccessful()

    fun onSessionBuildFailed()
}

/**
 * A `name -> deviceId -> T` map guarded by one lock, the shape upstream keeps.
 *
 * `open` because [SessionMap] and [FetchStatusMap] extend it; `put` is `open` because [SessionMap]
 * overrides it to mark a session not-fresh.
 */
internal open class AxolotlAddressMap<T> {
    protected val MAP_LOCK = Any()
    protected val map: MutableMap<String, MutableMap<Int, T>> = HashMap()

    open fun put(address: SignalProtocolAddress, value: T) {
        synchronized(MAP_LOCK) {
            var devices = map[address.name]
            if (devices == null) {
                devices = HashMap()
                map[address.name] = devices
            }
            devices[address.deviceId] = value
        }
    }

    fun get(address: SignalProtocolAddress): T? {
        synchronized(MAP_LOCK) {
            val devices = map[address.name] ?: return null
            return devices[address.deviceId]
        }
    }

    fun getAll(name: String): MutableMap<Int, T> {
        synchronized(MAP_LOCK) {
            return map[name] ?: HashMap()
        }
    }

    fun hasAny(address: SignalProtocolAddress): Boolean {
        synchronized(MAP_LOCK) {
            val devices = map[address.name]
            return devices != null && devices.isNotEmpty()
        }
    }

    fun clear() {
        map.clear()
    }
}

/** The live [XmppAxolotlSession]s, filled from the store and keyed by address. */
internal class SessionMap(
    @Suppress("unused") private val xmppConnectionService: XmppConnectionService,
    store: SQLiteAxolotlStore,
    private val account: OmemoAccount,
) : AxolotlAddressMap<XmppAxolotlSession>() {

    init {
        fillMap(store)
    }

    fun findCounterpartsForSourceId(sid: Int): MutableSet<Jid> {
        val candidates = HashSet<Jid>()
        synchronized(MAP_LOCK) {
            for ((key, value) in map) {
                if (value.containsKey(sid)) {
                    candidates.add(Jid.of(key))
                }
            }
        }
        return candidates
    }

    private fun putDevicesForJid(
        bareJid: String,
        deviceIds: List<Int>,
        store: SQLiteAxolotlStore,
    ) {
        for (deviceId in deviceIds) {
            val axolotlAddress = SignalProtocolAddress(bareJid, deviceId)
            val identityKey =
                store.loadSession(axolotlAddress).getSessionState().getRemoteIdentityKey()
            if (Config.X509_VERIFICATION) {
                // Java dereferenced `identityKey` unconditionally here, so a null one was a
                // NullPointerException at this line; the explicit throw keeps that outcome.
                val verifiedKey = identityKey ?: throw NullPointerException("identityKey")
                val certificate =
                    store.getFingerprintCertificate(
                        CryptoHelper.bytesToHex(verifiedKey.getPublicKey().serialize())
                    )
                if (certificate != null) {
                    val information: Bundle =
                        CryptoHelper.extractCertificateInformation(certificate)
                    try {
                        val cn = information.getString("subject_cn")
                        val jid = Jid.of(bareJid)
                        Log.d(Config.LOGTAG, "setting common name for $jid to $cn")
                        account.getRoster().getContact(jid).setCommonName(cn)
                    } catch (ignored: IllegalArgumentException) {
                        //ignored
                    }
                }
            }
            // Java passed a possibly-null `identityKey` to the four-argument constructor, which
            // simply stored it; the three-argument overload leaves the same field null, so the
            // null case takes that one rather than a non-null assertion that would change behaviour.
            this.put(
                axolotlAddress,
                if (identityKey != null) {
                    XmppAxolotlSession(account, store, axolotlAddress, identityKey)
                } else {
                    XmppAxolotlSession(account, store, axolotlAddress)
                },
            )
        }
    }

    private fun fillMap(store: SQLiteAxolotlStore) {
        var deviceIds: List<Int> =
            store.getSubDeviceSessions(account.getJid().asBareJid().toString())
        putDevicesForJid(account.getJid().asBareJid().toString(), deviceIds, store)
        for (address in store.getKnownAddresses()) {
            deviceIds = store.getSubDeviceSessions(address)
            putDevicesForJid(address, deviceIds, store)
        }
    }

    override fun put(address: SignalProtocolAddress, value: XmppAxolotlSession) {
        super.put(address, value)
        value.setNotFresh()
    }

    fun put(session: XmppAxolotlSession) {
        this.put(session.getRemoteAddress(), session)
    }
}

/** [AxolotlAddressMap] holding a per-device fetch status. */
internal class FetchStatusMap : AxolotlAddressMap<OmemoSessionPort.FetchStatus>() {

    fun clearErrorFor(jid: Jid) {
        synchronized(MAP_LOCK) {
            val devices = map[jid.asBareJid().toString()] ?: return
            for (entry in devices.entries) {
                if (entry.value == OmemoSessionPort.FetchStatus.ERROR) {
                    Log.d(
                        Config.LOGTAG,
                        "resetting error for ${jid.asBareJid()}(${entry.key})",
                    )
                    entry.setValue(OmemoSessionPort.FetchStatus.TIMEOUT)
                }
            }
        }
    }
}
