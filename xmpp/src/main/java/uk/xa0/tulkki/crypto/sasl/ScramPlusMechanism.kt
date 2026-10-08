package uk.xa0.tulkki.crypto.sasl

import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xmpp.refs.AccountRef

abstract class ScramPlusMechanism(account: AccountRef, channelBinding: ChannelBinding) :
    ScramMechanism(account, channelBinding), ChannelBindingMechanism {

    @Throws(SaslMechanism.AuthenticationException::class)
    override fun getChannelBindingData(sslSocket: SSLSocket?): ByteArray =
        ChannelBindingMechanism.getChannelBindingData(sslSocket, channelBinding)

    override fun getChannelBinding(): ChannelBinding = channelBinding
}
