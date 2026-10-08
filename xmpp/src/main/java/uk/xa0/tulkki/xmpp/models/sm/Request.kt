package uk.xa0.tulkki.xmpp.models.sm

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/** XEP-0198 stream management: the `r` acknowledgement request. The (`r`, `urn:xmpp:sm:3`) pair is in the compiled extension index. */
@XmlElement(name = "r", namespace = Namespace.STREAM_MANAGEMENT)
class Request : StreamElement(Request::class.java)
