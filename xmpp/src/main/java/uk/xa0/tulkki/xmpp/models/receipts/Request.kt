package uk.xa0.tulkki.xmpp.models.receipts

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.DeliveryReceiptRequest

/**
 * XEP-0184 message delivery receipts: the `<request/>` asking for an acknowledgement. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`request`, `urn:xmpp:receipts`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.DELIVERY_RECEIPTS)
class Request : DeliveryReceiptRequest(Request::class.java)
