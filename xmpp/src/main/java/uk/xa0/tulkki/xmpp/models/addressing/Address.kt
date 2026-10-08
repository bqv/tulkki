package uk.xa0.tulkki.xmpp.models.addressing

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0033 extended stanza addressing. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the javac annotation processor never sees this source, so
 * the (name, namespace) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.ADDRESSING)
class Address : Extension(Address::class.java)
