package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: one device id. The (`device`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.AXOLOTL)
class Device : Extension(Device::class.java) {

    fun getDeviceId(): Int? = getAttribute("id").orEmpty().toIntOrNull()

    fun setDeviceId(deviceId: Int) {
        setAttribute("id", deviceId)
    }
}
