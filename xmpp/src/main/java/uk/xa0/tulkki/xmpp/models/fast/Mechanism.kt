package uk.xa0.tulkki.xmpp.models.fast

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0484 fast authentication: the `<mechanism/>` the server advertises. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the derived (`mechanism`,
 * `urn:xmpp:fast:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.FAST)
class Mechanism : Extension(Mechanism::class.java)
