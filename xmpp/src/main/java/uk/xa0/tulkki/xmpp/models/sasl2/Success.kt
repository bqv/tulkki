package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.StreamElement

/** XEP-0388 SASL2: the `success` element. The (`success`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class Success : StreamElement(Success::class.java) {

    fun getAuthorizationIdentifier(): Jid? {
        val id = getExtension(AuthorizationIdentifier::class.java)
        return id?.get()
    }
}
