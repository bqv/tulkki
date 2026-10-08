package uk.xa0.tulkki.xmpp.models.error

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** The human-readable text of a stanza error. Converted from the Java; nothing but its class. */
@XmlElement(namespace = Namespace.STANZAS)
class Text : Extension(Text::class.java)
