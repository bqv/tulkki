package uk.xa0.tulkki.xmpp.models.nick

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0172 user nickname: the `<nick/>`. The namespace is named by the class rather than a package,
 * because Kotlin cannot annotate a package; the (`nick`, `http://jabber.org/protocol/nick`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.NICK)
class Nick : Extension(Nick::class.java)
