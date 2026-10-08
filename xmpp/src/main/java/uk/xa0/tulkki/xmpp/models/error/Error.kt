package uk.xa0.tulkki.xmpp.models.error

import uk.xa0.tulkki.annotation.XmlElement
import java.util.Locale
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension as BaseExtension

/**
 * RFC 6120 stanza error. Converted from the Java.
 *
 * The nested class is itself called `Extension`, so the base type is imported under an alias; the
 * binary names (`Error$Extension`, `Error$Type`) are unchanged, which `JingleCondition` (Kotlin)
 * and the registry both spell. `getCondition`/`getText` answer null where the Java returned the
 * absent extension; `getTextAsString` keeps the same null.
 */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Error() : BaseExtension(Error::class.java) {

    fun getCondition(): Condition? = this.getExtension(Condition::class.java)

    fun setCondition(condition: Condition) {
        this.addExtension(condition)
    }

    fun getText(): Text? = this.getExtension(Text::class.java)

    fun getTextAsString(): String? = getText()?.getContent()

    fun setType(type: Type) {
        this.setAttribute("type", type.toString().lowercase(Locale.ROOT))
    }

    fun addExtensions(extensions: Array<Extension>) {
        for (extension in extensions) {
            this.addExtension(extension)
        }
    }

    enum class Type {
        MODIFY,
        CANCEL,
        AUTH,
        WAIT,
    }

    open class Extension(clazz: Class<out BaseExtension>) : BaseExtension(clazz)
}
