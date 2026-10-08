package uk.xa0.tulkki.crypto.sasl

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import uk.xa0.tulkki.xmpp.refs.AccountRef

class ScramSha512Plus(account: AccountRef, channelBinding: ChannelBinding) :
    ScramPlusMechanism(account, channelBinding) {

    override fun getHMac(key: ByteArray): HashFunction =
        if (key.isEmpty()) Hashing.hmacSha512(ScramMechanism.EMPTY_KEY) else Hashing.hmacSha512(key)

    override fun getDigest(): HashFunction = Hashing.sha512()

    override fun getPriority(): Int = 45 + ChannelBinding.priority(channelBinding)

    override fun getMechanism(): String = MECHANISM

    companion object {
        const val MECHANISM: String = "SCRAM-SHA-512-PLUS"
    }
}
