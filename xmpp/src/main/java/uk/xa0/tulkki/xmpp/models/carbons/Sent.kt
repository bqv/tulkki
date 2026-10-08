package uk.xa0.tulkki.xmpp.models.carbons

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.forward.Forwarded

/**
 * XEP-0280 message carbons: the `<sent/>` copy of the owner's own message. The package namespace
 * moves onto the class, because Kotlin cannot annotate a package; the derived (`sent`,
 * `urn:xmpp:carbons:2`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.CARBONS)
class Sent : Extension(Sent::class.java) {

    fun getForwarded(): Forwarded? = getExtension(Forwarded::class.java)
}
