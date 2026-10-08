package uk.xa0.tulkki.xmpp.models.roster

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * RFC 6121 roster group. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`group`, `jabber:iq:roster`) pair comes from the `@XmlElement`
 * annotation.
 */
@XmlElement(namespace = Namespace.ROSTER)
class Group : Extension(Group::class.java)
