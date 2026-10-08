package uk.xa0.tulkki.xmpp.models.pubsub.owner

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.data.Data

/**
 * XEP-0060 publish-subscribe, the owner namespace: the `<configure/>` of a node. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the (`configure`,
 * `http://jabber.org/protocol/pubsub#owner`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PUBSUB_OWNER)
class Configure : Extension(Configure::class.java) {

    fun setNode(node: String?) {
        setAttribute("node", node)
    }

    fun getData(): Data? = getExtension(Data::class.java)
}
