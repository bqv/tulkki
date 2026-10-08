package uk.xa0.tulkki.xmpp.models.axolotl

import com.google.common.base.Optional
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: one per-device key. The (`key`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.AXOLOTL)
class Key : Extension(Key::class.java), ByteContent {

    fun setIsPreKey(isPreKey: Boolean) {
        setAttribute("prekey", isPreKey)
    }

    fun isPreKey(): Boolean = getAttributeAsBoolean("prekey")

    fun setRemoteDeviceId(remoteDeviceId: Int) {
        setAttribute("rid", remoteDeviceId)
    }

    fun getRemoteDeviceId(): Int? = getOptionalIntAttribute("rid").orNull()
}
