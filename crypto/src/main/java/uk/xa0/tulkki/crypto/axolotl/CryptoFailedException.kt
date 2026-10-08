package uk.xa0.tulkki.crypto.axolotl

import uk.xa0.tulkki.xmpp.crypto.OmemoFailure

/**
 * Pair 7: the root of the family is `OmemoFailure`, declared by the XMPP island, so the
 * island can ask "is this a crypto failure" without naming this class. The three specific
 * exceptions below extend their own island base instead of this one, which is what lets
 * `MessageParser` keep three distinct catch blocks.
 */
class CryptoFailedException : OmemoFailure {

    constructor(msg: String) : super(msg)

    constructor(msg: String, e: Exception) : super(msg, e)

    constructor(e: Exception) : super(e)
}
