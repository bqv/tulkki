package uk.xa0.tulkki.xmpp.models.pubsub.event

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.pubsub.Items

/**
 * XEP-0060 publish-subscribe, the event namespace: the `<event/>` wrapper, its `<items/>` list and
 * one `<item/>`. The package namespace moves onto each class, because Kotlin cannot annotate a
 * package; the (`item`, `items`, `event`, `http://jabber.org/protocol/pubsub#event`) pairs come from the
 * `@XmlElement` annotation. `Retract` resolves to this package's class, the pubsub
 * interface `Item` is spelled out in the supertype list because the nested `Item` shadows it.
 */
@XmlElement(namespace = Namespace.PUBSUB_EVENT)
class Event : Extension(Event::class.java) {

    fun getItems(): Items? = getExtension(ItemsWrapper::class.java)

    fun getPurge(): Purge? = getExtension(Purge::class.java)

    @XmlElement(name = "items", namespace = Namespace.PUBSUB_EVENT)
    class ItemsWrapper : Extension(ItemsWrapper::class.java), Items {

        override fun getNode(): String? = getAttribute("node")

        override fun getItems(): Collection<uk.xa0.tulkki.xmpp.models.pubsub.Item> =
            getExtensions(Item::class.java)

        override fun getRetractions(): Collection<Retract> = getExtensions(Retract::class.java)
    }

    @XmlElement(name = "item", namespace = Namespace.PUBSUB_EVENT)
    class Item : Extension(Item::class.java), uk.xa0.tulkki.xmpp.models.pubsub.Item {

        override fun getId(): String? = getAttribute("id")
    }
}
