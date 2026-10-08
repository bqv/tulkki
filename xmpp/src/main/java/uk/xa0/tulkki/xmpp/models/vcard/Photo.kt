package uk.xa0.tulkki.xmpp.models.vcard

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0054 vCard-temp: the `PHOTO` element. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the (`PHOTO`, `vcard-temp`) pair comes from the `@XmlElement`
 * annotation.
 */
@XmlElement(name = "PHOTO", namespace = Namespace.VCARD_TEMP)
class Photo : Extension(Photo::class.java)
