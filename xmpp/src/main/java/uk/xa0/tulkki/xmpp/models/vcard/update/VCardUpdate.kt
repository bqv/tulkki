package uk.xa0.tulkki.xmpp.models.vcard.update

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0153 vCard-based avatars: the `x` element. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`x`, `vcard-temp:x:update`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "x", namespace = Namespace.VCARD_TEMP_UPDATE)
class VCardUpdate : Extension(VCardUpdate::class.java) {

    fun getPhoto(): Photo? = getExtension(Photo::class.java)

    fun getHash(): String? = getPhoto()?.getContent()
}
