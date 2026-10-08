package uk.xa0.tulkki.xmpp.models.sasl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.AuthenticationStreamFeature

/** RFC 6120 SASL: the `mechanisms` stream feature. The (`mechanisms`, `urn:ietf:params:xml:ns:xmpp-sasl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL)
class Mechanisms : AuthenticationStreamFeature(Mechanisms::class.java) {

    fun getMechanisms(): Collection<Mechanism> = getExtensions(Mechanism::class.java)

    override fun getMechanismNames(): Collection<String> =
            getMechanisms().map { it.getContent() }
}
