package uk.xa0.tulkki.xmpp.jingle.stanzas

import uk.xa0.tulkki.xml.Namespace

/**
 * An ICE-UDP transport whose DTLS fingerprint arrived in the OMEMO verification namespace.
 *
 * Ported from Java by the port-14 `xmppport` lane. Decision taken rather than inherited:
 *
 * 1. **`upgrade` is `@JvmStatic`**: `RtpContentMap` and `OmemoVerifiedRtpContentMap` call it
 *    statically, and it keeps Java's return type `IceUdpTransportInfo` (it answers the plain
 *    transport unchanged when there is no OMEMO fingerprint to verify).
 */
class OmemoVerifiedIceUdpTransportInfo : IceUdpTransportInfo() {

    fun ensureNoPlaintextFingerprint() {
        if (this.findChild("fingerprint", Namespace.JINGLE_APPS_DTLS) != null) {
            throw IllegalStateException(
                "OmemoVerifiedIceUdpTransportInfo contains plaintext fingerprint"
            )
        }
    }

    companion object {
        @JvmStatic
        fun upgrade(transportInfo: IceUdpTransportInfo): IceUdpTransportInfo {
            if (transportInfo.hasChild("fingerprint", Namespace.JINGLE_APPS_DTLS)) {
                return transportInfo
            }
            if (transportInfo.hasChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)) {
                val omemoVerifiedIceUdpTransportInfo = OmemoVerifiedIceUdpTransportInfo()
                omemoVerifiedIceUdpTransportInfo.bindTo(transportInfo)
                return omemoVerifiedIceUdpTransportInfo
            }
            return transportInfo
        }
    }
}
