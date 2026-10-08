package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0388 SASL2: the `user-agent` element. The (`user-agent`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(name = "user-agent", namespace = Namespace.SASL_2)
class UserAgent : Extension {

    constructor() : super(UserAgent::class.java)

    constructor(userAgentId: String) : super(UserAgent::class.java) {
        setAttribute("id", userAgentId)
    }

    fun setSoftware(software: String) {
        addExtension(Software(software))
    }

    fun setDevice(device: String) {
        addExtension(Device(device))
    }
}
