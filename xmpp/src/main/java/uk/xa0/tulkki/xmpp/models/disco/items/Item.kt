package uk.xa0.tulkki.xmpp.models.disco.items

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0030 service discovery, the items namespace: one `<item/>`. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the derived (`item`,
 * `http://jabber.org/protocol/disco#items`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.DISCO_ITEMS)
class Item : Extension(Item::class.java) {

    fun getJid(): Jid? = getAttributeAsJid("jid")

    fun getNode(): String? = getAttribute("node")
}
