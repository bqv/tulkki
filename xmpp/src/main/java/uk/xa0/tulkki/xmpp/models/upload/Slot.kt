package uk.xa0.tulkki.xmpp.models.upload

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0363 HTTP file upload, the `<slot/>` that carries the get and put halves. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the (`slot`,
 * `urn:xmpp:http:upload:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.HTTP_UPLOAD)
class Slot : Extension(Slot::class.java)
