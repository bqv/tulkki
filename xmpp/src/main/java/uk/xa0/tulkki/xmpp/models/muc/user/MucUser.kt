package uk.xa0.tulkki.xmpp.models.muc.user

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0045 `muc#user`: the `x` element wrapping the occupant item and the status codes. The
 * package namespace moves onto the class, because Kotlin cannot annotate a package; the
 * (`x`, `http://jabber.org/protocol/muc#user`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "x", namespace = Namespace.MUC_USER)
class MucUser : Extension(MucUser::class.java) {

    fun getItem(): Item? = getExtension(Item::class.java)

    fun getStatus(): Collection<Int> = getExtensions(Status::class.java).mapNotNull { it.getCode() }

    companion object {

        const val STATUS_CODE_SELF_PRESENCE = 110
    }
}
