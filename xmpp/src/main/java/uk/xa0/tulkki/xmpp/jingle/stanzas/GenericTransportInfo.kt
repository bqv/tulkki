package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import uk.xa0.tulkki.xml.Element

/**
 * The `transport` element of a Jingle transport the tree does not model (XEP-0166).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The constructor is `protected`**, exactly as Java's was; the companion can still reach it.
 * 2. **The class is `open`**: `IbbTransportInfo`, `SocksByteStreamsTransportInfo`,
 *    `IceUdpTransportInfo` and `WebRTCDataChannelTransportInfo` extend it.
 * 3. **`upgrade` is `@JvmStatic`**, as in `GenericDescription`.
 */
open class GenericTransportInfo protected constructor(name: String, xmlns: String?) :
    Element(name, xmlns) {

    companion object {
        @JvmStatic
        fun upgrade(element: Element): GenericTransportInfo {
            Preconditions.checkArgument("transport" == element.getName())
            val transport = GenericTransportInfo("transport", element.getNamespace())
            transport.bindTo(element)
            return transport
        }
    }
}
