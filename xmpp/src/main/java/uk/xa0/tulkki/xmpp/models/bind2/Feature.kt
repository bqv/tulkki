package uk.xa0.tulkki.xmpp.models.bind2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0386 bind 2.0: the `<feature/>` a bind 2.0 server advertises inside its inline element. The
 * package namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`feature`, `urn:xmpp:bind:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.BIND2)
class Feature : Extension(Feature::class.java)
