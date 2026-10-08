package uk.xa0.tulkki.xmpp.models.pubsub.error

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe errors: the abstract base whose nested class is the one registry
 * entry. The package namespace moves onto the nested class, because Kotlin cannot annotate a
 * package; the (`precondition-not-met`, `http://jabber.org/protocol/pubsub#errors`) pair comes from the
 * `@XmlElement` annotation. The base's private constructor stays private, as in the Java.
 */
abstract class PubSubError private constructor(
    clazz: Class<out PubSubError>,
) : Extension(clazz) {

    @XmlElement(namespace = Namespace.PUBSUB_ERROR)
    class PreconditionNotMet : PubSubError(PreconditionNotMet::class.java)
}
