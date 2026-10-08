package uk.xa0.tulkki.xmpp.models.sm

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/** XEP-0198 stream management: the resume request. The (`resume`, `urn:xmpp:sm:3`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.STREAM_MANAGEMENT)
class Resume : StreamElement {

    constructor() : super(Resume::class.java)

    constructor(id: String, sequence: Int) : super(Resume::class.java) {
        setAttribute("previd", id)
        setAttribute("h", sequence)
    }
}
