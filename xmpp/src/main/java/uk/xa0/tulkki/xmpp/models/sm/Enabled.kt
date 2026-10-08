package uk.xa0.tulkki.xmpp.models.sm

import com.google.common.base.Optional
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/** XEP-0198 stream management: the `enabled` answer. The (`enabled`, `urn:xmpp:sm:3`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.STREAM_MANAGEMENT)
class Enabled : StreamElement(Enabled::class.java) {

    fun isResume(): Boolean = getAttributeAsBoolean("resume")

    fun getLocation(): String? = getAttribute("location")

    fun getResumeId(): Optional<String> {
        val id = getAttribute("id")
        if (id.isNullOrEmpty()) {
            return Optional.absent()
        }
        return if (isResume()) Optional.of(id) else Optional.absent()
    }
}
