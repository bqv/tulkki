package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The Jingle message `proceed` element (XEP-0353).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decision taken rather than inherited:
 *
 * 1. **The private constructor still names the element `propose`.** Java's line 12 does; `upgrade`
 *    then `bindTo`s the real element over it, and `bindTo` carries the name and namespace across, so
 *    the string is not observable. It is kept rather than tidied because this port changes no
 *    behaviour.
 * 2. **`upgrade` is `@JvmStatic`**; `getDeviceId` answers `Int?` because Java's `Ints.tryParse` can
 *    answer null.
 */
class Proceed private constructor() : Element("propose", Namespace.JINGLE_MESSAGE) {

    fun getDeviceId(): Int? {
        val device = this.findChild("device")
        val id = device?.getAttribute("id")
        if (id == null) {
            return null
        }
        return Ints.tryParse(id)
    }

    companion object {
        @JvmStatic
        fun upgrade(element: Element): Proceed {
            Preconditions.checkArgument("proceed" == element.getName())
            Preconditions.checkArgument(Namespace.JINGLE_MESSAGE == element.getNamespace())
            val proceed = Proceed()
            proceed.bindTo(element)
            return proceed
        }
    }
}
