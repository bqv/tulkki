package uk.xa0.tulkki.xmpp.jingle.stanzas

import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.jingle.transports.SocksByteStreamsTransport

/**
 * The SOCKS5 bytestream transport element (XEP-0260).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`cid` is `@JvmField` on both `CandidateUsed` and `Activated`.** Java reads it as a field
 *    (`JingleFileTransferConnection:729,734,737`: `candidateUsed.cid`, `activated.cid`), so a
 *    Kotlin `val` with a generated getter would not satisfy those call sites. The declared type is
 *    `String?` because Java's field could hold one.
 * 2. **The two constructors stay two**, as in `IbbTransportInfo`.
 * 3. **`upgrade` is `@JvmStatic`** (`FileTransferContentMap`, `JingleFileTransferConnection`,
 *    `SocksByteStreamsTransport`).
 * 4. **`getTransportInfo` answers `TransportInfo?`** (Java returned null on the unknown branch and on
 *    a `candidate-used`/`activated` child with no `cid`), and `getTransportId`/`getDestinationAddress`
 *    answer `String?` for the same reason.
 */
class SocksByteStreamsTransportInfo : GenericTransportInfo {

    private constructor() : super("transport", Namespace.JINGLE_TRANSPORTS_S5B)

    constructor(
        transportId: String,
        candidates: Collection<SocksByteStreamsTransport.Candidate>,
    ) : super("transport", Namespace.JINGLE_TRANSPORTS_S5B) {
        Preconditions.checkNotNull(transportId, "transport id must not be null")
        for (candidate in candidates) {
            this.addChild(candidate.asElement())
        }
        this.setAttribute("sid", transportId)
    }

    fun getTransportId(): String? = this.getAttribute("sid")

    fun getTransportInfo(): TransportInfo? {
        if (hasChild("proxy-error")) {
            return ProxyError()
        } else if (hasChild("candidate-error")) {
            return CandidateError()
        } else if (hasChild("candidate-used")) {
            val candidateUsed = findChild("candidate-used")
            val cid = candidateUsed?.getAttribute("cid")
            if (cid.isNullOrEmpty()) {
                return null
            } else {
                return CandidateUsed(cid)
            }
        } else if (hasChild("activated")) {
            val activated = findChild("activated")
            val cid = activated?.getAttribute("cid")
            if (cid.isNullOrEmpty()) {
                return null
            } else {
                return Activated(cid)
            }
        } else {
            return null
        }
    }

    fun getCandidates(): List<SocksByteStreamsTransport.Candidate> {
        val candidateBuilder = ImmutableList.builder<SocksByteStreamsTransport.Candidate>()
        for (child in getChildren()) {
            if ("candidate" == child.getName()
                && Namespace.JINGLE_TRANSPORTS_S5B == child.getNamespace()
            ) {
                try {
                    candidateBuilder.add(SocksByteStreamsTransport.Candidate.of(child))
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "skip over broken candidate", e)
                }
            }
        }
        return candidateBuilder.build()
    }

    fun getDestinationAddress(): String? = this.getAttribute("dstaddr")

    abstract class TransportInfo

    class CandidateUsed(@JvmField val cid: String?) : TransportInfo()

    class Activated(@JvmField val cid: String?) : TransportInfo()

    class CandidateError : TransportInfo()

    class ProxyError : TransportInfo()

    companion object {
        @JvmStatic
        fun upgrade(element: Element): SocksByteStreamsTransportInfo {
            Preconditions.checkArgument(
                "transport" == element.getName(),
                "Name of provided element is not transport",
            )
            Preconditions.checkArgument(
                Namespace.JINGLE_TRANSPORTS_S5B == element.getNamespace(),
                "Element does not match s5b transport namespace",
            )
            val transportInfo = SocksByteStreamsTransportInfo()
            transportInfo.bindTo(element)
            return transportInfo
        }
    }
}
