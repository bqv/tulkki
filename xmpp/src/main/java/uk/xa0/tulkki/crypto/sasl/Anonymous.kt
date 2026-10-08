package uk.xa0.tulkki.crypto.sasl

import com.google.common.base.Preconditions
import com.google.common.base.Strings
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.refs.AccountRef

class Anonymous(account: AccountRef) : SaslMechanism(account) {

    override fun getPriority(): Int = 0

    override fun getMechanism(): String = MECHANISM

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        Preconditions.checkState(
            state == SaslMechanism.State.INITIAL,
            "Calling getClientFirstMessage from invalid state",
        )
        state = SaslMechanism.State.AUTH_TEXT_SENT
        return ""
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
        const val MECHANISM: String = "ANONYMOUS"
    }
}
