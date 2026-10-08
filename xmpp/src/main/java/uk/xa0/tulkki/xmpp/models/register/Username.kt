package uk.xa0.tulkki.xmpp.models.register

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0077 in-band registration: the `<username/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`username`, `jabber:iq:register`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.REGISTER)
class Username : Extension(Username::class.java)
