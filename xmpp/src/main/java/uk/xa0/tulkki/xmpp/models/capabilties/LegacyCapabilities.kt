package uk.xa0.tulkki.xmpp.models.capabilties

import com.google.common.io.BaseEncoding
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.EntityCapabilities
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0115 entity capabilities: the `<c/>` of the legacy namespace. The namespace is named by the
 * class rather than a package, because Kotlin cannot annotate a package; the (`c`,
 * `http://jabber.org/protocol/caps`) pair comes from the `@XmlElement` annotation. The private
 * `sha-1` constant becomes a companion `const val` so the static field survives on the class.
 */
@XmlElement(name = "c", namespace = Namespace.ENTITY_CAPABILITIES)
class LegacyCapabilities : Extension(LegacyCapabilities::class.java) {

    companion object {
        private const val HASH_ALGORITHM = "sha-1"
    }

    fun getNode(): String? = getAttribute("node")

    fun getHash(): EntityCapabilities.EntityCapsHash? {
        val hash = getAttribute("hash")
        val ver = getAttribute("ver")
        if (ver.isNullOrEmpty() || hash.isNullOrEmpty()) {
            return null
        }
        return if (HASH_ALGORITHM == hash && BaseEncoding.base64().canDecode(ver)) {
            EntityCapabilities.EntityCapsHash.of(ver)
        } else {
            null
        }
    }

    fun setNode(node: String?) {
        setAttribute("node", node)
    }

    fun setHash(hash: EntityCapabilities.EntityCapsHash) {
        setAttribute("hash", HASH_ALGORITHM)
        setAttribute("ver", hash.encoded())
    }
}
