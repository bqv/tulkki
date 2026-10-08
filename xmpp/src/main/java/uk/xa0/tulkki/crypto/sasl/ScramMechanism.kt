package uk.xa0.tulkki.crypto.sasl

import com.google.common.base.CaseFormat
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.ImmutableMap
import com.google.common.hash.HashFunction
import com.google.common.io.BaseEncoding
import com.google.common.primitives.Ints
import java.nio.charset.Charset
import java.security.InvalidKeyException
import java.util.Objects
import java.util.concurrent.ExecutionException
import javax.crypto.SecretKey
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

abstract class ScramMechanism protected constructor(
    account: AccountRef,
    @JvmField protected val channelBinding: ChannelBinding,
) : SaslMechanism(account) {

    private val gs2Header: String =
        if (channelBinding == ChannelBinding.NONE) {
            "y,,"
        } else {
            // This nonce should be different for each authentication attempt.
            String.format(
                "p=%s,,",
                CaseFormat.UPPER_UNDERSCORE.to(
                    CaseFormat.LOWER_HYPHEN,
                    channelBinding.toString(),
                ),
            )
        }
    private val clientNonce: String = CryptoHelper.random(100)
    private val clientFirstMessageBare: String =
        String.format(
            "n=%s,r=%s",
            CryptoHelper.saslEscape(CryptoHelper.saslPrep(account.getUsername())),
            clientNonce,
        )
    private lateinit var serverSignature: ByteArray
    private var downgradeProtection: DowngradeProtection? = null

    fun setDowngradeProtection(downgradeProtection: DowngradeProtection) {
        Preconditions.checkState(
            state == SaslMechanism.State.INITIAL,
            "setting downgrade protection in invalid state",
        )
        this.downgradeProtection = downgradeProtection
    }

    protected abstract fun getHMac(key: ByteArray): HashFunction

    protected abstract fun getDigest(): HashFunction

    @Throws(ExecutionException::class)
    private fun getKeyPair(password: String, salt: ByteArray, iterations: Int): KeyPair {
        val key = CacheKey(getMechanism(), password, salt, iterations)
        return CACHE.get(key) { calculateKeyPair(password, salt, iterations) }
    }

    @Throws(InvalidKeyException::class)
    private fun calculateKeyPair(password: String, salt: ByteArray, iterations: Int): KeyPair {
        val saltedPassword: ByteArray
        val serverKey: ByteArray
        val clientKey: ByteArray
        saltedPassword = hi(password.toByteArray(Charset.defaultCharset()), salt, iterations)
        serverKey = hmac(saltedPassword, SERVER_KEY_BYTES)
        clientKey = hmac(saltedPassword, CLIENT_KEY_BYTES)
        return KeyPair(clientKey, serverKey)
    }

    override fun getMechanism(): String = ""

    @Throws(InvalidKeyException::class)
    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        getHMac(key).hashBytes(input).asBytes()

    private fun digest(bytes: ByteArray): ByteArray = getDigest().hashBytes(bytes).asBytes()

    /*
     * Hi() is, essentially, PBKDF2 [RFC2898] with HMAC() as the
     * pseudorandom function (PRF) and with dkLen == output length of
     * HMAC() == output length of H().
     */
    @Throws(InvalidKeyException::class)
    private fun hi(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        var u = hmac(key, CryptoHelper.concatenateByteArrays(salt, CryptoHelper.ONE))
        val out = u.clone()
        for (i in 1 until iterations) {
            u = hmac(key, u)
            for (j in u.indices) {
                out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return out
    }

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        Preconditions.checkState(
            state == SaslMechanism.State.INITIAL,
            "Calling getClientFirstMessage from invalid state",
        )
        state = SaslMechanism.State.AUTH_TEXT_SENT
        val message = (gs2Header + clientFirstMessageBare).toByteArray(Charset.defaultCharset())
        return BaseEncoding.base64().encode(message)
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getResponse(challenge: String?, socket: SSLSocket?): String {
        return when (state) {
            SaslMechanism.State.AUTH_TEXT_SENT -> processServerFirstMessage(challenge, socket)
            SaslMechanism.State.RESPONSE_SENT -> processServerFinalMessage(challenge)
            else -> throw SaslMechanism.InvalidStateException(state)
        }
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    private fun processServerFirstMessage(challenge: String?, socket: SSLSocket?): String {
        if (challenge.isNullOrEmpty()) {
            throw SaslMechanism.AuthenticationException("challenge can not be null")
        }
        val serverFirstMessage: ByteArray
        try {
            serverFirstMessage = BaseEncoding.base64().decode(challenge)
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Unable to decode server challenge", e)
        }
        val attributes: Map<String, String>
        try {
            attributes = splitToAttributes(String(serverFirstMessage, Charset.defaultCharset()))
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Duplicate attributes")
        }
        if (attributes.containsKey("m")) {
            /*
             * RFC 5802:
             * m: This attribute is reserved for future extensibility.  In this
             * version of SCRAM, its presence in a client or a server message
             * MUST cause authentication failure when the attribute is parsed by
             * the other end.
             */
            throw SaslMechanism.AuthenticationException("Server sent reserved token: 'm'")
        }
        val i = attributes["i"]
        val s = attributes["s"]
        val nonce = attributes["r"]
        val h = attributes["h"]
        if (s.isNullOrEmpty() || nonce.isNullOrEmpty() || i.isNullOrEmpty()) {
            throw SaslMechanism.AuthenticationException("Missing attributes from server first message")
        }
        val iterationCount = Ints.tryParse(i)

        if (iterationCount == null || iterationCount < 0) {
            throw SaslMechanism.AuthenticationException("Server did not send iteration count")
        }

        if (iterationCount < ITERATION_COUNT_MINIMUM) {
            throw SaslMechanism.AuthenticationException(
                String.format(
                    "Weak iteration count. %d instead of %d",
                    iterationCount,
                    ITERATION_COUNT_MINIMUM,
                ),
            )
        }

        if (!nonce.startsWith(clientNonce)) {
            throw SaslMechanism.AuthenticationException(
                "Server nonce does not contain client nonce: " + nonce,
            )
        }

        val salt: ByteArray

        try {
            salt = BaseEncoding.base64().decode(s)
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Invalid salt in server first message")
        }

        val protection = downgradeProtection
        if (h != null && protection != null) {
            val asSeenInFeatures: String
            try {
                asSeenInFeatures = protection.asHString()
            } catch (e: SecurityException) {
                throw SaslMechanism.AuthenticationException(e)
            }
            val hashed =
                BaseEncoding.base64().encode(digest(asSeenInFeatures.toByteArray(Charset.defaultCharset())))
            if (hashed != h) {
                throw SaslMechanism.AuthenticationException("Mismatch in SSDP")
            }
        }

        val channelBindingData = getChannelBindingData(socket)

        val gs2Len = gs2Header.toByteArray(Charset.defaultCharset()).size
        val cMessage = ByteArray(gs2Len + channelBindingData.size)
        System.arraycopy(gs2Header.toByteArray(Charset.defaultCharset()), 0, cMessage, 0, gs2Len)
        System.arraycopy(channelBindingData, 0, cMessage, gs2Len, channelBindingData.size)

        val clientFinalMessageWithoutProof =
            String.format("c=%s,r=%s", BaseEncoding.base64().encode(cMessage), nonce)

        val authMessage =
            Joiner.on(',')
                .join(
                    clientFirstMessageBare,
                    String(serverFirstMessage, Charset.defaultCharset()),
                    clientFinalMessageWithoutProof,
                )

        val keys: KeyPair
        try {
            // Tulkki: `AccountRef.getPassword()` is nullable; `saslPrep` dereferences its argument,
            // as the Java's did, so a missing password is still the Java's NPE.
            keys =
                getKeyPair(
                    CryptoHelper.saslPrep(
                        account.getPassword() ?: throw NullPointerException("account has no password"),
                    ),
                    salt,
                    iterationCount,
                )
        } catch (e: ExecutionException) {
            throw SaslMechanism.AuthenticationException("Invalid keys generated")
        }
        val clientSignature: ByteArray
        try {
            serverSignature = hmac(keys.serverKey, authMessage.toByteArray(Charset.defaultCharset()))
            val storedKey = digest(keys.clientKey)

            clientSignature = hmac(storedKey, authMessage.toByteArray(Charset.defaultCharset()))
        } catch (e: InvalidKeyException) {
            throw SaslMechanism.AuthenticationException(e)
        }

        val clientProof = ByteArray(keys.clientKey.size)

        if (clientSignature.size < keys.clientKey.size) {
            throw SaslMechanism.AuthenticationException("client signature was shorter than clientKey")
        }

        for (j in clientProof.indices) {
            clientProof[j] = (keys.clientKey[j].toInt() xor clientSignature[j].toInt()).toByte()
        }

        val clientFinalMessage =
            String.format(
                "%s,p=%s",
                clientFinalMessageWithoutProof,
                BaseEncoding.base64().encode(clientProof),
            )
        state = SaslMechanism.State.RESPONSE_SENT
        return BaseEncoding.base64().encode(clientFinalMessage.toByteArray(Charset.defaultCharset()))
    }

    private fun splitToAttributes(message: String): Map<String, String> {
        val builder = ImmutableMap.builder<String, String>()
        for (token in Splitter.on(',').split(message)) {
            val tuple = Splitter.on('=').limit(2).splitToList(token)
            if (tuple.size == 2) {
                builder.put(tuple[0], tuple[1])
            }
        }
        return builder.buildOrThrow()
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    private fun processServerFinalMessage(challenge: String?): String {
        val serverFinalMessage: String
        try {
            serverFinalMessage =
                String(
                    BaseEncoding.base64().decode(challenge.orEmpty()),
                    Charset.defaultCharset(),
                )
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Invalid base64 in server final message", e)
        }
        val clientCalculatedServerFinalMessage =
            String.format("v=%s", BaseEncoding.base64().encode(serverSignature))
        if (clientCalculatedServerFinalMessage == serverFinalMessage) {
            state = SaslMechanism.State.VALID_SERVER_RESPONSE
            return ""
        }
        throw SaslMechanism.AuthenticationException(
            "Server final message does not match calculated final message",
        )
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    protected open fun getChannelBindingData(sslSocket: SSLSocket?): ByteArray {
        if (channelBinding == ChannelBinding.NONE) {
            return ByteArray(0)
        }
        throw AssertionError("getChannelBindingData needs to be overwritten")
    }

    private class CacheKey(
        private val algorithm: String,
        private val password: String,
        private val salt: ByteArray,
        private val iterations: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val cacheKey = other as CacheKey
            return iterations == cacheKey.iterations &&
                algorithm == cacheKey.algorithm &&
                password == cacheKey.password &&
                salt.contentEquals(cacheKey.salt)
        }

        override fun hashCode(): Int {
            val result = Objects.hash(algorithm, password, iterations)
            return 31 * result + salt.contentHashCode()
        }
    }

    private class KeyPair(val clientKey: ByteArray, val serverKey: ByteArray)

    companion object {

        @JvmField
        val EMPTY_KEY: SecretKey = object : SecretKey {
            override fun getAlgorithm(): String = "HMAC"

            override fun getFormat(): String = "RAW"

            override fun getEncoded(): ByteArray = ByteArray(0)
        }

        // For the SCRAM-SHA-1/SCRAM-SHA-1-PLUS SASL mechanism, servers SHOULD announce a hash
        // iteration-count of at least 4096.
        // https://datatracker.ietf.org/doc/html/rfc5802#section-5.1
        private const val ITERATION_COUNT_MINIMUM = 4096
        private val CLIENT_KEY_BYTES = "Client Key".toByteArray(Charset.defaultCharset())
        private val SERVER_KEY_BYTES = "Server Key".toByteArray(Charset.defaultCharset())
        private val CACHE: Cache<CacheKey, KeyPair> =
            CacheBuilder.newBuilder().maximumSize(10).build<CacheKey, KeyPair>()
    }
}
