package uk.xa0.tulkki.xmpp.crypto

import org.whispersystems.libsignal.SignalProtocolAddress

/**
 * The failure family the XMPP island catches around OMEMO, in island vocabulary.
 *
 * Ported from Java by the port-14 `xmppport` lane, as the second annotation-free package after
 * `jingle/stanzas`. Decisions taken rather than inherited:
 *
 * 1. **The class and its three bases stay `open`.** Java's four are non-final, and `:crypto`'s
 *    `BrokenSessionException`, `NotEncryptedForThisDeviceException` and `OutdatedSenderException`
 *    extend them; a Kotlin class is final by default, which would have broken those subclasses.
 * 2. **Every constructor parameter stays nullable**, as Java's unannotated ones were: `Exception`
 *    takes `String?`, `Throwable?` and both, and a Java caller may pass null to any of the three.
 * 3. `signalProtocolAddress` is a property, so the Java getter keeps its exact spelling
 *    (`getSignalProtocolAddress`); Java's field was private.
 */
open class OmemoFailure : Exception {

    constructor(message: String?) : super(message)

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    /** The base `BrokenSessionException` extends. */
    open class BrokenSession(
        val signalProtocolAddress: SignalProtocolAddress?,
        cause: Throwable?,
    ) : OmemoFailure(cause)

    /** The base `NotEncryptedForThisDeviceException` extends. */
    open class NotEncryptedForThisDevice :
        OmemoFailure("Message was not encrypted for this device")

    /** The base `OutdatedSenderException` extends. */
    open class OutdatedSender(message: String?) : OmemoFailure(message)
}
