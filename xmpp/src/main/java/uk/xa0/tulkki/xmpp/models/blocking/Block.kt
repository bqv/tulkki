package uk.xa0.tulkki.xmpp.models.blocking

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0191 blocking command. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`block`, `urn:xmpp:blocking`) pair comes from the `@XmlElement`
 * annotation.
 */
@XmlElement(namespace = Namespace.BLOCKING)
class Block : Extension(Block::class.java)
