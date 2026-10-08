package uk.xa0.tulkki.crypto.axolotl

import android.os.Build
import android.util.Base64
import android.util.Log

import java.nio.charset.Charset
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SecureRandom

import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid

class XmppAxolotlMessage : OmemoWire {
    private val keys: MutableList<XmppAxolotlSession.AxolotlKey>
    private val from: Jid
    private val sourceDeviceId: Int
    private var innerKey: ByteArray? = null
    private var ciphertext: ByteArray? = null
    private var authtagPlusInnerKey: ByteArray? = null
    private var iv: ByteArray? = null

    internal constructor(axolotlMessage: Element, from: Jid) {
        this.from = from
        val header = axolotlMessage.findChild(HEADER) ?: throw NullPointerException()
        this.sourceDeviceId = try {
            Integer.parseInt(header.getAttribute(SOURCEID))
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("invalid source id")
        }
        val keyElements = header.getChildren()
        this.keys = ArrayList()
        for (keyElement in keyElements) {
            when (keyElement.getName()) {
                KEYTAG -> {
                    try {
                        val recipientId = Integer.parseInt(keyElement.getAttribute(REMOTEID))
                        val key = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT)
                        val isPreKey = keyElement.getAttributeAsBoolean("prekey")
                        this.keys.add(
                            XmppAxolotlSession.AxolotlKey(recipientId, key, isPreKey)
                        )
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException("invalid remote id")
                    }
                }
                IVTAG -> {
                    if (this.iv != null) {
                        throw IllegalArgumentException("Duplicate iv entry")
                    }
                    iv = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT)
                }
                else ->
                    Log.w(Config.LOGTAG, "Unexpected element in header: " + keyElement.toString())
            }
        }
        val payloadElement =
            axolotlMessage.findChildEnsureSingle(PAYLOAD, OmemoSessionPort.PEP_PREFIX)
        if (payloadElement != null) {
            ciphertext = Base64.decode(payloadElement.getContent().trim(), Base64.DEFAULT)
        }
    }

    internal constructor(from: Jid, sourceDeviceId: Int) {
        this.from = from
        this.sourceDeviceId = sourceDeviceId
        this.keys = ArrayList()
        this.iv = generateIv()
        this.innerKey = generateKey()
    }

    override fun hasPayload(): Boolean = ciphertext != null

    @Throws(CryptoFailedException::class)
    fun encrypt(plaintext: String?) {
        if (plaintext == null) return

        try {
            val secretKey: SecretKey = SecretKeySpec(innerKey, KEYTYPE)
            val ivSpec = IvParameterSpec(iv)
            val cipher = getCipher()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            this.ciphertext = cipher.doFinal(
                if (Config.OMEMO_PADDING) getPaddedBytes(plaintext)
                else plaintext.toByteArray(Charset.defaultCharset())
            )
            val currentCiphertext = this.ciphertext
            if (Config.PUT_AUTH_TAG_INTO_KEY && currentCiphertext != null) {
                this.authtagPlusInnerKey = ByteArray(16 + 16)
                val ciphertext = ByteArray(currentCiphertext.size - 16)
                System.arraycopy(currentCiphertext, 0, ciphertext, 0, ciphertext.size)
                System.arraycopy(
                    currentCiphertext,
                    ciphertext.size,
                    authtagPlusInnerKey,
                    16,
                    16
                )
                val innerKey = this.innerKey
                if (innerKey != null) {
                    System.arraycopy(innerKey, 0, authtagPlusInnerKey, 0, innerKey.size)
                }
                this.ciphertext = ciphertext
            }
        } catch (e: NoSuchAlgorithmException) {
            throw CryptoFailedException(e)
        } catch (e: NoSuchPaddingException) {
            throw CryptoFailedException(e)
        } catch (e: InvalidKeyException) {
            throw CryptoFailedException(e)
        } catch (e: IllegalBlockSizeException) {
            throw CryptoFailedException(e)
        } catch (e: BadPaddingException) {
            throw CryptoFailedException(e)
        } catch (e: NoSuchProviderException) {
            throw CryptoFailedException(e)
        } catch (e: InvalidAlgorithmParameterException) {
            throw CryptoFailedException(e)
        }
    }

    override fun getFrom(): Jid = this.from

    fun getSenderDeviceId(): Int = sourceDeviceId

    fun addDevice(session: XmppAxolotlSession) {
        addDevice(session, false)
    }

    fun addDevice(session: XmppAxolotlSession, ignoreSessionTrust: Boolean) {
        val outgoing: ByteArray =
            authtagPlusInnerKey ?: (innerKey ?: throw NullPointerException("innerKey"))
        val key = session.processSending(outgoing, ignoreSessionTrust)
        if (key != null) {
            keys.add(key)
        }
    }

    override fun getInnerKey(): ByteArray? = innerKey

    override fun getIV(): ByteArray? = this.iv

    override fun toElement(): Element {
        val encryptionElement = Element(CONTAINERTAG, OmemoSessionPort.PEP_PREFIX)
        val headerElement = encryptionElement.addChild(HEADER)
        headerElement.setAttribute(SOURCEID, sourceDeviceId)
        for (key in keys) {
            val keyElement = Element(KEYTAG)
            keyElement.setAttribute(REMOTEID, key.deviceId)
            if (key.prekey) {
                keyElement.setAttribute("prekey", "true")
            }
            keyElement.setContent(Base64.encodeToString(key.key, Base64.NO_WRAP))
            headerElement.addChild(keyElement)
        }
        headerElement.addChild(IVTAG).setContent(Base64.encodeToString(iv, Base64.NO_WRAP))
        val payloadCiphertext = ciphertext
        if (payloadCiphertext != null) {
            val payload = encryptionElement.addChild(PAYLOAD)
            payload.setContent(Base64.encodeToString(payloadCiphertext, Base64.NO_WRAP))
        }
        return encryptionElement
    }

    @Throws(
        CryptoFailedException::class,
        NotEncryptedForThisDeviceException::class,
        BrokenSessionException::class
    )
    private fun unpackKey(session: XmppAxolotlSession, sourceDeviceId: Int?): ByteArray? {
        val possibleKeys = ArrayList<XmppAxolotlSession.AxolotlKey>()
        for (key in keys) {
            if (key.deviceId == sourceDeviceId) {
                possibleKeys.add(key)
            }
        }
        if (possibleKeys.size == 0) {
            throw NotEncryptedForThisDeviceException()
        }
        return session.processReceiving(possibleKeys)
    }

    @Throws(
        CryptoFailedException::class,
        NotEncryptedForThisDeviceException::class,
        BrokenSessionException::class
    )
    fun getParameters(
        session: XmppAxolotlSession,
        sourceDeviceId: Int?
    ): XmppAxolotlKeyTransportMessage =
        XmppAxolotlKeyTransportMessage(
            session.getFingerprint(),
            unpackKey(session, sourceDeviceId),
            getIV()
        )

    @Throws(
        CryptoFailedException::class,
        NotEncryptedForThisDeviceException::class,
        BrokenSessionException::class,
        OutdatedSenderException::class
    )
    fun decrypt(
        session: XmppAxolotlSession,
        sourceDeviceId: Int?
    ): XmppAxolotlPlaintextMessage? {
        var plaintextMessage: XmppAxolotlPlaintextMessage? = null
        var key = unpackKey(session, sourceDeviceId)
        if (key != null) {
            try {
                if (key.size < 32) {
                    throw OutdatedSenderException(
                        "Key did not contain auth tag. Sender needs to update their OMEMO client"
                    )
                }
                val authTagLength = key.size - 16
                val ciphertext = this.ciphertext ?: throw NullPointerException("ciphertext")
                val iv = this.iv ?: throw NullPointerException("iv")
                val newCipherText = ByteArray(key.size - 16 + ciphertext.size)
                val newKey = ByteArray(16)
                System.arraycopy(ciphertext, 0, newCipherText, 0, ciphertext.size)
                System.arraycopy(key, 16, newCipherText, ciphertext.size, authTagLength)
                System.arraycopy(key, 0, newKey, 0, newKey.size)
                this.ciphertext = newCipherText
                key = newKey

                val cipher = getCipher()
                val keySpec = SecretKeySpec(key, KEYTYPE)
                val ivSpec = IvParameterSpec(iv)

                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

                val plaintext = String(cipher.doFinal(newCipherText), Charset.defaultCharset())
                plaintextMessage = XmppAxolotlPlaintextMessage(
                    if (Config.OMEMO_PADDING) plaintext.trim() else plaintext,
                    session.getFingerprint()
                )
            } catch (e: NoSuchAlgorithmException) {
                throw CryptoFailedException(e)
            } catch (e: NoSuchPaddingException) {
                throw CryptoFailedException(e)
            } catch (e: InvalidKeyException) {
                throw CryptoFailedException(e)
            } catch (e: InvalidAlgorithmParameterException) {
                throw CryptoFailedException(e)
            } catch (e: IllegalBlockSizeException) {
                throw CryptoFailedException(e)
            } catch (e: BadPaddingException) {
                throw CryptoFailedException(e)
            } catch (e: NoSuchProviderException) {
                throw CryptoFailedException(e)
            }
        }
        return plaintextMessage
    }

    class XmppAxolotlPlaintextMessage internal constructor(
        private val plaintext: String,
        private val fingerprint: String?
    ) : OmemoSessionPort.Plaintext {

        /** Non-null by construction: the only caller passes the `String` `decrypt` just built. */
        override fun getPlaintext(): String = plaintext

        /** Nullable on evidence: `XmppAxolotlSession.getFingerprint()` is null without an identity key. */
        override fun getFingerprint(): String? = fingerprint
    }

    class XmppAxolotlKeyTransportMessage internal constructor(
        private val fingerprint: String?,
        private val key: ByteArray?,
        private val iv: ByteArray?
    ) : OmemoSessionPort.KeyTransport {

        override fun getFingerprint(): String? = fingerprint

        override fun getKey(): ByteArray? = key

        override fun getIv(): ByteArray? = iv
    }

    companion object {
        const val CONTAINERTAG = "encrypted"
        private const val HEADER = "header"
        private const val SOURCEID = "sid"
        private const val KEYTAG = "key"
        private const val REMOTEID = "rid"
        private const val IVTAG = "iv"
        private const val PAYLOAD = "payload"

        private const val KEYTYPE = "AES"
        private const val CIPHERMODE = "AES/GCM/NoPadding"
        private const val PROVIDER = "BC"

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun parseSourceId(axolotlMessage: Element): Int {
            val header = axolotlMessage.findChild(HEADER)
            if (header == null) {
                throw IllegalArgumentException("No header found")
            }
            return try {
                Integer.parseInt(header.getAttribute(SOURCEID))
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("invalid source id")
            }
        }

        @JvmStatic
        fun fromElement(element: Element, from: Jid): XmppAxolotlMessage =
            XmppAxolotlMessage(element, from)

        private fun generateKey(): ByteArray {
            return try {
                val generator = KeyGenerator.getInstance(KEYTYPE)
                generator.init(128)
                generator.generateKey().getEncoded()
            } catch (e: NoSuchAlgorithmException) {
                throw IllegalStateException(e)
            }
        }

        private fun generateIv(): ByteArray {
            val random = SecureRandom()
            val iv = ByteArray(12)
            random.nextBytes(iv)
            return iv
        }

        /**
         * The AES/GCM cipher this message is encrypted and decrypted with.
         *
         * The provider is a platform question: API 28 (P) ships an implementation, and before that
         * the provider has to be named. This used to be `Compatibility.twentyEight()` - a helper in
         * `:app`, the composition root - and it was the whole of the forbidden `:crypto` -> `:app`
         * direction (pair 1 of docs/MIGRATION.md "The cycle rules" §3). A package move cannot fix that
         * direction, so the platform check lives with its only caller instead.
         */
        @Throws(
            NoSuchAlgorithmException::class,
            NoSuchPaddingException::class,
            NoSuchProviderException::class
        )
        private fun getCipher(): Cipher =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Cipher.getInstance(CIPHERMODE)
            } else {
                Cipher.getInstance(CIPHERMODE, PROVIDER)
            }

        private fun getPaddedBytes(plaintext: String): ByteArray {
            val plainLength = plaintext.toByteArray(Charset.defaultCharset()).size
            val pad = Math.max(64, (plainLength / 32 + 1) * 32) - plainLength
            val random = SecureRandom()
            val left = random.nextInt(pad)
            val right = pad - left
            val builder = StringBuilder(plaintext)
            for (i in 0 until left) {
                builder.insert(0, if (random.nextBoolean()) "\t" else " ")
            }
            for (i in 0 until right) {
                builder.append(if (random.nextBoolean()) "\t" else " ")
            }
            return builder.toString().toByteArray(Charset.defaultCharset())
        }
    }
}
