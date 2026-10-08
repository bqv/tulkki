package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the `encrypted` envelope. The (`encrypted`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.AXOLOTL)
class Encrypted : Extension(Encrypted::class.java) {

    fun hasPayload(): Boolean = hasExtension(Payload::class.java)

    fun getHeader(): Header? = getExtension(Header::class.java)

    fun getPayload(): Payload? = getExtension(Payload::class.java)
}
