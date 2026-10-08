package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.AuthenticationRequest

/**
 * XEP-0388 SASL2: the `authenticate` request. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the (`authenticate`, `urn:xmpp:sasl:2`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.SASL_2)
class Authenticate : AuthenticationRequest(Authenticate::class.java) {

    override fun setMechanism(mechanism: SaslMechanism) {
        setAttribute("mechanism", mechanism.getMechanism())
    }
}
