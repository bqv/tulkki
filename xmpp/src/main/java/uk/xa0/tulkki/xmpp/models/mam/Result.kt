package uk.xa0.tulkki.xmpp.models.mam

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.forward.Forwarded

/**
 * XEP-0313 message archive management, one `<result/>` of an archive page. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the (`result`, `urn:xmpp:mam:2`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.MESSAGE_ARCHIVE_MANAGEMENT)
class Result : Extension(Result::class.java) {

    fun getForwarded(): Forwarded? = getExtension(Forwarded::class.java)

    fun getId(): String? = getAttribute("id")

    fun getQueryId(): String? = getAttribute("queryid")
}
