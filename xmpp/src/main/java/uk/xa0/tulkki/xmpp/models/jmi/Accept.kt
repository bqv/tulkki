package uk.xa0.tulkki.xmpp.models.jmi

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace

/**
 * XEP-0353 Jingle Message Initiation: the `<accept/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`accept`, `urn:xmpp:jingle-message:0`)
 * pair comes from the `@XmlElement` annotation. `JingleMessage` is not a registry entry and stays
 * Java beside it.
 */
@XmlElement(namespace = Namespace.JINGLE_MESSAGE)
class Accept : JingleMessage(Accept::class.java)
