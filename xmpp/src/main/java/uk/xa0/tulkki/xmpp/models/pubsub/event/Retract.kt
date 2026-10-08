package uk.xa0.tulkki.xmpp.models.pubsub.event

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe events: the `<retract/>` notification. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the (`retract`,
 * `http://jabber.org/protocol/pubsub#event`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PUBSUB_EVENT)
class Retract : Extension(Retract::class.java) {

    fun getId(): String? = getAttribute("id")
}
