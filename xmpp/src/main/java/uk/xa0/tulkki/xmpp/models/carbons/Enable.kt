package uk.xa0.tulkki.xmpp.models.carbons

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0280 message carbons: the `<enable/>` request. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`enable`, `urn:xmpp:carbons:2`) pair comes from the
 * `@XmlElement` annotation. The unqualified name collides with `sm.Enable`, which is why
 * `XmppConnection` spells this one fully qualified.
 */
@XmlElement(namespace = Namespace.CARBONS)
class Enable : Extension(Enable::class.java)
