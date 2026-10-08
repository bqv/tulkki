package uk.xa0.tulkki.xmpp.jingle

import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort

/**
 * The one OMEMO device id and session fingerprint a Jingle session is verified against, each
 * writable once.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **`getFingerprint` answers `String?`.** Java declared a bare `String` over a field that starts
 *    null and is read before it is set: `JingleRtpConnection.isVerified()` does
 *    `if (fingerprint == null) return false;`, and `hasFingerprint()` exists precisely to test it.
 *    The one Kotlin caller, `OmemoSessionPort.OmemoVerifiedPayload`, declares the result non-null,
 *    so its own line carries the throw it already had through the platform type.
 * 2. **`setSessionFingerprint` takes `String?`**, because `AxolotlService` hands it
 *    `XmppAxolotlSession.getFingerprint()`/`Plaintext.getFingerprint()`, both `String?`; Java's own
 *    body then did `Preconditions.checkNotNull(fingerprint, …)`, which is kept verbatim and still
 *    throws the same `NullPointerException`.
 * 3. **`setDeviceId` takes `Int?`** (`Integer` in Java) and the field stays nullable;
 *    `getDeviceId` returns `Int` through `Preconditions.checkNotNull`, which is Java's throw.
 * 4. **`setOrEnsureEqual(payload)` takes `OmemoVerifiedPayload<*>`** for Java's wildcard `<?>`, and
 *    the two-argument form stays a second method rather than a default argument.
 * 5. **`getFingerprint` is a method, not a Kotlin property**, so the JVM spelling matches
 *    `hasFingerprint`'s sibling and `toString`'s helper.
 */
class OmemoVerification {

    private val deviceIdWritten = AtomicBoolean(false)
    private val sessionFingerprintWritten = AtomicBoolean(false)
    private var deviceId: Int? = null
    private var sessionFingerprint: String? = null

    fun setDeviceId(id: Int?) {
        if (deviceIdWritten.compareAndSet(false, true)) {
            this.deviceId = id
            return
        }
        throw IllegalStateException("Device Id has already been set")
    }

    fun getDeviceId(): Int {
        Preconditions.checkNotNull(this.deviceId, "Device ID is null")
        return this.deviceId ?: throw NullPointerException()
    }

    fun hasDeviceId(): Boolean = this.deviceId != null

    fun setSessionFingerprint(fingerprint: String?) {
        Preconditions.checkNotNull(fingerprint, "Session fingerprint must not be null")
        if (sessionFingerprintWritten.compareAndSet(false, true)) {
            this.sessionFingerprint = fingerprint
            return
        }
        throw IllegalStateException("Session fingerprint has already been set")
    }

    fun getFingerprint(): String? = this.sessionFingerprint

    fun setOrEnsureEqual(omemoVerifiedPayload: OmemoSessionPort.OmemoVerifiedPayload<*>) {
        setOrEnsureEqual(omemoVerifiedPayload.deviceId, omemoVerifiedPayload.fingerprint)
    }

    fun setOrEnsureEqual(deviceId: Int, sessionFingerprint: String) {
        Preconditions.checkNotNull(sessionFingerprint, "Session fingerprint must not be null")
        if (this.deviceIdWritten.get() || this.sessionFingerprintWritten.get()) {
            val previousFingerprint = this.sessionFingerprint
            if (previousFingerprint == null) {
                throw IllegalStateException("No session fingerprint has been previously provided")
            }
            if (sessionFingerprint != previousFingerprint) {
                throw SecurityException("Session Fingerprints did not match")
            }
            val previousDeviceId = this.deviceId
            if (previousDeviceId == null) {
                throw IllegalStateException("No Device Id has been previously provided")
            }
            if (previousDeviceId != deviceId) {
                throw IllegalStateException("Device Ids did not match")
            }
        } else {
            this.setSessionFingerprint(sessionFingerprint)
            this.setDeviceId(deviceId)
        }
    }

    fun hasFingerprint(): Boolean = this.sessionFingerprint != null

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("deviceId", deviceId)
            .add("fingerprint", sessionFingerprint)
            .toString()
}
