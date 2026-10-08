package uk.xa0.tulkki.xmpp.models.streams

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement
import uk.xa0.tulkki.xmpp.models.StreamFeature
import uk.xa0.tulkki.xmpp.models.capabilties.EntityCapabilities
import uk.xa0.tulkki.xmpp.models.sm.StreamManagement

/**
 * RFC 6120 stream features. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`features`, `http://etherx.jabber.org/streams`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.STREAMS)
class Features : StreamElement(Features::class.java), EntityCapabilities {

    fun streamManagement(): Boolean = hasStreamFeature(StreamManagement::class.java)

    fun invite(): Boolean = hasChild("register", Namespace.INVITE)

    fun clientStateIndication(): Boolean = hasChild("csi", Namespace.CSI)

    fun hasStreamFeature(clazz: Class<out StreamFeature>): Boolean = hasExtension(clazz)
}
