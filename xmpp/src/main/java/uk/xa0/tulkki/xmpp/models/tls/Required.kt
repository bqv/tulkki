package uk.xa0.tulkki.xmpp.models.tls

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 STARTTLS: the `<required/>` a server advertises when it will not offer plaintext. The
 * package namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`required`, `urn:ietf:params:xml:ns:xmpp-tls`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.TLS)
class Required : Extension(Required::class.java)
