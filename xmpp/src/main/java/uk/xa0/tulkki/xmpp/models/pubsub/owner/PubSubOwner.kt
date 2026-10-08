package uk.xa0.tulkki.xmpp.models.pubsub.owner

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe, the owner namespace: the `<pubsub/>` wrapper of an owner request.
 * The package namespace moves onto the class, because Kotlin cannot annotate a package; the
 * (`pubsub`, `http://jabber.org/protocol/pubsub#owner`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "pubsub", namespace = Namespace.PUBSUB_OWNER)
class PubSubOwner : Extension(PubSubOwner::class.java)
