package uk.xa0.tulkki.xmpp.models.stanza

import com.google.common.base.Strings
import uk.xa0.tulkki.annotation.XmlElement
import java.util.Locale
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.error.Error

/**
 * RFC 6120 IQ. Converted from the Java.
 *
 * `TIMEOUT` stays a mutable static in the companion (`@JvmField var`), because the Java field was
 * `public static Iq` and `services/StanzaDispatch.kt` reads `Iq.TIMEOUT`. `getType()` keeps the
 * Java's throw for an absent or unknown `type` (`Type.valueOf` on the upper-cased empty string),
 * and `isInvalid` stays an `override` of [Stanza.isInvalid].
 */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Iq() : Stanza(Iq::class.java) {

    constructor(type: Type) : this() {
        this.setAttribute("type", type.toString().lowercase(Locale.ROOT))
    }

    // TODO get rid of timeout
    enum class Type {
        SET,
        GET,
        ERROR,
        RESULT,
        TIMEOUT,
    }

    fun getType(): Type =
        Type.valueOf(Strings.nullToEmpty(this.getAttribute("type")).uppercase(Locale.ROOT))

    override fun isInvalid(): Boolean {
        val id = getId()
        if (Strings.isNullOrEmpty(id)) {
            return true
        }
        return super.isInvalid()
    }

    // Legacy methods that need to be refactored:

    fun query(): Element {
        val query = findChild("query")
        if (query != null) {
            return query
        }
        return addChild("query")
    }

    fun query(xmlns: String): Element {
        val query = query()
        query.setAttribute("xmlns", xmlns)
        return query()
    }

    fun generateResponse(type: Type): Iq {
        val packet = Iq(type)
        packet.setTo(this.getFrom())
        packet.setId(this.getId())
        return packet
    }

    fun getErrorCondition(): String? {
        val error = getError()
        val condition = error?.getCondition()
        return condition?.getName()
    }

    companion object {

        @JvmField var TIMEOUT: Iq = Iq(Type.TIMEOUT)
    }
}
