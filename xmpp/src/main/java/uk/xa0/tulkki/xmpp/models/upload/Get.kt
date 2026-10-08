package uk.xa0.tulkki.xmpp.models.upload

import uk.xa0.tulkki.annotation.XmlElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0363 HTTP file upload, the `<get/>` half of a slot offer. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the (`get`, `urn:xmpp:http:upload:0`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.HTTP_UPLOAD)
class Get : Extension(Get::class.java) {

    fun getUrl(): HttpUrl? {
        val url = getAttribute("url")
        if (url.isNullOrEmpty()) {
            return null
        }
        return url.toHttpUrlOrNull()
    }
}
