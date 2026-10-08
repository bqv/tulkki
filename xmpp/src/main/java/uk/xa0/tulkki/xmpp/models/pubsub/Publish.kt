package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe: the `<publish/>` in a publish request. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the (`publish`,
 * `http://jabber.org/protocol/pubsub`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PUBSUB)
class Publish : Extension(Publish::class.java) {

    fun setNode(node: String?) {
        setAttribute("node", node)
    }
}
