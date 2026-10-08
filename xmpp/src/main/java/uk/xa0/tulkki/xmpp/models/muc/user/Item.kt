package uk.xa0.tulkki.xmpp.models.muc.user

import android.util.Log
import uk.xa0.tulkki.annotation.XmlElement
import java.util.Locale
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.muc.Affiliation
import uk.xa0.tulkki.xmpp.models.muc.Role

/**
 * XEP-0045 `muc#user`: the `item` element naming one occupant. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the name is derived, and the
 * (`item`, `http://jabber.org/protocol/muc#user`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.MUC_USER)
class Item : Extension(Item::class.java) {

    fun getAffiliation(): Affiliation {
        val affiliation = getAttribute("affiliation")
        if (affiliation.isNullOrEmpty()) {
            return Affiliation.NONE
        }
        return try {
            Affiliation.valueOf(affiliation.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            Log.d(Config.LOGTAG, "could not parse affiliation $affiliation")
            Affiliation.NONE
        }
    }

    fun getRole(): Role {
        val role = getAttribute("role")
        if (role.isNullOrEmpty()) {
            return Role.NONE
        }
        return try {
            Role.valueOf(role.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            Log.d(Config.LOGTAG, "could not parse role $role")
            Role.NONE
        }
    }

    fun getNick(): String? = getAttribute("nick")

    fun getJid(): Jid? = getAttributeAsJid("jid")
}
