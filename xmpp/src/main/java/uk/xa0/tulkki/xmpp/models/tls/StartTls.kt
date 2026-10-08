package uk.xa0.tulkki.xmpp.models.tls

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * RFC 6120 STARTTLS: the `<starttls/>` stream feature. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`starttls`, `urn:ietf:params:xml:ns:xmpp-tls`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "starttls", namespace = Namespace.TLS)
class StartTls : StreamElement(StartTls::class.java) {

    fun isRequired(): Boolean = hasExtension(Required::class.java)
}
