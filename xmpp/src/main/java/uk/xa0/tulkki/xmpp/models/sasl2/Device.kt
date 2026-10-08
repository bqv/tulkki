package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0388 SASL2: the `device` element. The (`device`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class Device : Extension {

    constructor() : super(Device::class.java)

    constructor(device: String) : super(Device::class.java) {
        setContent(device)
    }
}
