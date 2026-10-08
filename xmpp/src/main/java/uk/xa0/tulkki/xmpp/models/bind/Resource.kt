package uk.xa0.tulkki.xmpp.models.bind

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6120 resource binding: the `<resource/>` the client chooses. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the derived (`resource`,
 * `urn:ietf:params:xml:ns:xmpp-bind`) pair comes from the `@XmlElement` annotation. The one-argument
 * constructor stays a public secondary constructor, because `Bind.setResource` names it.
 */
@XmlElement(namespace = Namespace.BIND)
class Resource() : Extension(Resource::class.java) {

    constructor(resource: String) : this() {
        setContent(resource)
    }
}
