package uk.xa0.tulkki.xmpp.models.sasl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 SASL error conditions: the base whose eleven nested classes are the registry entries.
 * The namespace is named by each nested class rather than a package, because Kotlin cannot annotate
 * a package; the eleven (`aborted`…`temporary-auth-failure`, `urn:ietf:params:xml:ns:xmpp-sasl`)
 * pairs come from the `@XmlElement` annotation. The base's private constructor stays private, as in
 * the Java, and the class stays non-final because the nested classes inherit from it.
 */
open class SaslError private constructor(clazz: Class<out SaslError>) : Extension(clazz) {

    @XmlElement(namespace = Namespace.SASL)
    class Aborted : SaslError(Aborted::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class AccountDisabled : SaslError(AccountDisabled::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class CredentialsExpired : SaslError(CredentialsExpired::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class EncryptionRequired : SaslError(EncryptionRequired::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class IncorrectEncoding : SaslError(IncorrectEncoding::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class InvalidAuthzid : SaslError(InvalidAuthzid::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class InvalidMechanism : SaslError(InvalidMechanism::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class MalformedRequest : SaslError(MalformedRequest::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class MechanismTooWeak : SaslError(MechanismTooWeak::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class NotAuthorized : SaslError(NotAuthorized::class.java)

    @XmlElement(namespace = Namespace.SASL)
    class TemporaryAuthFailure : SaslError(TemporaryAuthFailure::class.java)
}
