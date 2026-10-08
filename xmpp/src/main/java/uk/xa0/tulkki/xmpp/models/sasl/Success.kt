package uk.xa0.tulkki.xmpp.models.sasl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/** RFC 6120 SASL: the `success` element. The (`success`, `urn:ietf:params:xml:ns:xmpp-sasl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL)
class Success : StreamElement(Success::class.java)
