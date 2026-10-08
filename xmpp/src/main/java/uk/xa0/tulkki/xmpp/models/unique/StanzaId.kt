package uk.xa0.tulkki.xmpp.models.unique

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0359 unique and stable stanza IDs: the `<stanza-id/>`. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the derived (`stanza-id`, `urn:xmpp:sid:0`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.STANZA_IDS)
class StanzaId : Extension(StanzaId::class.java) {

    fun getBy(): Jid? = getAttributeAsJid("by")

    fun getId(): String? = getAttribute("id")
}
