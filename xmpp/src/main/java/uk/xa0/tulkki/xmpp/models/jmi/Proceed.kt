package uk.xa0.tulkki.xmpp.models.jmi

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xml.Element

/**
 * XEP-0353 Jingle Message Initiation: the `<proceed/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`proceed`, `urn:xmpp:jingle-message:0`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.JINGLE_MESSAGE)
class Proceed : JingleMessage(Proceed::class.java) {

    fun getDeviceId(): Int? {
        // TODO use proper namespace and create extension
        val device: Element = findChild("device") ?: return null
        return device.getAttribute("id")?.toIntOrNull()
    }
}
