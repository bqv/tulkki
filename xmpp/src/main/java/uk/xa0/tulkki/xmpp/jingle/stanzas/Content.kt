package uk.xa0.tulkki.xmpp.jingle.stanzas

import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import java.util.Locale
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.jingle.SessionDescription
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid

/**
 * The Jingle `content` element (XEP-0166 §7.2).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`getTransport` hoists the null check.** Java computed
 *    `namespace = transport == null ? null : transport.getNamespace()` and then compared the
 *    namespace in every branch; Kotlin cannot smart-cast `transport` from a test on `namespace`, so
 *    the `transport == null` branch returns first and the rest of the chain is unchanged. The branch
 *    order, the namespaces and the answers are identical.
 * 2. **The public `(creator, senders, name)` and private `()` constructors stay two**; the enums
 *    stay nested, and their `of`/`receiveOnly` statics are `@JvmStatic` (`JingleRtpConnection`,
 *    `RtpContentMap`, `SessionDescription`, `AbstractContentMap`, `ContentAddition` call them).
 * 3. **`getContentName`/`getDescriptionNamespace` answer `String?`**, and `setSenders` takes
 *    `Senders?`, as Java's signatures allowed.
 * 4. `toString` on both enums is `name.lowercase(Locale.ROOT)`, which is Java's
 *    `super.toString().toLowerCase(Locale.ROOT)`.
 * 5. **`getSecurity` reads the content name through `?: throw NullPointerException()`.** Java's
 *    `contentName.equals(name)` dereferences the result of `getAttribute("name")`, which throws
 *    when the attribute is absent; a nullable Kotlin receiver would answer false instead, turning a
 *    throw into a null return (`JingleFileTransferConnection:338` is the one call site).
 */
class Content : Element {

    constructor(creator: Creator, senders: Senders, name: String) :
        super("content", Namespace.JINGLE) {
        this.setAttribute("creator", creator.toString())
        this.setAttribute("name", name)
        this.setSenders(senders)
    }

    private constructor() : super("content", Namespace.JINGLE)

    fun getContentName(): String? = this.getAttribute("name")

    fun getCreator(): Creator = Creator.of(getAttribute("creator") ?: throw NullPointerException())

    fun getSenders(): Senders {
        val attribute = getAttribute("senders")
        if (attribute.isNullOrEmpty()) {
            return Senders.BOTH
        }
        return Senders.of(attribute)
    }

    fun setSenders(senders: Senders?) {
        if (senders != null && senders != Senders.BOTH) {
            this.setAttribute("senders", senders.toString())
        }
    }

    fun getDescription(): GenericDescription? {
        val description = this.findChild("description")
        if (description == null) {
            return null
        }
        val namespace = description.getNamespace()
        return if (Namespace.JINGLE_APPS_FILE_TRANSFER == namespace) {
            FileTransferDescription.upgrade(description)
        } else if (Namespace.JINGLE_APPS_RTP == namespace) {
            RtpDescription.upgrade(description)
        } else {
            GenericDescription.upgrade(description)
        }
    }

    fun setDescription(description: GenericDescription) {
        Preconditions.checkNotNull(description)
        this.addChild(description)
    }

    fun getDescriptionNamespace(): String? {
        val description = this.findChild("description")
        return description?.getNamespace()
    }

    fun getTransport(): GenericTransportInfo? {
        val transport = this.findChild("transport")
        if (transport == null) {
            return null
        }
        val namespace = transport.getNamespace()
        return if (Namespace.JINGLE_TRANSPORTS_IBB == namespace) {
            IbbTransportInfo.upgrade(transport)
        } else if (Namespace.JINGLE_TRANSPORTS_S5B == namespace) {
            SocksByteStreamsTransportInfo.upgrade(transport)
        } else if (Namespace.JINGLE_TRANSPORT_ICE_UDP == namespace) {
            IceUdpTransportInfo.upgrade(transport)
        } else if (Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL == namespace) {
            WebRTCDataChannelTransportInfo.upgrade(transport)
        } else {
            GenericTransportInfo.upgrade(transport)
        }
    }

    fun setSecurity(xmppAxolotlMessage: OmemoWire) {
        val contentName = this.getContentName()
        val security = Element("security", Namespace.JINGLE_ENCRYPTED_TRANSPORT)
        security.setAttribute("name", contentName)
        security.setAttribute("cipher", "urn:xmpp:ciphers:aes-128-gcm-nopadding")
        security.setAttribute("type", OmemoSessionPort.PEP_PREFIX)
        security.addChild(xmppAxolotlMessage.toElement())
        this.addChild(security)
    }

    fun getSecurity(from: Jid, omemo: OmemoSessionPort): OmemoWire? {
        val contentName = this.getContentName() ?: throw NullPointerException()
        for (child in getChildren()) {
            if ("security" == child.getName()
                && Namespace.JINGLE_ENCRYPTED_TRANSPORT == child.getNamespace()
            ) {
                val name = child.getAttribute("name")
                val type = child.getAttribute("type")
                val cipher = child.getAttribute("cipher")
                if (contentName == name
                    && OmemoSessionPort.PEP_PREFIX == type
                    && "urn:xmpp:ciphers:aes-128-gcm-nopadding" == cipher
                ) {
                    val encrypted = child.findChild("encrypted", OmemoSessionPort.PEP_PREFIX)
                    if (encrypted != null) {
                        return omemo.parseWire(encrypted, from.asBareJid())
                    }
                }
            }
        }
        return null
    }

    fun setTransport(transportInfo: GenericTransportInfo) {
        this.addChild(transportInfo)
    }

    enum class Creator {
        INITIATOR,
        RESPONDER;

        override fun toString(): String = name.lowercase(Locale.ROOT)

        companion object {
            @JvmStatic
            fun of(value: String): Creator = Creator.valueOf(value.uppercase(Locale.ROOT))
        }
    }

    enum class Senders {
        BOTH,
        INITIATOR,
        NONE,
        RESPONDER;

        override fun toString(): String = name.lowercase(Locale.ROOT)

        fun asMediaAttribute(initiator: Boolean): String {
            val responder = !initiator
            return if (this == BOTH) {
                "sendrecv"
            } else if (this == NONE) {
                "inactive"
            } else if ((initiator && this == INITIATOR) || (responder && this == RESPONDER)) {
                "sendonly"
            } else if ((initiator && this == RESPONDER) || (responder && this == INITIATOR)) {
                "recvonly"
            } else {
                throw IllegalStateException(
                    String.format("illegal combination of initiator=%s and %s", initiator, this)
                )
            }
        }

        companion object {
            @JvmStatic
            fun of(value: String): Senders = Senders.valueOf(value.uppercase(Locale.ROOT))

            @JvmStatic
            fun of(media: SessionDescription.Media, initiator: Boolean): Senders {
                val attributes = media.attributes.keySet()
                if (attributes.contains("sendrecv")) {
                    return BOTH
                } else if (attributes.contains("inactive")) {
                    return NONE
                } else if (attributes.contains("sendonly")) {
                    return if (initiator) INITIATOR else RESPONDER
                } else if (attributes.contains("recvonly")) {
                    return if (initiator) RESPONDER else INITIATOR
                }
                Log.w(Config.LOGTAG, "assuming default value for senders")
                // If none of the attributes "sendonly", "recvonly", "inactive", and "sendrecv" is
                // present, "sendrecv" SHOULD be assumed as the default
                // https://www.rfc-editor.org/rfc/rfc4566
                return BOTH
            }

            @JvmStatic
            fun receiveOnly(initiator: Boolean): Set<Senders> =
                ImmutableSet.of(if (initiator) RESPONDER else INITIATOR)
        }
    }

    companion object {
        @JvmStatic
        fun upgrade(element: Element): Content {
            Preconditions.checkArgument("content" == element.getName())
            val content = Content()
            content.bindTo(element)
            return content
        }
    }
}
