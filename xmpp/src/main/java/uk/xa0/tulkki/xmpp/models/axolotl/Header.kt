package uk.xa0.tulkki.xmpp.models.axolotl

import com.google.common.base.Optional
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the message header. The (`header`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.AXOLOTL)
class Header : Extension(Header::class.java) {

    fun addIv(iv: ByteArray) {
        addExtension(IV()).setContent(iv)
    }

    fun setSourceDevice(sourceDeviceId: Long) {
        setAttribute("sid", sourceDeviceId)
    }

    fun getSourceDevice(): Optional<Int> = getOptionalIntAttribute("sid")

    fun getKeys(): Collection<Key> = getExtensions(Key::class.java)

    fun getKey(deviceId: Int): Key? =
            getKeys().firstOrNull { it.getRemoteDeviceId() == deviceId }

    fun getIv(): ByteArray {
        val iv = getExtension(IV::class.java)
        if (iv == null) {
            throw IllegalStateException("No IV in header")
        }
        return iv.asBytes()
    }
}
