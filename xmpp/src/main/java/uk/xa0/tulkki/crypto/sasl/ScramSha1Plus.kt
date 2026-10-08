package uk.xa0.tulkki.crypto.sasl

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import uk.xa0.tulkki.xmpp.refs.AccountRef

class ScramSha1Plus(account: AccountRef, channelBinding: ChannelBinding) :
    ScramPlusMechanism(account, channelBinding) {

    override fun getHMac(key: ByteArray): HashFunction =
        if (key.isEmpty()) Hashing.hmacSha1(ScramMechanism.EMPTY_KEY) else Hashing.hmacSha1(key)

    override fun getDigest(): HashFunction = Hashing.sha1()

    // higher than SCRAM-SHA512 (30)
    override fun getPriority(): Int = 35 + ChannelBinding.priority(channelBinding)

    override fun getMechanism(): String = MECHANISM

    companion object {
        const val MECHANISM: String = "SCRAM-SHA-1-PLUS"
    }
}
