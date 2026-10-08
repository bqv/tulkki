package uk.xa0.tulkki.xmpp.jingle

import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription

/**
 * An [RtpContentMap] every one of whose transports carries the OMEMO-verified DTLS fingerprint.
 *
 * Ported from Java by lane `C`. Decision taken rather than inherited: the constructor's loop becomes
 * an `init` block (Kotlin has no body of its own after `super`), and the `instanceof` test becomes a
 * smart-cast `is`, so no cast is written. The iteration and the `IllegalStateException` are Java's.
 */
class OmemoVerifiedRtpContentMap(
    group: Group?,
    contents: Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
) : RtpContentMap(group, contents) {

    init {
        for (descriptionTransport in contents.values) {
            val transport = descriptionTransport.transport
            if (transport is OmemoVerifiedIceUdpTransportInfo) {
                transport.ensureNoPlaintextFingerprint()
                continue
            }
            throw IllegalStateException(
                "OmemoVerifiedRtpContentMap contains non-verified transport info",
            )
        }
    }
}
