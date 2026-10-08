package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0388 SASL2: the `mechanism` element. The (`mechanism`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class Mechanism : Extension(Mechanism::class.java)
