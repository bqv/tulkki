package uk.xa0.tulkki.xmpp.models.avatar

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0084 user avatar: one size of the metadata. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.AVATAR_METADATA)
class Info : Extension(Info::class.java) {

    fun getHeight(): Long = getLongAttribute("height")

    fun getWidth(): Long = getLongAttribute("width")

    fun getBytes(): Long = getLongAttribute("bytes")

    fun getType(): String? = getAttribute("type")

    fun getUrl(): String? = getAttribute("url")

    fun getId(): String? = getAttribute("id")
}
