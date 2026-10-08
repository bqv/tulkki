package uk.xa0.tulkki.xmpp.models.bind

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 resource binding: the `<jid/>` the server answers a bind with. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the derived (`jid`,
 * `urn:ietf:params:xml:ns:xmpp-bind`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.BIND)
class Jid : Extension(Jid::class.java)
