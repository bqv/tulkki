package uk.xa0.tulkki.xmpp.models.stanza

import uk.xa0.tulkki.annotation.XmlElement
import java.util.Locale
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.LocalizedContent
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.jabber.Body

/**
 * RFC 6120 message. Converted from the Java.
 *
 * `getType()` answers `Type?` because the Java's `catch (IllegalArgumentException)` returned null;
 * `getBody()` answers `LocalizedContent?` because
 * `Element.findInternationalizedChildContentInDefaultNamespace` does. `getBody()`/`setBody()` stay
 * methods rather than properties - the Java's readers already call them that way.
 */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Message() : Stanza(Message::class.java) {

    constructor(type: Type) : this() {
        this.setType(type)
    }

    fun getBody(): LocalizedContent? =
        findInternationalizedChildContentInDefaultNamespace("body")

    fun getType(): Type? {
        val value = this.getAttribute("type")
        return if (value == null) {
            Type.NORMAL
        } else {
            try {
                Type.valueOf(value.uppercase(Locale.ROOT))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    fun setType(type: Type?) {
        if (type == null || type == Type.NORMAL) {
            this.removeAttribute("type")
        } else {
            this.setAttribute("type", type.toString().lowercase(Locale.ROOT))
        }
    }

    fun setBody(text: String) {
        removeChild(findChild("body"))
        this.addExtension(Body(text))
    }

    fun setAxolotlMessage(axolotlMessage: Element) {
        removeChild(findChild("body"))
        prependChild(axolotlMessage)
    }

    enum class Type {
        ERROR,
        NORMAL,
        GROUPCHAT,
        HEADLINE,
        CHAT,
    }
}
