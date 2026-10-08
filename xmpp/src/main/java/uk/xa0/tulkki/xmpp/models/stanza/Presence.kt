package uk.xa0.tulkki.xmpp.models.stanza

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.capabilties.EntityCapabilities

/** RFC 6120 presence. Converted from the Java; the interface is `capabilties.EntityCapabilities`. */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Presence : Stanza(Presence::class.java), EntityCapabilities
