package uk.xa0.tulkki.xmpp.models.ping

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0199 XMPP ping: the `<ping/>`. The namespace is named by the class rather than a package,
 * because Kotlin cannot annotate a package; the (`ping`, `urn:xmpp:ping`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.PING)
class Ping : Extension(Ping::class.java)
