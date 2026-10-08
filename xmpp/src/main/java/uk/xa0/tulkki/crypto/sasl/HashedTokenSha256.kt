package uk.xa0.tulkki.crypto.sasl

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import uk.xa0.tulkki.xmpp.refs.AccountRef

class HashedTokenSha256(account: AccountRef, channelBinding: ChannelBinding) :
    HashedToken(account, channelBinding) {

    override fun getHashFunction(key: ByteArray): HashFunction = Hashing.hmacSha256(key)

    override fun getTokenMechanism(): HashedToken.Mechanism =
        HashedToken.Mechanism("SHA-256", channelBinding)
}
