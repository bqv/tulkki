package uk.xa0.tulkki.xmpp.models.forward

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.stanza.Message

/**
 * XEP-0297 stanza forwarding: the `<forwarded/>` wrapper. Its name and namespace are carried by the
 * class already, because there is no `package-info` to inherit them from; the (`forwarded`,
 * `urn:xmpp:forward:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.FORWARD)
class Forwarded : Extension(Forwarded::class.java) {

    fun getMessage(): Message? = getExtension(Message::class.java)
}
