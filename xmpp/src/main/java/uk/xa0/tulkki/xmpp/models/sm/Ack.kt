package uk.xa0.tulkki.xmpp.models.sm

import com.google.common.base.Optional
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * XEP-0198 stream management: the `a` acknowledgement. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`a`, `urn:xmpp:sm:3`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "a", namespace = Namespace.STREAM_MANAGEMENT)
class Ack : StreamElement {

    constructor() : super(Ack::class.java)

    constructor(sequence: Int) : super(Ack::class.java) {
        setAttribute("h", sequence)
    }

    fun getHandled(): Optional<Int> = getOptionalIntAttribute("h")
}
