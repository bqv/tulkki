package uk.xa0.tulkki.xmpp.models.receipts

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.DeliveryReceipt

/**
 * XEP-0184 message delivery receipts: the `<received/>` acknowledgement. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the derived (`received`,
 * `urn:xmpp:receipts`) pair comes from the `@XmlElement` annotation. The abstract
 * `DeliveryReceipt.getId()` is read off `getAttribute`, so it answers null when absent.
 */
@XmlElement(namespace = Namespace.DELIVERY_RECEIPTS)
class Received : DeliveryReceipt(Received::class.java) {

    fun setId(id: String) {
        setAttribute("id", id)
    }

    override fun getId(): String? = getAttribute("id")
}
