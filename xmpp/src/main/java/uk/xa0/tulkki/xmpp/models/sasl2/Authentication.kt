package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.AuthenticationStreamFeature

/** XEP-0388 SASL2: the `authentication` stream feature. The (`authentication`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class Authentication : AuthenticationStreamFeature(Authentication::class.java) {

    fun getMechanisms(): Collection<Mechanism> = getExtensions(Mechanism::class.java)

    override fun getMechanismNames(): Collection<String> =
            getMechanisms().map { it.getContent() }

    fun getInline(): Inline? = getExtension(Inline::class.java)
}
