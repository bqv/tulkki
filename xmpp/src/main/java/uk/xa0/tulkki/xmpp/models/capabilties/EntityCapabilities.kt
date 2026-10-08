package uk.xa0.tulkki.xmpp.models.capabilties

import uk.xa0.tulkki.xmpp.models.EntityCapabilities.Hash
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * The `caps` view a stanza or a stream feature offers: whichever `<c/>` it carries, legacy or
 * XEP-0390. Converted from the Java interface.
 *
 * `getCapabilities` keeps its Java `default` body; Kotlin emits it as an interface default method,
 * so a Java caller resolves it exactly as before. `NodeHash`'s constructor was `private` and the
 * interface's own default body called it, which Kotlin does not allow from the enclosing interface,
 * so a companion factory constructs it and the constructor stays `private`.
 */
interface EntityCapabilities {

    fun <E : Extension> getExtension(clazz: Class<E>): E?

    fun getCapabilities(): NodeHash? {
        val capabilities = this.getExtension(Capabilities::class.java)
        val legacyCapabilities = this.getExtension(LegacyCapabilities::class.java)
        val node: String?
        val hash: Hash?
        if (capabilities != null) {
            node = null
            hash = capabilities.getHash()
        } else if (legacyCapabilities != null) {
            node = legacyCapabilities.getNode()
            hash = legacyCapabilities.getHash()
        } else {
            return null
        }
        return if (hash == null) null else NodeHash.of(node, hash)
    }

    class NodeHash private constructor(
        @JvmField val node: String?,
        @JvmField val hash: Hash,
    ) {

        companion object {
            internal fun of(node: String?, hash: Hash): NodeHash = NodeHash(node, hash)
        }
    }
}
