package uk.xa0.tulkki.xmpp.models.markers

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.DeliveryReceiptRequest

/**
 * XEP-0333 chat markers: the `<markable/>` hint that a message wants a marker. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`markable`, `urn:xmpp:chat-markers:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.CHAT_MARKERS)
class Markable : DeliveryReceiptRequest(Markable::class.java)
