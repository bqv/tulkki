package uk.xa0.tulkki.xmpp.models.sm

import com.google.common.base.Optional
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/** XEP-0198 stream management: the `failed` answer. The (`failed`, `urn:xmpp:sm:3`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.STREAM_MANAGEMENT)
class Failed : StreamElement(Failed::class.java) {

    fun getHandled(): Optional<Int> = getOptionalIntAttribute("h")
}
