package uk.xa0.tulkki.app

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.WebRTCDataChannelTransportInfo

/**
 * Tulkki: an ICE candidate added to a data-channel transport has to land in the element that is
 * actually serialized - the port of upstream's `ad97011b67` fix, and a call that cannot connect
 * without it.
 *
 * `WebRTCDataChannelTransportInfo.innerIceUdpTransportInfo()` returns
 * `IceUdpTransportInfo.upgrade(child)`, i.e. a **wrapper built over a clone** of the inner
 * `<transport/>`, so `addCandidate`'s old body added the candidate to a throwaway object: the peer
 * received ufrag/pwd and **zero candidates**, and ICE stalled. The fixed body finds the live child
 * on this element and adds the candidate there.
 *
 * The cell reads the fact back twice, because that is the whole defect: once through
 * `getCandidates()` - what this side hands to the ICE stack - and once on the wire element the
 * transport was built from, which is what a peer would parse.
 */
class WebRtcIceCandidateTest {

    @Test
    fun aCandidateAddedToTheTransportIsInTheSerializedElement() {
        val outer =
            Element("transport", Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL)
        outer.addChild(Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP))
        val transportInfo = WebRTCDataChannelTransportInfo.upgrade(outer)

        val candidateElement = Element("candidate")
        candidateElement.setAttribute("foundation", "1")
        candidateElement.setAttribute("component", "1")
        candidateElement.setAttribute("protocol", "udp")
        candidateElement.setAttribute("priority", "1")

        transportInfo.addCandidate(IceUdpTransportInfo.Candidate.upgrade(candidateElement))

        assertEquals(
            "the candidate must be readable back off this transport",
            1,
            transportInfo.getCandidates().size)
        val liveInner =
            outer.findChild("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP)
        assertEquals(
            "the candidate must be in the element the wire would carry",
            1,
            liveInner!!.getChildren().size)
    }
}
