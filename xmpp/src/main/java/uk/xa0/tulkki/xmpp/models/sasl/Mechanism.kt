package uk.xa0.tulkki.xmpp.models.sasl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** RFC 6120 SASL: one `mechanism` name. The (`mechanism`, `urn:ietf:params:xml:ns:xmpp-sasl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL)
class Mechanism : Extension(Mechanism::class.java)
