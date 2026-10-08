package uk.xa0.tulkki.xmpp.models.data

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0004 data forms: one `<option/>`. The package namespace moves onto the class, because Kotlin
 * cannot annotate a package; the derived (`option`, `jabber:x:data`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.DATA)
class Option : Extension(Option::class.java)
