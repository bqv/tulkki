package uk.xa0.tulkki.xmpp.models.sasl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.AuthenticationRequest

/**
 * RFC 6120 SASL: the `auth` request. The package namespace moves onto the class, because Kotlin
 * cannot annotate a package; the (`auth`, `urn:ietf:params:xml:ns:xmpp-sasl`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.SASL)
class Auth : AuthenticationRequest(Auth::class.java) {

    override fun setMechanism(mechanism: SaslMechanism) {
        setAttribute("mechanism", mechanism.getMechanism())
    }
}
