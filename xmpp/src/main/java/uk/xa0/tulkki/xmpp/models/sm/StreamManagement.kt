package uk.xa0.tulkki.xmpp.models.sm

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamFeature

/** XEP-0198 stream management: the `sm` stream feature. The (`sm`, `urn:xmpp:sm:3`) pair is in the compiled extension index. */
@XmlElement(name = "sm", namespace = Namespace.STREAM_MANAGEMENT)
class StreamManagement : StreamFeature(StreamManagement::class.java)
