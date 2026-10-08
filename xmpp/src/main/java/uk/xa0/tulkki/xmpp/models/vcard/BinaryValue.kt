package uk.xa0.tulkki.xmpp.models.vcard

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0054 vCard-temp: the `BINVAL` element holding one base64 value. The package namespace moves
 * onto the class, because Kotlin cannot annotate a package; the (`BINVAL`, `vcard-temp`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "BINVAL", namespace = Namespace.VCARD_TEMP)
class BinaryValue : Extension(BinaryValue::class.java), ByteContent
