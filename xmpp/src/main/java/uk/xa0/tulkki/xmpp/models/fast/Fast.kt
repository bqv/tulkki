package uk.xa0.tulkki.xmpp.models.fast

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0484 fast authentication: the `<fast/>` stream feature. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the derived (`fast`, `urn:xmpp:fast:0`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.FAST)
class Fast : Extension(Fast::class.java)
