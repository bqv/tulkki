package uk.xa0.tulkki.xmpp.models.pubsub.event

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe events: the `<purge/>` notification. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the (`purge`,
 * `http://jabber.org/protocol/pubsub#event`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PUBSUB_EVENT)
class Purge : Extension(Purge::class.java) {

    fun getNode(): String? = getAttribute("node")
}
