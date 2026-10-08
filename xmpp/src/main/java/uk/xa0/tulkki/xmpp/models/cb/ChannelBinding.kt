package uk.xa0.tulkki.xmpp.models.cb

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0440 SASL channel binding: one `<channel-binding/>`. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the derived (`channel-binding`,
 * `urn:xmpp:sasl-cb:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.CHANNEL_BINDING)
class ChannelBinding : Extension(ChannelBinding::class.java) {

    fun getType(): String? = getAttribute("type")
}
