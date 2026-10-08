package uk.xa0.tulkki.xmpp.models.bind2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0386 bind 2.0: the `<tag/>` that labels the client in a bind 2.0 request. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived (`tag`,
 * `urn:xmpp:bind:0`) pair comes from the `@XmlElement` annotation. The one-argument constructor stays
 * a public secondary constructor, because `Bind.setTag` names it.
 */
@XmlElement(namespace = Namespace.BIND2)
class Tag() : Extension(Tag::class.java) {

    constructor(tag: String) : this() {
        setContent(tag)
    }
}
