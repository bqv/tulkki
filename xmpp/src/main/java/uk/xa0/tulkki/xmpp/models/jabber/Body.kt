package uk.xa0.tulkki.xmpp.models.jabber

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * `jabber:client` message body. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`body`, `jabber:client`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Body() : Extension(Body::class.java) {

    constructor(content: String) : this() {
        setContent(content)
    }

    fun getLang(): String? = getAttribute("xml:lang")
}
