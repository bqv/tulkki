package uk.xa0.tulkki.crypto.sasl

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import uk.xa0.tulkki.xmpp.refs.AccountRef

class ScramSha256(account: AccountRef) : ScramMechanism(account, ChannelBinding.NONE) {

    override fun getHMac(key: ByteArray): HashFunction =
        if (key.isEmpty()) Hashing.hmacSha256(ScramMechanism.EMPTY_KEY) else Hashing.hmacSha256(key)

    override fun getDigest(): HashFunction = Hashing.sha256()

    override fun getPriority(): Int = 25

    override fun getMechanism(): String = MECHANISM

    companion object {
        const val MECHANISM: String = "SCRAM-SHA-256"
    }
}
