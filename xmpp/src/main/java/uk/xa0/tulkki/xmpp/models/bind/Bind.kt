package uk.xa0.tulkki.xmpp.models.bind

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid as XmppJid
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 resource binding: the `<bind/>` request. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`bind`,
 * `urn:ietf:params:xml:ns:xmpp-bind`) pair comes from the `@XmlElement` annotation.
 *
 * The return type is aliased to `XmppJid` because this package declares its own nested `Jid` child
 * element, which the unqualified name has to keep naming.
 */
@XmlElement(namespace = Namespace.BIND)
class Bind : Extension(Bind::class.java) {

    fun setResource(resource: String) {
        addExtension(Resource(resource))
    }

    fun getJid(): XmppJid? {
        val jidExtension = getExtension(Jid::class.java) ?: return null
        val content = jidExtension.getContent()
        if (content.isEmpty()) {
            return null
        }
        return try {
            XmppJid.of(content)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
