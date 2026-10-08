package uk.xa0.tulkki.xmpp.models.hints

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0334 message hint: the `store` hint. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the (`store`, `urn:xmpp:hints`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.HINTS)
class Store : Extension(Store::class.java)
