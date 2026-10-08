package uk.xa0.tulkki.crypto.axolotl

import android.util.Log

import androidx.annotation.Nullable

import org.whispersystems.libsignal.DuplicateMessageException
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.InvalidMessageException
import org.whispersystems.libsignal.InvalidVersionException
import org.whispersystems.libsignal.LegacyMessageException
import org.whispersystems.libsignal.NoSessionException
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.UntrustedIdentityException
import org.whispersystems.libsignal.protocol.CiphertextMessage
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage

import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

class XmppAxolotlSession : Comparable<XmppAxolotlSession> {
    private val cipher: SessionCipher
    private val sqLiteAxolotlStore: SQLiteAxolotlStore
    private val remoteAddress: SignalProtocolAddress
    private val account: OmemoAccount
    private var identityKey: IdentityKey? = null
    private var preKeyId: Int? = null
    private var fresh = true

    constructor(
        account: OmemoAccount,
        store: SQLiteAxolotlStore,
        remoteAddress: SignalProtocolAddress,
        identityKey: IdentityKey
    ) : this(account, store, remoteAddress) {
        this.identityKey = identityKey
    }

    constructor(
        account: OmemoAccount,
        store: SQLiteAxolotlStore,
        remoteAddress: SignalProtocolAddress
    ) {
        this.cipher = SessionCipher(store, remoteAddress)
        this.remoteAddress = remoteAddress
        this.sqLiteAxolotlStore = store
        this.account = account
    }

    fun getPreKeyIdAndReset(): Int? {
        val preKeyId = this.preKeyId
        this.preKeyId = null
        return preKeyId
    }

    fun getFingerprint(): String? {
        val identityKey = this.identityKey
        return if (identityKey == null) null
        else CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize())
    }

    fun getIdentityKey(): IdentityKey? = identityKey

    fun getRemoteAddress(): SignalProtocolAddress = remoteAddress

    fun isFresh(): Boolean = fresh

    fun setNotFresh() {
        this.fresh = false
    }

    // Widened from `protected` for the `axolotl` lane's port of AxolotlService: Kotlin's
    // `protected` is subclass-only, while Java's `protected` also reached the same package, and
    // AxolotlService (a sibling, not a subclass) assigned the trust at three sites. `internal`
    // keeps it module-scoped; its only callers are Kotlin in this module, so the mangled JVM name
    // is not observed. Nothing outside this module named it (checked across the tree).
    internal fun setTrust(status: FingerprintStatus) {
        sqLiteAxolotlStore.setFingerprintStatus(
            getFingerprint() ?: throw NullPointerException("fingerprint"),
            status
        )
    }

    fun getTrust(): FingerprintStatus {
        val status = sqLiteAxolotlStore.getFingerprintStatus(getFingerprint())
        return status ?: FingerprintStatus.createActiveUndecided()
    }

    @Nullable
    @Throws(CryptoFailedException::class, BrokenSessionException::class)
    fun processReceiving(possibleKeys: List<AxolotlKey>): ByteArray? {
        var plaintext: ByteArray? = null
        val status = getTrust()
        if (!status.isCompromised()) {
            val iterator = possibleKeys.iterator()
            while (iterator.hasNext()) {
                val encryptedKey = iterator.next()
                try {
                    if (encryptedKey.prekey) {
                        val preKeySignalMessage = PreKeySignalMessage(encryptedKey.key)
                        val optionalPreKeyId = preKeySignalMessage.preKeyId
                        val identityKey = preKeySignalMessage.identityKey
                        if (!optionalPreKeyId.isPresent()) {
                            if (iterator.hasNext()) {
                                continue
                            }
                            throw CryptoFailedException(
                                "PreKeyWhisperMessage did not contain a PreKeyId"
                            )
                        }
                        preKeyId = optionalPreKeyId.get()
                        if (this.identityKey != null && this.identityKey != identityKey) {
                            if (iterator.hasNext()) {
                                continue
                            }
                            throw CryptoFailedException(
                                "Received PreKeyWhisperMessage but preexisting identity key changed."
                            )
                        }
                        this.identityKey = identityKey
                        plaintext = cipher.decrypt(preKeySignalMessage)
                    } else {
                        val signalMessage = SignalMessage(encryptedKey.key)
                        try {
                            plaintext = cipher.decrypt(signalMessage)
                        } catch (e: InvalidMessageException) {
                            if (iterator.hasNext()) {
                                Log.w(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString() +
                                        ": ignoring crypto exception because possible keys left to try",
                                    e
                                )
                                continue
                            }
                            throw BrokenSessionException(this.remoteAddress, e)
                        } catch (e: NoSessionException) {
                            if (iterator.hasNext()) {
                                Log.w(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString() +
                                        ": ignoring crypto exception because possible keys left to try",
                                    e
                                )
                                continue
                            }
                            throw BrokenSessionException(this.remoteAddress, e)
                        }
                        preKeyId = null //better safe than sorry because we use that to do special after prekey handling
                    }
                } catch (e: InvalidVersionException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: InvalidKeyException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: LegacyMessageException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: InvalidMessageException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: DuplicateMessageException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: InvalidKeyIdException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                } catch (e: UntrustedIdentityException) {
                    if (iterator.hasNext()) {
                        Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": ignoring crypto exception because possible keys left to try",
                            e
                        )
                        continue
                    }
                    throw CryptoFailedException("Error decrypting SignalMessage", e)
                }
                if (iterator.hasNext()) {
                    break
                }
            }
            if (!status.isActive()) {
                setTrust(status.toActive())
                //TODO: also (re)add to device list?
            }
        } else {
            throw CryptoFailedException(
                "not encrypting omemo message from fingerprint " + getFingerprint() +
                    " because it was marked as compromised"
            )
        }
        return plaintext
    }

    @Nullable
    fun processSending(outgoingMessage: ByteArray, ignoreSessionTrust: Boolean): AxolotlKey? {
        val status = getTrust()
        return if (ignoreSessionTrust || status.isTrustedAndActive()) {
            try {
                val ciphertextMessage = cipher.encrypt(outgoingMessage)
                AxolotlKey(
                    getRemoteAddress().getDeviceId(),
                    ciphertextMessage.serialize(),
                    ciphertextMessage.getType() == CiphertextMessage.PREKEY_TYPE
                )
            } catch (e: UntrustedIdentityException) {
                null
            }
        } else {
            null
        }
    }

    fun getAccount(): OmemoAccount = account

    override fun compareTo(o: XmppAxolotlSession): Int = getTrust().compareTo(o.getTrust())

    class AxolotlKey(
        @JvmField val deviceId: Int,
        @JvmField val key: ByteArray,
        @JvmField val prekey: Boolean
    )
}
