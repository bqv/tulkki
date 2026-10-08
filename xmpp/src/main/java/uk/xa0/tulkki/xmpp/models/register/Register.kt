package uk.xa0.tulkki.xmpp.models.register

import uk.xa0.tulkki.annotation.XmlElement
import org.jxmpp.jid.parts.Localpart
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0077 in-band registration: the `<query/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`query`, `jabber:iq:register`) pair comes from the
 * `@XmlElement` annotation. `Password`, `Instructions` and `Remove` are not registry entries and
 * stay Java beside it.
 */
@XmlElement(name = "query", namespace = Namespace.REGISTER)
class Register : Extension(Register::class.java) {

    fun addUsername(username: Localpart) {
        addExtension(Username()).setContent(username.toString())
    }

    fun addPassword(password: String) {
        addExtension(Password()).setContent(password)
    }
}
