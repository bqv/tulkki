package uk.xa0.tulkki.crypto.sasl

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import uk.xa0.tulkki.xmpp.refs.AccountRef

class ScramSha512(account: AccountRef) : ScramMechanism(account, ChannelBinding.NONE) {

    override fun getHMac(key: ByteArray): HashFunction =
        if (key.isEmpty()) Hashing.hmacSha512(ScramMechanism.EMPTY_KEY) else Hashing.hmacSha512(key)

    override fun getDigest(): HashFunction = Hashing.sha512()

    override fun getPriority(): Int = 30

    override fun getMechanism(): String = MECHANISM

    companion object {
        const val MECHANISM: String = "SCRAM-SHA-512"
    }
}
