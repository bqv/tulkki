package uk.xa0.tulkki.xmpp.models.disco.items

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0030 service discovery, the items namespace: the `<query/>`. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the (`query`,
 * `http://jabber.org/protocol/disco#items`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "query", namespace = Namespace.DISCO_ITEMS)
class ItemsQuery : Extension(ItemsQuery::class.java) {

    fun setNode(node: String) {
        setAttribute("node", node)
    }

    fun getNode(): String? = getAttribute("node")
}
