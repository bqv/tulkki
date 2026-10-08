package uk.xa0.tulkki.crypto.sasl

import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.io.BaseEncoding
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.refs.AccountRef

class Plain(account: AccountRef) : SaslMechanism(account) {

    override fun getPriority(): Int = 10

    override fun getMechanism(): String = MECHANISM

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        Preconditions.checkState(
            state == SaslMechanism.State.INITIAL,
            "Calling getClientFirstMessage from invalid state",
        )
        state = SaslMechanism.State.AUTH_TEXT_SENT
        return getMessage(account.getUsername(), account.getPassword())
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getResponse(challenge: String?, sslSocket: SSLSocket?): String? {
        checkState(SaslMechanism.State.AUTH_TEXT_SENT)
        if (Strings.isNullOrEmpty(challenge)) {
            state = SaslMechanism.State.VALID_SERVER_RESPONSE
            return null
        }
        throw SaslMechanism.AuthenticationException("Unexpected server response")
    }

    companion object {
        const val MECHANISM: String = "PLAIN"

        /**
         * Tulkki: `password` is nullable because `AccountRef.getPassword()` is, and the Java's own
         * body tolerated the null - `'\u0000' + username + '\u0000' + password` concatenates it,
         * so a missing password crossed the wire as the four characters `null` rather than an NPE.
         * The conversion narrowed the parameter to `String`; the Java's tolerance is restored here
         * (the JVM descriptor is unchanged, and `DigestMd5`'s identical concatenation keeps the
         * same tolerance at its own call site).
         */
        @JvmStatic
        fun getMessage(username: String, password: String?): String {
            val message = '\u0000' + username + '\u0000' + password
            return BaseEncoding.base64().encode(message.toByteArray(Charset.defaultCharset()))
        }
    }
}
