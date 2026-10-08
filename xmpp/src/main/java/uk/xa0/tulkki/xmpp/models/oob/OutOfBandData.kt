package uk.xa0.tulkki.xmpp.models.oob

import com.google.common.base.Strings
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0066 out-of-band data. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`x`, `jabber:x:oob`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "x", namespace = Namespace.OOB)
class OutOfBandData : Extension(OutOfBandData::class.java) {

    fun getURL(): String? {
        val url = getExtension(URL::class.java) ?: return null
        return Strings.emptyToNull(url.getContent())
    }
}
