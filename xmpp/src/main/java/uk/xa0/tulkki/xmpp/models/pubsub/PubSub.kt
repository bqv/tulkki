package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.pubsub.event.Retract as EventRetract

/**
 * XEP-0060 publish-subscribe: the `<pubsub/>` wrapper, its `<items/>` list and one `<item/>`.
 * The package namespace moves onto each class, because Kotlin cannot annotate a package; the three
 * (`item`/`items`/`pubsub`, `http://jabber.org/protocol/pubsub`) pairs come from the `@XmlElement`
 * annotation. The `Item` interface and the `Items` interface stay Java; the nested classes
 * are the registry entries.
 */
@XmlElement(name = "pubsub", namespace = Namespace.PUBSUB)
class PubSub : Extension(PubSub::class.java) {

    fun getItems(): Items? = getExtension(ItemsWrapper::class.java)

    @XmlElement(name = "items", namespace = Namespace.PUBSUB)
    class ItemsWrapper : Extension(ItemsWrapper::class.java), Items {

        override fun getNode(): String? = getAttribute("node")

        override fun getItems(): Collection<uk.xa0.tulkki.xmpp.models.pubsub.Item> =
            getExtensions(Item::class.java)

        override fun getRetractions(): Collection<EventRetract> =
            getExtensions(EventRetract::class.java)

        fun setNode(node: String?) {
            setAttribute("node", node)
        }

        fun setMaxItems(maxItems: Int) {
            setAttribute("max_items", maxItems)
        }
    }

    @XmlElement(name = "item", namespace = Namespace.PUBSUB)
    class Item : Extension(Item::class.java), uk.xa0.tulkki.xmpp.models.pubsub.Item {

        override fun getId(): String? = getAttribute("id")

        fun setId(itemId: String?) {
            setAttribute("id", itemId)
        }
    }
}
