package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the device list. The (`list`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "list", namespace = Namespace.AXOLOTL)
class DeviceList : Extension(DeviceList::class.java) {

    fun getDevices(): Collection<Device> = getExtensions(Device::class.java)

    fun getDeviceIds(): Set<Int> = getDevices().mapNotNull { it.getDeviceId() }.toSet()

    fun setDeviceIds(deviceIds: Collection<Int>) {
        for (deviceId in deviceIds) {
            addExtension(Device()).setDeviceId(deviceId)
        }
    }
}
