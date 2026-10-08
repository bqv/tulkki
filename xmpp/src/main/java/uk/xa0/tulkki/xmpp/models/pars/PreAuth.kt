package uk.xa0.tulkki.xmpp.models.pars

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * Pre-authenticated roster subscription: the `<pre-auth/>`. The namespace is named by the class
 * rather than a package, because Kotlin cannot annotate a package; the (`pre-auth`,
 * `urn:xmpp:pars:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PARS)
class PreAuth : Extension(PreAuth::class.java) {

    fun setToken(token: String) {
        setAttribute("token", token)
    }
}
