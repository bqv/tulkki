package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import com.google.common.collect.Iterables
import com.google.common.primitives.Ints
import java.util.Collections
import java.util.Hashtable
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.jingle.SessionDescription
import uk.xa0.tulkki.xmpp.jingle.transports.Transport

/**
 * The WebRTC data-channel transport element (`urn:xmpp:jingle:transports:dtls-sctp:1`).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`STUB` is `@JvmField` on the companion**, so `WebRTCDataChannelTransportInfo.STUB` stays a
 *    real static field rather than a `Companion.getSTUB()` call.
 * 2. **`cloneWrapper` reaches its non-null inner transport through `?: throw NullPointerException()`.**
 *    Java calls `iceUdpTransport.cloneWrapper()` and would throw a message-less
 *    `NullPointerException` when `innerIceUdpTransportInfo()` answered null; Kotlin's
 *    `innerIceUdpTransportInfo` is a declared `IceUdpTransportInfo?`, so the same failure is
 *    spelled out and the same exception type (with no message) is thrown. `!!` is not used.
 * 3. **`of` and `upgrade` are `@JvmStatic`** (`SessionDescription`, `JingleFileTransferConnection`,
 *    `WebRTCDataChannelTransport`), and `innerIceUdpTransportInfo`/`getCredentials` answer nullable
 *    as Java's did.
 * 4. The `addCandidate` comment is upstream's ICE-candidate fix (`ad97011b67`); its mutation of the
 *    live inner `<transport>` element and its `IllegalStateException` are unchanged.
 */
class WebRTCDataChannelTransportInfo :
    GenericTransportInfo("transport", Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL) {

    fun innerIceUdpTransportInfo(): IceUdpTransportInfo? {
        val iceUdpTransportInfo =
            this.findChild("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP)
        if (iceUdpTransportInfo != null) {
            return IceUdpTransportInfo.upgrade(iceUdpTransportInfo)
        }
        return null
    }

    fun getSctpPort(): Int? {
        val attribute = this.getAttribute("sctp-port")
        if (attribute == null) {
            return null
        }
        return Ints.tryParse(attribute)
    }

    fun getMaxMessageSize(): Int? {
        val attribute = this.getAttribute("max-message-size")
        if (attribute == null) {
            return null
        }
        return Ints.tryParse(attribute)
    }

    fun cloneWrapper(): WebRTCDataChannelTransportInfo {
        val iceUdpTransport = this.innerIceUdpTransportInfo() ?: throw NullPointerException()
        val transportInfo = WebRTCDataChannelTransportInfo()
        transportInfo.setAttributes(Hashtable(getAttributes()))
        transportInfo.addChild(iceUdpTransport.cloneWrapper())
        return transportInfo
    }

    fun addCandidate(candidate: IceUdpTransportInfo.Candidate) {
        // Tulkki: port-11, upstream `ad97011b67` - mutate the live inner <transport> element
        // directly. The candidate has to end up in the element that is actually serialized,
        // otherwise the peer receives ufrag/pwd but zero candidates and ICE stalls.
        // `innerIceUdpTransportInfo()` below cannot be used for this: it answers
        // `IceUdpTransportInfo.upgrade(...)`, a wrapper over a copy of the child.
        val iceUdpTransport =
            this.findChild("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP)
        if (iceUdpTransport == null) {
            throw IllegalStateException(
                "cannot add ICE candidate: inner ice-udp transport is missing"
            )
        }
        iceUdpTransport.addChild(candidate)
    }

    fun getCandidates(): List<IceUdpTransportInfo.Candidate> {
        val innerTransportInfo = this.innerIceUdpTransportInfo()
        if (innerTransportInfo == null) {
            return Collections.emptyList()
        }
        return innerTransportInfo.getCandidates()
    }

    fun getCredentials(): IceUdpTransportInfo.Credentials? {
        val innerTransportInfo = this.innerIceUdpTransportInfo()
        return innerTransportInfo?.getCredentials()
    }

    companion object {
        @JvmField
        val STUB = WebRTCDataChannelTransportInfo()

        @JvmStatic
        fun upgrade(element: Element): WebRTCDataChannelTransportInfo {
            Preconditions.checkArgument(
                "transport" == element.getName(),
                "Name of provided element is not transport",
            )
            Preconditions.checkArgument(
                Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL == element.getNamespace(),
                "Element does not match ice-udp transport namespace",
            )
            val transportInfo = WebRTCDataChannelTransportInfo()
            transportInfo.bindTo(element)
            return transportInfo
        }

        @JvmStatic
        fun of(sessionDescription: SessionDescription): Transport.InitialTransportInfo {
            val media = Iterables.getOnlyElement(sessionDescription.media)
            val id =
                Iterables.getFirst(media.attributes.get("mid"), null)
                    ?: throw NullPointerException("media has no mid")
            val maxMessageSize = Iterables.getFirst(media.attributes.get("max-message-size"), null)
            val maxMessageSizeInt =
                if (maxMessageSize == null) null else Ints.tryParse(maxMessageSize)
            val sctpPort = Iterables.getFirst(media.attributes.get("sctp-port"), null)
            val sctpPortInt = if (sctpPort == null) null else Ints.tryParse(sctpPort)
            val webRTCDataChannelTransportInfo = WebRTCDataChannelTransportInfo()
            if (maxMessageSizeInt != null) {
                webRTCDataChannelTransportInfo.setAttribute("max-message-size", maxMessageSizeInt)
            }
            if (sctpPortInt != null) {
                webRTCDataChannelTransportInfo.setAttribute("sctp-port", sctpPortInt)
            }
            webRTCDataChannelTransportInfo.addChild(
                IceUdpTransportInfo.of(sessionDescription, media)
            )

            val groupAttribute =
                Iterables.getFirst(sessionDescription.attributes.get("group"), null)
            val group = if (groupAttribute == null) null else Group.ofSdpString(groupAttribute)
            return Transport.InitialTransportInfo(id, webRTCDataChannelTransportInfo, group)
        }
    }
}
