package uk.xa0.tulkki.xmpp.models.markers

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.DeliveryReceipt

/**
 * XEP-0333 chat markers: the `<received/>` marker. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`received`, `urn:xmpp:chat-markers:0`)
 * pair comes from the `@XmlElement` annotation. The abstract `DeliveryReceipt.getId()` is read off
 * `getAttribute`, so it answers null when the peer omitted the id.
 */
@XmlElement(namespace = Namespace.CHAT_MARKERS)
class Received : DeliveryReceipt(Received::class.java) {

    fun setId(id: String) {
        setAttribute("id", id)
    }

    override fun getId(): String? = getAttribute("id")
}
