package uk.xa0.tulkki.xmpp.models.vcard.update

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0153 vCard-based avatars: the `photo` element. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the name is derived, and the
 * (`photo`, `vcard-temp:x:update`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.VCARD_TEMP_UPDATE)
class Photo : Extension(Photo::class.java)
