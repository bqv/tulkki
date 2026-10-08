package uk.xa0.tulkki.xmpp.models.roster

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** RFC 6121 roster query. Registered in the compiled extension index. */
@XmlElement(name = "query", namespace = Namespace.ROSTER)
class Query : Extension(Query::class.java) {

    fun setVersion(rosterVersion: String) {
        setAttribute("ver", rosterVersion)
    }

    fun getVersion(): String? = getAttribute("ver")
}
