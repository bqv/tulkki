package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.NodeConfiguration
import uk.xa0.tulkki.xmpp.models.data.Data

/**
 * XEP-0060 publish-subscribe: the `<publish-options/>` carrying a node configuration. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the (`publish-options`,
 * `http://jabber.org/protocol/pubsub`) pair comes from the `@XmlElement` annotation. `of` stays a
 * JVM static through the companion, because Java callers name it.
 */
@XmlElement(namespace = Namespace.PUBSUB)
class PublishOptions : Extension(PublishOptions::class.java) {

    companion object {

        @JvmStatic
        fun of(nodeConfiguration: NodeConfiguration): PublishOptions {
            val publishOptions = PublishOptions()
            publishOptions.addExtension(Data.of(Namespace.PUBSUB_PUBLISH_OPTIONS, nodeConfiguration))
            return publishOptions
        }
    }
}
