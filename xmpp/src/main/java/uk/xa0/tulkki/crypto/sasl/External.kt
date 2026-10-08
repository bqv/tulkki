package uk.xa0.tulkki.crypto.sasl

import com.google.common.base.Preconditions
import com.google.common.io.BaseEncoding
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.refs.AccountRef

class External(account: AccountRef) : SaslMechanism(account) {

    override fun getPriority(): Int = 25

    override fun getMechanism(): String = MECHANISM

    override fun getClientFirstMessage(sslSocket: SSLSocket?): String {
        Preconditions.checkState(
            state == SaslMechanism.State.INITIAL,
            "Calling getClientFirstMessage from invalid state",
        )
        state = SaslMechanism.State.AUTH_TEXT_SENT
        val message = account.getJid().asBareJid().toString()
        return BaseEncoding.base64().encode(message.toByteArray(Charset.defaultCharset()))
    }

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getResponse(challenge: String?, sslSocket: SSLSocket?): String {
        // TODO check that state is in auth text sent and move to finished
        return ""
    }

    companion object {
        const val MECHANISM: String = "EXTERNAL"
    }
}
