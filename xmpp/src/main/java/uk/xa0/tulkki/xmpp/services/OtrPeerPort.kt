package uk.xa0.tulkki.xmpp.services

import java.security.PublicKey

/**
 * Tulkki: the OTR engine's fingerprint helper, in island vocabulary. Converted from the Java.
 *
 * `publicKey` is **non-null** - the one caller, `MessageParser.kt:330`, passes
 * `otrSession.getRemotePublicKey()` after locating a live session - and so is the answer, which the
 * `OtrService` implementation inherits from `OtrCryptoEngineImpl.getFingerprint(PublicKey)`.
 *
 * The checked `throws` clause is re-declared with `@Throws`: Kotlin has no checked exceptions, and
 * without the declaration the Java-visible signature would lose the clause the Java interface
 * carried.
 */
interface OtrPeerPort {

    @Throws(net.java.otr4j.crypto.OtrCryptoException::class)
    fun getFingerprint(publicKey: PublicKey): String
}
