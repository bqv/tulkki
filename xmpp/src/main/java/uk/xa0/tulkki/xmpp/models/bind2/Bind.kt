package uk.xa0.tulkki.xmpp.models.bind2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0386 bind 2.0: the `<bind/>` request. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the derived (`bind`, `urn:xmpp:bind:0`) pair comes from the
 * `@XmlElement` annotation. The Guava `Collections.emptyList()` fallback becomes `emptyList()`, with the
 * declared `Collection<Feature>` signature kept.
 */
@XmlElement(namespace = Namespace.BIND2)
class Bind : Extension(Bind::class.java) {

    fun getInline(): Inline? = getExtension(Inline::class.java)

    fun getInlineFeatures(): Collection<Feature> =
        getInline()?.getExtensions(Feature::class.java) ?: emptyList()

    fun setTag(tag: String) {
        addExtension(Tag(tag))
    }
}
