package uk.xa0.tulkki.xmpp.models.unique

import com.google.common.base.Strings
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0359 unique and stable stanza IDs: the `<origin-id/>`. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the derived (`origin-id`, `urn:xmpp:sid:0`)
 * pair comes from the `@XmlElement` annotation. The one-argument constructor stays a public secondary
 * constructor, because `MessageGenerator` names it.
 */
@XmlElement(namespace = Namespace.STANZA_IDS)
class OriginId() : Extension(OriginId::class.java) {

    constructor(id: String) : this() {
        setAttribute("id", id)
    }

    fun getId(): String? = Strings.emptyToNull(getAttribute("id"))
}
