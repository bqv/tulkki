package uk.xa0.tulkki.xmpp.models.version

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0092 software version: the `<query/>`. The namespace is named by the class rather than a
 * package, because Kotlin cannot annotate a package; the (`query`, `jabber:iq:version`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "query", namespace = Namespace.VERSION)
class Version : Extension(Version::class.java) {

    fun setSoftwareName(name: String) {
        addChild("name").setContent(name)
    }

    fun setVersion(version: String) {
        addChild("version").setContent(version)
    }

    fun setOs(os: String) {
        addChild("os").setContent(os)
    }
}
