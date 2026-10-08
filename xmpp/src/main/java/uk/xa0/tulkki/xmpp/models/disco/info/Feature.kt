package uk.xa0.tulkki.xmpp.models.disco.info

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0030 service discovery: one `<feature/>`. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the derived (`feature`,
 * `http://jabber.org/protocol/disco#info`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.DISCO_INFO)
class Feature : Extension(Feature::class.java) {

    fun getVar(): String? = getAttribute("var")

    fun setVar(feature: String) {
        setAttribute("var", feature)
    }
}
