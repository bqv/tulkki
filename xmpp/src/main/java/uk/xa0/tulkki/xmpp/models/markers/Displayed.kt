package uk.xa0.tulkki.xmpp.models.markers

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0333 chat markers: the `<displayed/>` marker. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`displayed`,
 * `urn:xmpp:chat-markers:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.CHAT_MARKERS)
class Displayed : Extension(Displayed::class.java) {

    fun getId(): String? = getAttribute("id")
}
