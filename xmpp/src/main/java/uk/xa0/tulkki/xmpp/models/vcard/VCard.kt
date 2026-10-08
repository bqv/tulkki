package uk.xa0.tulkki.xmpp.models.vcard

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0054 vCard-temp: the `vCard` element itself. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`vCard`, `vcard-temp`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "vCard", namespace = Namespace.VCARD_TEMP)
class VCard : Extension(VCard::class.java)
