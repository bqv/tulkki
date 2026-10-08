package uk.xa0.tulkki.xmpp.models.muc

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0045 multi-user chat: the `<history/>` of a join. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`history`,
 * `http://jabber.org/protocol/muc`) pair comes from the `@XmlElement` annotation. The `Affiliation`
 * and `Role` enums are not registry entries and stay Java beside it.
 */
@XmlElement(namespace = Namespace.MUC)
class History : Extension(History::class.java) {

    fun setMaxChars(maxChars: Int) {
        setAttribute("maxchars", maxChars)
    }

    fun setMaxStanzas(maxStanzas: Int) {
        setAttribute("maxstanzas", maxStanzas)
    }
}
