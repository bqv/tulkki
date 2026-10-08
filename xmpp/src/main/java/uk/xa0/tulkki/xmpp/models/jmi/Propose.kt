package uk.xa0.tulkki.xmpp.models.jmi

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.jingle.stanzas.FileTransferDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription

/**
 * XEP-0353 Jingle Message Initiation: the `<propose/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`propose`, `urn:xmpp:jingle-message:0`)
 * pair comes from the `@XmlElement` annotation. The Java `ImmutableList.Builder` becomes a mutable
 * list of the same declared type.
 */
@XmlElement(namespace = Namespace.JINGLE_MESSAGE)
class Propose : JingleMessage(Propose::class.java) {

    fun getDescriptions(): List<GenericDescription> {
        val descriptions = mutableListOf<GenericDescription>()
        // TODO create proper extension for description
        for (child in getChildren()) {
            if ("description" == child.getName()) {
                val namespace = child.getNamespace() ?: throw NullPointerException()
                if (Namespace.JINGLE_APPS_FILE_TRANSFER.contains(namespace)) {
                    descriptions.add(FileTransferDescription.upgrade(child))
                } else if (Namespace.JINGLE_APPS_RTP == namespace) {
                    descriptions.add(RtpDescription.upgrade(child))
                } else {
                    descriptions.add(GenericDescription.upgrade(child))
                }
            }
        }
        return descriptions
    }
}
