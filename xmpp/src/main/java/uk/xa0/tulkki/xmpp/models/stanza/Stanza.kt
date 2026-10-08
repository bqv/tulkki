package uk.xa0.tulkki.xmpp.models.stanza

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.StreamElement
import uk.xa0.tulkki.xmpp.models.error.Error
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * A stanza: a stream element addressed by `to`/`from`. Converted from the Java.
 *
 * `getTo`/`getFrom` answer `Jid?` because `Element.getAttributeAsJid` does and the Java returned
 * that null; `setFrom`/`setTo`/`setId` take the nullable the Java passed straight to
 * `setAttribute`. `isInvalid` stays `open`, because `Iq` overrides it.
 */
abstract class Stanza protected constructor(clazz: Class<out Stanza>) : StreamElement(clazz) {

    open fun getTo(): Jid? = this.getAttributeAsJid("to")

    open fun getFrom(): Jid? = this.getAttributeAsJid("from")

    open fun getId(): String? = this.getAttribute("id")

    open fun setId(id: String?) {
        this.setAttribute("id", id)
    }

    open fun setFrom(from: Jid?) {
        this.setAttribute("from", from)
    }

    open fun setTo(to: Jid?) {
        this.setAttribute("to", to)
    }

    open fun getError(): Error? = this.getExtension(Error::class.java)

    open fun isInvalid(): Boolean {
        val to = getTo()
        val from = getFrom()
        if (to is Jid.Invalid || from is Jid.Invalid) {
            return true
        }
        return false
    }

    open fun fromServer(account: AccountRef): Boolean {
        val from = getFrom()
        return from == null ||
            from == account.getDomain() ||
            from == account.getJid().asBareJid() ||
            from == account.getJid()
    }

    open fun toServer(account: AccountRef): Boolean {
        val to = getTo()
        return to == null ||
            to == account.getDomain() ||
            to == account.getJid().asBareJid() ||
            to == account.getJid()
    }

    open fun fromAccount(account: AccountRef): Boolean {
        val from = getFrom()
        return from != null && from.asBareJid() == account.getJid().asBareJid()
    }
}
