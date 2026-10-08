package uk.xa0.tulkki.xmpp.models.fast

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.crypto.sasl.HashedToken
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0484 fast authentication: the `<request-token/>` asking for a hashed token. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`request-token`, `urn:xmpp:fast:0`) pair comes from the `@XmlElement` annotation. The
 * `HashedToken.Mechanism` constructor stays a public secondary constructor, because
 * `XmppConnection` names it, and `Mechanism.name()` keeps its spelling.
 */
@XmlElement(namespace = Namespace.FAST)
class RequestToken() : Extension(RequestToken::class.java) {

    constructor(mechanism: HashedToken.Mechanism) : this() {
        setAttribute("mechanism", mechanism.name())
    }
}
