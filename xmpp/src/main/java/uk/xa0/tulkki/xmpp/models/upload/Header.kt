package uk.xa0.tulkki.xmpp.models.upload

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0363 HTTP file upload, one `<header/>` a slot's PUT should carry. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the (`header`,
 * `urn:xmpp:http:upload:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.HTTP_UPLOAD)
class Header : Extension(Header::class.java) {

    fun getHeaderName(): String? = getAttribute("name")
}
