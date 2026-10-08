package uk.xa0.tulkki.xmpp.models.csi

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * XEP-0352 client state indication: the `<active/>` stream element. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the derived (`active`,
 * `urn:xmpp:csi:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.CSI)
class Active : StreamElement(Active::class.java)
