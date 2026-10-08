package uk.xa0.tulkki.xmpp.models.capabilties

import com.google.common.base.Strings
import com.google.common.io.BaseEncoding
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.EntityCapabilities2
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.Hash

/**
 * XEP-0390 entity capabilities: the `<c/>` of the newer namespace. The namespace is named by the
 * class rather than a package, because Kotlin cannot annotate a package; the (`c`,
 * `urn:xmpp:caps`) pair comes from the `@XmlElement` annotation. The Java `Iterables.tryFind` becomes
 * `firstOrNull` with the same predicate and result.
 */
@XmlElement(name = "c", namespace = Namespace.ENTITY_CAPABILITIES_2)
class Capabilities : Extension(Capabilities::class.java) {

    fun getHash(): EntityCapabilities2.EntityCaps2Hash? {
        val sha256Hash = getExtensions(Hash::class.java)
            .firstOrNull { it.getAlgorithm() == Hash.Algorithm.SHA_256 } ?: return null
        val content = sha256Hash.getContent()
        if (Strings.isNullOrEmpty(content)) {
            return null
        }
        if (BaseEncoding.base64().canDecode(content)) {
            return EntityCapabilities2.EntityCaps2Hash.of(Hash.Algorithm.SHA_256, content)
        }
        return null
    }

    fun setHash(caps2Hash: EntityCapabilities2.EntityCaps2Hash) {
        val hash = Hash()
        hash.setAlgorithm(caps2Hash.algorithm)
        hash.setContent(caps2Hash.encoded())
        addExtension(hash)
    }
}
