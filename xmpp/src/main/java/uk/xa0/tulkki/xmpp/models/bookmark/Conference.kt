package uk.xa0.tulkki.xmpp.models.bookmark

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0402 bookmarks 2: one conference. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; registered in the compiled extension index.
 */
@XmlElement(namespace = Namespace.BOOKMARKS2)
class Conference : Extension(Conference::class.java) {

    fun isAutoJoin(): Boolean = getAttributeAsBoolean("autojoin")

    fun getConferenceName(): String? = getAttribute("name")

    fun setAutoJoin(autoJoin: Boolean) {
        setAttribute("autojoin", autoJoin)
    }

    fun getNick(): Nick? = getExtension(Nick::class.java)

    fun getExtensions(): Extensions? = getExtension(Extensions::class.java)
}
