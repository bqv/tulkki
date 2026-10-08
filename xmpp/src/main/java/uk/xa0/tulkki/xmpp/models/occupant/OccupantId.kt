package uk.xa0.tulkki.xmpp.models.occupant

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0421 occupant id. The pair this class registers under comes from the `@XmlElement`
 * annotation, because the javac annotation processor that used to derive it from
 * [XmlElement] never sees Kotlin sources.
 */
@XmlElement(namespace = Namespace.OCCUPANT_ID)
class OccupantId : Extension(OccupantId::class.java) {

    fun getId(): String? = getAttribute("id")?.takeIf { it.isNotEmpty() }
}
