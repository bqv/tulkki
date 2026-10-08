package uk.xa0.tulkki.xmpp.models.muc

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0045 multi-user chat: the `<x/>` of a join. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`x`, `http://jabber.org/protocol/muc`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "x", namespace = Namespace.MUC)
class MultiUserChat : Extension(MultiUserChat::class.java)
