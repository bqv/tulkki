package uk.xa0.tulkki.xmpp.models.correction

import com.google.common.base.Strings
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0308 last message correction: the `<replace/>`. The namespace is named by the class rather
 * than a package, because Kotlin cannot annotate a package; the derived (`replace`,
 * `urn:xmpp:message-correct:0`) pair comes from the `@XmlElement` annotation. The one-argument
 * constructor stays a public secondary constructor, because `MessageGenerator` names it.
 */
@XmlElement(namespace = Namespace.LAST_MESSAGE_CORRECTION)
class Replace() : Extension(Replace::class.java) {

    constructor(id: String) : this() {
        setId(id)
    }

    fun getId(): String? = Strings.emptyToNull(getAttribute("id"))

    fun setId(id: String) {
        setAttribute("id", id)
    }
}
