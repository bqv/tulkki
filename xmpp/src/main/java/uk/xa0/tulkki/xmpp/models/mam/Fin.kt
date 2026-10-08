package uk.xa0.tulkki.xmpp.models.mam

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0313 message archive management, the `<fin/>` element that closes a result set. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the (`fin`,
 * `urn:xmpp:mam:2`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.MESSAGE_ARCHIVE_MANAGEMENT)
class Fin : Extension(Fin::class.java) {

    fun isComplete(): Boolean = getAttributeAsBoolean("complete")
}
