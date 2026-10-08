package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0388 SASL2: the `authorization-identifier`. The (`authorization-identifier`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class AuthorizationIdentifier : Extension(AuthorizationIdentifier::class.java) {

    fun get(): Jid? {
        val content = getContent()
        if (content.isNullOrEmpty()) {
            return null
        }
        return try {
            Jid.of(content)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
