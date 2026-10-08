package uk.xa0.tulkki.crypto.sasl

import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ImmutableMap
import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

class DigestMd5(account: AccountRef) : SaslMechanism(account) {

    // The Java declared a private field `state` here, hiding the inherited protected one.  Kotlin
    // cannot hide a member, so the hidden field is named for what it holds; it is private to this
    // class and every use below is the Java's own use, so nothing observable moves.
    private var md5State: SaslMechanism.State = SaslMechanism.State.INITIAL
    private var precalculatedRSPAuth: String? = null

    override fun getPriority(): Int = 10

    override fun getMechanism(): String = MECHANISM

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        Preconditions.checkState(
            md5State == SaslMechanism.State.INITIAL,
            "Calling getClientFirstMessage from invalid state",
        )
        md5State = SaslMechanism.State.AUTH_TEXT_SENT
        return ""
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getResponse(challenge: String?, socket: SSLSocket?): String {
        return when (md5State) {
            SaslMechanism.State.AUTH_TEXT_SENT -> processChallenge(challenge, socket)
            SaslMechanism.State.RESPONSE_SENT -> validateServerResponse(challenge)
            SaslMechanism.State.VALID_SERVER_RESPONSE -> validateUnnecessarySuccessMessage(challenge)
            else -> throw SaslMechanism.InvalidStateException(md5State)
        }
    }

    // ejabberd sends the RSPAuth response as a challenge and then an empty success
    // technically this is allowed as per https://datatracker.ietf.org/doc/html/rfc2222#section-5.2
    // although it says to do that only if the profile of the protocol does not allow data to be put
    // into success. which xmpp does allow. obviously
    @Throws(SaslMechanism.AuthenticationException::class)
    private fun validateUnnecessarySuccessMessage(challenge: String?): String {
        if (Strings.isNullOrEmpty(challenge)) {
            return ""
        }
        throw SaslMechanism.AuthenticationException("Success message must be empty")
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    private fun validateServerResponse(challenge: String?): String {
        Log.d(Config.LOGTAG, "DigestMd5.validateServerResponse(" + challenge + ")")
        val attributes = messageToAttributes(challenge)
        Log.d(Config.LOGTAG, "attributes: " + attributes)
        val rspauth = attributes["rspauth"]
        if (rspauth.isNullOrEmpty()) {
            throw SaslMechanism.AuthenticationException("no rspauth in server finish message")
        }
        val expected = precalculatedRSPAuth
        if (expected.isNullOrEmpty() || precalculatedRSPAuth != rspauth) {
            throw SaslMechanism.AuthenticationException("RSPAuth mismatch")
        }
        md5State = SaslMechanism.State.VALID_SERVER_RESPONSE
        return ""
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    private fun processChallenge(challenge: String?, socket: SSLSocket?): String {
        Log.d(Config.LOGTAG, "DigestMd5.processChallenge()")
        md5State = SaslMechanism.State.RESPONSE_SENT
        val attributes = messageToAttributes(challenge)

        val nonce = attributes["nonce"]

        if (nonce.isNullOrEmpty()) {
            throw SaslMechanism.AuthenticationException("Server nonce missing")
        }
        val digestUri = "xmpp/" + account.getServer()
        val nonceCount = "00000001"
        val x = account.getUsername() + ":" + account.getServer() + ":" + account.getPassword()
        val y = Hashing.md5().hashBytes(x.toByteArray(Charset.defaultCharset())).asBytes()
        val cNonce = CryptoHelper.random(100)
        val a1 =
            CryptoHelper.concatenateByteArrays(
                y,
                (":" + nonce + ":" + cNonce).toByteArray(Charset.defaultCharset()),
            )
        val a2 = "AUTHENTICATE:" + digestUri
        val ha1 = CryptoHelper.bytesToHex(Hashing.md5().hashBytes(a1).asBytes())
        val ha2 =
            CryptoHelper.bytesToHex(
                Hashing.md5().hashBytes(a2.toByteArray(Charset.defaultCharset())).asBytes(),
            )
        val kd = ha1 + ":" + nonce + ":" + nonceCount + ":" + cNonce + ":auth:" + ha2

        val a2ForResponse = ":" + digestUri
        val ha2ForResponse =
            CryptoHelper.bytesToHex(
                Hashing.md5()
                    .hashBytes(a2ForResponse.toByteArray(Charset.defaultCharset()))
                    .asBytes(),
            )
        val kdForResponseInput =
            ha1 + ":" + nonce + ":" + nonceCount + ":" + cNonce + ":auth:" + ha2ForResponse

        precalculatedRSPAuth =
            CryptoHelper.bytesToHex(
                Hashing.md5()
                    .hashBytes(kdForResponseInput.toByteArray(Charset.defaultCharset()))
                    .asBytes(),
            )

        val response =
            CryptoHelper.bytesToHex(
                Hashing.md5().hashBytes(kd.toByteArray(Charset.defaultCharset())).asBytes(),
            )

        val saslString =
            "username=\"" +
                account.getUsername() +
                "\",realm=\"" +
                account.getServer() +
                "\",nonce=\"" +
                nonce +
                "\",cnonce=\"" +
                cNonce +
                "\",nc=" +
                nonceCount +
                ",qop=auth,digest-uri=\"" +
                digestUri +
                "\",response=" +
                response +
                ",charset=utf-8"
        return BaseEncoding.base64().encode(saslString.toByteArray(Charset.defaultCharset()))
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    private fun messageToAttributes(message: String?): Map<String, String> {
        val asBytes: ByteArray
        try {
            asBytes = BaseEncoding.base64().decode(message.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Unable to decode server challenge", e)
        }
        try {
            return splitToAttributes(String(asBytes, Charset.defaultCharset()))
        } catch (e: IllegalArgumentException) {
            throw SaslMechanism.AuthenticationException("Duplicate attributes")
        }
    }

    private fun splitToAttributes(message: String): Map<String, String> {
        val builder = ImmutableMap.builder<String, String>()
        for (token in Splitter.on(',').split(message)) {
            val tuple = Splitter.on('=').limit(2).splitToList(token)
            if (tuple.size == 2) {
                val value = tuple[1]
                builder.put(tuple[0], trimQuotes(value))
            }
        }
        return builder.buildOrThrow()
    }

    companion object {
        const val MECHANISM: String = "DIGEST-MD5"

        @JvmStatic
        fun trimQuotes(input: String): String {
            if (input.length >= 2 &&
                input[0] == '"' &&
                input[input.length - 1] == '"'
            ) {
                return input.substring(1, input.length - 1)
            }
            return input
        }
    }
}
