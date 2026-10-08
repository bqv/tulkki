package uk.xa0.tulkki.xmpp.models.oob

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0066 out-of-band URL. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`url`, `jabber:x:oob`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "url", namespace = Namespace.OOB)
class URL : Extension(URL::class.java)
