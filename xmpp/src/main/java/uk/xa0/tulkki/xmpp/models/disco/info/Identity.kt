package uk.xa0.tulkki.xmpp.models.disco.info

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0030 service discovery: one `<identity/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`identity`,
 * `http://jabber.org/protocol/disco#info`) pair comes from the `@XmlElement` annotation. Every
 * accessor reads an optional attribute, so each answers null when the peer omitted it.
 */
@XmlElement(namespace = Namespace.DISCO_INFO)
class Identity : Extension(Identity::class.java) {

    fun getCategory(): String? = getAttribute("category")

    fun getType(): String? = getAttribute("type")

    fun getLang(): String? = getAttribute("xml:lang")

    fun getIdentityName(): String? = getAttribute("name")

    fun setIdentityName(name: String) {
        setAttribute("name", name)
    }

    fun setType(type: String) {
        setAttribute("type", type)
    }

    fun setCategory(category: String) {
        setAttribute("category", category)
    }
}
