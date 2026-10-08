package uk.xa0.tulkki.xmpp.models.upload

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0363 HTTP file upload, the `<request/>` that asks for a slot. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the (`request`, `urn:xmpp:http:upload:0`)
 * pair comes from the `@XmlElement` annotation. `content-ype` is upstream's misspelling of the
 * attribute and is kept, because it is the wire name.
 */
@XmlElement(namespace = Namespace.HTTP_UPLOAD)
class Request : Extension(Request::class.java) {

    fun setFilename(filename: String) {
        setAttribute("filename", filename)
    }

    fun setSize(size: Long) {
        setAttribute("size", size)
    }

    fun setContentType(type: String) {
        setAttribute("content-ype", type)
    }
}
