package uk.xa0.tulkki.xmpp.models.blocking

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0191 blocking: one blocked JID. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.BLOCKING)
class Item : Extension(Item::class.java) {

    fun getJid(): Jid? = getAttributeAsJid("jid")
}
