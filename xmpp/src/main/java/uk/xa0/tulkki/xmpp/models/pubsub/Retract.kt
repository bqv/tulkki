package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe: the `<retract/>` of a publish request. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the (`retract`,
 * `http://jabber.org/protocol/pubsub`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PUBSUB)
class Retract : Extension(Retract::class.java) {

    fun setNode(node: String?) {
        setAttribute("node", node)
    }

    fun setNotify(notify: Boolean) {
        setAttribute("notify", if (notify) 1 else 0)
    }
}
