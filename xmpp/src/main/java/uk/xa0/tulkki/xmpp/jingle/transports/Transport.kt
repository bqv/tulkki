package uk.xa0.tulkki.xmpp.jingle.transports

import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group

/**
 * The transport seam of a Jingle connection (XEP-0166).
 *
 * Ported from Java by lane B. Decisions taken rather than inherited:
 *
 * 1. **`getInputStream`/`getOutputStream` keep `@Throws(IOException::class)`.** The callers are still
 *    Java and catch it; without the declaration `javac` rejects the catch as unreachable.
 * 2. **`onAdditionalCandidate`'s candidate is `Candidate?`.** Java passed
 *    `IceUdpTransportInfo.Candidate.fromSdpAttribute(...)`, which answers null, and the only
 *    implementor (`JingleFileTransferConnection.onAdditionalCandidate`) tests it with `instanceof`
 *    rather than dereferencing it. A non-null parameter would be a Kotlin compile error at that call.
 * 3. **`TransportInfo` is `open`** because `InitialTransportInfo` extends it; both stay nested in the
 *    interface, where Kotlin emits them as the same static member classes Java declared.
 * 4. **`transportInfo`, `group` and `contentName` are `@JvmField`.** `FileTransferContentMap:76-79`
 *    and `:112-113` read them as fields, so a Kotlin `val` with a generated getter would not satisfy
 *    those call sites. `group` is nullable because Java's field could hold one.
 * 5. **`readyToSentAdditionalCandidates` keeps its empty default body**, which Kotlin emits as a real
 *    interface `default` method, so a Java implementor that does not override it still links.
 */
interface Transport {

    @Throws(IOException::class)
    fun getOutputStream(): OutputStream

    @Throws(IOException::class)
    fun getInputStream(): InputStream

    fun asTransportInfo(): ListenableFuture<TransportInfo>

    fun asInitialTransportInfo(): ListenableFuture<InitialTransportInfo>

    fun readyToSentAdditionalCandidates() {}

    fun terminate()

    fun setTransportCallback(callback: Callback)

    fun connect()

    fun getTerminationLatch(): CountDownLatch

    interface Callback {
        fun onTransportEstablished()

        fun onTransportSetupFailed()

        fun onAdditionalCandidate(contentName: String, candidate: Candidate?)

        fun onCandidateUsed(streamId: String, candidate: SocksByteStreamsTransport.Candidate)

        fun onCandidateError(streamId: String)

        fun onProxyActivated(streamId: String, candidate: SocksByteStreamsTransport.Candidate)
    }

    enum class Direction {
        SEND,
        RECEIVE,
        SEND_RECEIVE,
    }

    class InitialTransportInfo(
        @JvmField val contentName: String,
        transportInfo: GenericTransportInfo,
        group: Group?,
    ) : TransportInfo(transportInfo, group)

    open class TransportInfo(
        @JvmField val transportInfo: GenericTransportInfo,
        @JvmField val group: Group?,
    ) {
        constructor(transportInfo: GenericTransportInfo) : this(transportInfo, null)
    }

    interface Candidate
}
