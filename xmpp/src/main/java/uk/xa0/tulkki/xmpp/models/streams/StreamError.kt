package uk.xa0.tulkki.xmpp.models.streams

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * RFC 6120 stream error. The package namespace moves onto the class, because Kotlin cannot
 * annotate a package; the (`error`, `http://etherx.jabber.org/streams`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "error", namespace = Namespace.STREAMS)
class StreamError : StreamElement(StreamError::class.java)
