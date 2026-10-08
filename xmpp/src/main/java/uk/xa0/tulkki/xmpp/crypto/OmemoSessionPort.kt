package uk.xa0.tulkki.xmpp.crypto

import com.google.common.util.concurrent.ListenableFuture
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnAdvancedStreamFeaturesLoaded
import uk.xa0.tulkki.xmpp.jingle.OmemoVerification
import uk.xa0.tulkki.xmpp.jingle.RtpContentMap

/**
 * What the XMPP island needs from the OMEMO engine, in island-owned vocabulary.
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The constants live on the companion as `const val`.** Java's interface fields are inherited
 *    by implementing classes, and `:crypto`'s callers spell them `AxolotlService.PEP_PREFIX` and
 *    `AxolotlService.FetchStatus`; a Kotlin interface companion's `const val` still compiles to a
 *    `public static final` field on the interface, which a Java implementer inherits — proven with a
 *    throwaway Java implementer before this file was written, not assumed. A **Kotlin** implementer
 *    inherits nothing from it, though, and must spell `OmemoSessionPort.PEP_PREFIX`: the direct
 *    `import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.PEP_PREFIX` does not resolve either.
 * 2. **The checked throws clauses are re-declared with `@Throws`.** Kotlin has no checked
 *    exceptions, so without it the Java implementer in `:crypto` could not declare
 *    `throws BrokenSession, …` and the island's callers could not catch them. The three types are
 *    `OmemoFailure`'s subclasses; `wireSourceId`'s `IllegalArgumentException` is re-declared too so
 *    the bytecode signature is the Java one.
 * 3. **`Object` parameters are `Any?`.** The island may not name `:data`'s model types, and Java's
 *    unannotated `Object` accepted null.
 * 4. **`getIV` on `KeyTransport` keeps Java's spelling** (`getIv` there, `getIV` on `OmemoWire`):
 *    Kotlin properties would have normalised both to one case and broken a caller.
 * 5. **Every payload member but `Plaintext.getPlaintext` is nullable**, because its implementation
 *    can be null and Java's platform types hid that: a fingerprint comes from
 *    `XmppAxolotlSession.getFingerprint()`, which answers null without an identity key; a
 *    `KeyTransport`'s key is `unpackKey`'s answer, which is null when no key decrypts; and its iv
 *    is `OmemoWire.getIV()`. `getPlaintext` stays non-null because it provably cannot be null -
 *    its only constructor argument is the `String` `XmppAxolotlMessage.decrypt` just built.
 * 6. **The two received-payload members and the two wire-returning members answer null, so they are
 *    declared nullable**, from the implementation rather than the interface's own text:
 *    `AxolotlService.processReceivingPayloadMessage` starts at null and a caught
 *    `CryptoFailedException` leaves it there, `processReceivingKeyTransportMessage` returns null on
 *    `OmemoFailure`, `fetchAxolotlMessageFromCache` is a cache lookup that misses, and `encrypt`
 *    answers null when no header can be built. Their Java callers already check for null
 *    (`XmppConnectionService` for the two wire ones, `MessageParser` for the payload), so the only
 *    reading of the port that was ever true at the call sites is the nullable one. `encrypt`'s
 *    `content` is nullable for the same evidence: `AxolotlService.encrypt(OmemoMessage)` builds it
 *    from `getRawBody()`, a nullable string. `registerDevices` takes a `MutableSet` because its
 *    implementation removes from the set it is handed - Java's `Set` parameter was mutated all
 *    along, and only the Kotlin spelling of that contract is different.
 */
interface OmemoSessionPort : OnAdvancedStreamFeaturesLoaded {

    companion object {
        const val PEP_PREFIX = "eu.siacs.conversations.axolotl"
        const val PEP_DEVICE_LIST = PEP_PREFIX + ".devicelist"
        const val PEP_DEVICE_LIST_NOTIFY = PEP_DEVICE_LIST + "+notify"
        const val PEP_BUNDLES = PEP_PREFIX + ".bundles"
        const val PEP_VERIFICATION = PEP_PREFIX + ".verification"
        const val PEP_OMEMO_WHITELISTED = PEP_PREFIX + ".whitelisted"
        const val LOGPREFIX = "AxolotlService"
    }

    /** The `LOGPREFIX` plus this account's bare JID, the header of every OMEMO log line. */
    fun logPrefix(): String

    /** The wire half of a received `<encrypted>` element. */
    fun parseWire(element: Element, from: Jid): OmemoWire

    /** The device id in an `<encrypted>` element's header, without building the message. */
    @Throws(IllegalArgumentException::class)
    fun wireSourceId(element: Element): Int

    /**
     * Answers null when the message could not be decrypted for this device, the failure swallowed
     * by the implementation; `MessageParser` already checks. See the class doc, decision 6.
     */
    @Throws(
        OmemoFailure.BrokenSession::class,
        OmemoFailure.NotEncryptedForThisDevice::class,
        OmemoFailure.OutdatedSender::class,
    )
    fun processReceivingPayloadMessage(message: OmemoWire, postpone: Boolean): Plaintext?

    /** Answers null when the implementation's `getParameters` throws `OmemoFailure`. */
    fun processReceivingKeyTransportMessage(message: OmemoWire, postpone: Boolean): KeyTransport?

    fun trustedOrPreviouslyResponded(jid: Jid): Boolean

    fun reportBrokenSessionException(e: OmemoFailure.BrokenSession, postpone: Boolean)

    /** `deviceIds` is mutated in place by `AxolotlService`; Java's plain `Set` hid that. */
    fun registerDevices(jid: Jid, deviceIds: MutableSet<Int>)

    fun hasEmptyDeviceList(jid: Jid): Boolean

    fun fetchDeviceIds(jid: Jid)

    fun findCounterpartsBySourceId(sid: Int): Set<Jid>

    fun prepareKeyTransportMessage(conversation: Any?): ListenableFuture<OmemoWire>

    fun getOwnFingerprint(): String

    fun getOwnDeviceId(): Int

    fun isPepBroken(): Boolean

    fun resetBrokenness()

    fun clearErrorsInFetchStatusMap(jid: Jid)

    fun regenerateKeys(wipeOther: Boolean)

    fun deleteOmemoIdentity()

    /**
     * Tulkki: 3.7 C5-D. `MessageArchiveService.processPostponed` used to reach this through
     * `Account.getAxolotlService()`, whose return type is `:crypto`'s class - a type `:xmpp` may not
     * name. `Account` already answers `getOmemoSession()` with this port for exactly that reason, and
     * `AxolotlService` already declares the method, so the island asks the same question through the
     * door that exists rather than a second one being built for it.
     */
    fun processPostponed()

    /** Null when no header can be built, or when the content is null as `getRawBody()` may be. */
    fun encrypt(content: String?, conversation: Any?): OmemoWire?

    /** Null on a cache miss; the caller then prepares a fresh payload. */
    fun fetchAxolotlMessageFromCache(message: Any?): OmemoWire?

    fun preparePayloadMessage(message: Any?, delay: Boolean)

    fun encryptVerified(
        contentMap: RtpContentMap,
        jid: Jid,
        deviceId: Int,
    ): ListenableFuture<OmemoVerifiedPayload<RtpContentMap>>

    fun decryptVerified(
        verifiedContentMap: Any?,
        from: Jid,
    ): ListenableFuture<OmemoVerifiedPayload<RtpContentMap>>

    fun hasVerifiedKeys(name: String): Boolean

    fun hasFingerprintTrust(fingerprint: String): Boolean

    fun isFingerprintVerified(fingerprint: String): Boolean

    /** Marks a known fingerprint verified, the `toVerified()` transition. */
    fun markFingerprintVerified(fingerprint: String)

    fun preVerifyContactFingerprint(contact: Any?, fingerprint: String)

    fun preVerifyAccountFingerprint(account: Any?, fingerprint: String)

    /** What `AxolotlService` reports after a device-list fetch. */
    enum class FetchStatus {
        PENDING,
        SUCCESS,
        SUCCESS_VERIFIED,
        TIMEOUT,
        SUCCESS_TRUSTED,
        ERROR
    }

    /** A verified payload and the session it was verified with. */
    class OmemoVerifiedPayload<T>(omemoVerification: OmemoVerification, val payload: T) {

        val deviceId: Int = omemoVerification.getDeviceId()

        // `OmemoVerification.getFingerprint()` is Kotlin now and answers `String?` (its Java was
        // platform-typed; `JingleRtpConnection.isVerified()` still relies on the null). This
        // property is non-null by contract, so the check Kotlin used to insert for the platform
        // type is spelled out here, at the same place and with the same failure.
        val fingerprint: String =
            omemoVerification.getFingerprint() ?: throw NullPointerException()
    }

    /** A decrypted payload and the fingerprint it came from. */
    interface Plaintext {

        fun getPlaintext(): String

        fun getFingerprint(): String?
    }

    /** A decrypted key transport payload. */
    interface KeyTransport {

        fun getFingerprint(): String?

        fun getKey(): ByteArray?

        fun getIv(): ByteArray?
    }
}
