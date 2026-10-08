package uk.xa0.tulkki.xmpp.models.error

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 stanza error conditions: the abstract base whose 22 nested classes are the registry
 * entries. Converted from the Java; the base's constructor stays `private` and the nested classes
 * still call it, which Kotlin allows for a nested subclass (measured with kotlinc 2.3), so no
 * visibility moves.
 *
 * The 22 nested `@XmlElement` classes stay nested and keep their names: `XmppConnection.java` spells
 * `Condition.Conflict`, `Condition.ResourceConstraint` and `Condition.NotAcceptable` in
 * `instanceof` tests. They also stop feeding the javac `annotationProcessor` now that the file is
 * Kotlin, so `Extensions.EXTENSION_CLASS_MAP` loses these entries; the hand-written
 * `ModelRegistry.kt` is deliberately not edited (it is being replaced by a generated index), and
 * that gap is reported for the coordinator to sequence.
 */
abstract class Condition private constructor(clazz: Class<out Condition>) : Extension(clazz) {

    @XmlElement(namespace = Namespace.STANZAS)
    class BadRequest : Condition(BadRequest::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class Conflict : Condition(Conflict::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class FeatureNotImplemented : Condition(FeatureNotImplemented::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class Forbidden : Condition(Forbidden::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class Gone : Condition(Gone::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class InternalServerError : Condition(InternalServerError::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class ItemNotFound : Condition(ItemNotFound::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class JidMalformed : Condition(JidMalformed::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class NotAcceptable : Condition(NotAcceptable::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class NotAllowed : Condition(NotAllowed::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class NotAuthorized : Condition(NotAuthorized::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class PaymentRequired : Condition(PaymentRequired::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class RecipientUnavailable : Condition(RecipientUnavailable::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class Redirect : Condition(Redirect::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class RegistrationRequired : Condition(RegistrationRequired::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class RemoteServerNotFound : Condition(RemoteServerNotFound::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class RemoteServerTimeout : Condition(RemoteServerTimeout::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class ResourceConstraint : Condition(ResourceConstraint::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class ServiceUnavailable : Condition(ServiceUnavailable::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class SubscriptionRequired : Condition(SubscriptionRequired::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class UndefinedCondition : Condition(UndefinedCondition::class.java)

    @XmlElement(namespace = Namespace.STANZAS)
    class UnexpectedRequest : Condition(UnexpectedRequest::class.java)
}
