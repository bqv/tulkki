package uk.xa0.tulkki.xmpp.models.bind2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0386 bind 2.0: the `<bound/>` the server answers a bind with. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the derived (`bound`,
 * `urn:xmpp:bind:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.BIND2)
class Bound : Extension(Bound::class.java)
