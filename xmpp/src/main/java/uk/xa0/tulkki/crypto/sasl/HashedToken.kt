package uk.xa0.tulkki.crypto.sasl

import android.util.Base64
import android.util.Log
import com.google.common.base.MoreObjects
import com.google.common.base.Strings
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Multimap
import com.google.common.hash.HashFunction
import com.google.common.primitives.Bytes
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.utils.SSLSockets

private const val PREFIX = "HT"
private val HASH_FUNCTIONS = listOf("SHA-512", "SHA-256")
private val INITIATOR = "Initiator".toByteArray(StandardCharsets.UTF_8)
private val RESPONDER = "Responder".toByteArray(StandardCharsets.UTF_8)

abstract class HashedToken protected constructor(
    account: AccountRef,
    @JvmField protected val channelBinding: ChannelBinding,
) : SaslMechanism(account), ChannelBindingMechanism {

    override fun getPriority(): Int {
        throw UnsupportedOperationException()
    }

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        val token = Strings.nullToEmpty(account.getFastToken())
        val hashing = getHashFunction(token.toByteArray(StandardCharsets.UTF_8))
        val cbData = getChannelBindingData(sslSocket)
        val initiatorHashedToken = hashing.hashBytes(Bytes.concat(INITIATOR, cbData)).asBytes()
        val firstMessage =
            Bytes.concat(
                account.getUsername().toByteArray(StandardCharsets.UTF_8),
                byteArrayOf(0x00),
                initiatorHashedToken,
            )
        return Base64.encodeToString(firstMessage, Base64.NO_WRAP)
    }

    private fun getChannelBindingData(sslSocket: SSLSocket?): ByteArray {
        if (channelBinding == ChannelBinding.NONE) {
            return ByteArray(0)
        }
        try {
            return ChannelBindingMechanism.getChannelBindingData(sslSocket, channelBinding)
        } catch (e: SaslMechanism.AuthenticationException) {
            Log.e(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": unable to retrieve channel binding data for " +
                    getMechanism(),
                e,
            )
            return ByteArray(0)
        }
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getResponse(challenge: String?, socket: SSLSocket?): String? {
        val responderMessage: ByteArray
        try {
            responderMessage = Base64.decode(challenge, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw SaslMechanism.AuthenticationException("Unable to decode responder message", e)
        }
        val token = Strings.nullToEmpty(account.getFastToken())
        val hashing = getHashFunction(token.toByteArray(StandardCharsets.UTF_8))
        val cbData = getChannelBindingData(socket)
        val expectedResponderMessage =
            hashing.hashBytes(Bytes.concat(RESPONDER, cbData)).asBytes()
        if (responderMessage.contentEquals(expectedResponderMessage)) {
            return null
        }
        throw SaslMechanism.AuthenticationException("Responder message did not match")
    }

    protected abstract fun getHashFunction(key: ByteArray): HashFunction

    abstract fun getTokenMechanism(): Mechanism

    override fun getMechanism(): String = getTokenMechanism().name()

    class Mechanism(
        @JvmField val hashFunction: String,
        @JvmField val channelBinding: ChannelBinding,
    ) {

        companion object {

            @JvmStatic
            fun of(mechanism: String): Mechanism {
                val first = mechanism.indexOf('-')
                val last = mechanism.lastIndexOf('-')
                if (last <= first || mechanism.length <= last) {
                    throw IllegalArgumentException("Not a valid HashedToken name")
                }
                if (mechanism.substring(0, first) == PREFIX) {
                    val hashFunction = mechanism.substring(first + 1, last)
                    val cbShortName = mechanism.substring(last + 1)
                    val channelBinding = ChannelBinding.SHORT_NAMES.inverse()[cbShortName]
                    if (channelBinding == null) {
                        throw IllegalArgumentException("Unknown channel binding " + cbShortName)
                    }
                    return Mechanism(hashFunction, channelBinding)
                } else {
                    throw IllegalArgumentException("HashedToken name does not start with HT")
                }
            }

            @JvmStatic
            fun ofOrNull(mechanism: String?): Mechanism? {
                return try {
                    if (mechanism == null) null else of(mechanism)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

            @JvmStatic
            @JvmSuppressWildcards
            fun of(mechanisms: Collection<String>): Multimap<String, ChannelBinding> {
                val builder = ImmutableMultimap.builder<String, ChannelBinding>()
                for (name in mechanisms) {
                    try {
                        val mechanism = of(name)
                        builder.put(mechanism.hashFunction, mechanism.channelBinding)
                    } catch (e: IllegalArgumentException) {
                    }
                }
                return builder.build()
            }

            @JvmStatic
            @JvmSuppressWildcards
            fun best(
                mechanisms: Collection<String>,
                sslVersion: SSLSockets.Version,
            ): Mechanism? {
                val multimap = of(mechanisms)
                for (hashFunction in HASH_FUNCTIONS) {
                    val channelBindings = multimap[hashFunction]
                    if (channelBindings.isEmpty()) {
                        continue
                    }
                    val cb = ChannelBinding.best(channelBindings, sslVersion)
                    return Mechanism(hashFunction, cb)
                }
                return null
            }
        }

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("hashFunction", hashFunction)
                .add("channelBinding", channelBinding)
                .toString()

        fun name(): String =
            String.format("%s-%s-%s", PREFIX, hashFunction, ChannelBinding.SHORT_NAMES[channelBinding])
    }

    override fun getChannelBinding(): ChannelBinding = channelBinding
}
