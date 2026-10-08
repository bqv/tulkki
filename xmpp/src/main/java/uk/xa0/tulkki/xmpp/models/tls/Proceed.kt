package uk.xa0.tulkki.xmpp.models.tls

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * RFC 6120 STARTTLS: the `<proceed/>` the server answers a `<starttls/>` with. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`proceed`, `urn:ietf:params:xml:ns:xmpp-tls`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.TLS)
class Proceed : StreamElement(Proceed::class.java)
