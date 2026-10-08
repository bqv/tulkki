package uk.xa0.tulkki.xmpp.models

/**
 * A server-stream element: an [Extension] bound to the stream rather than to a stanza. Converted
 * from the Java abstract class; `stanza.Stanza` still extends it and keeps a `protected`
 * constructor, so the visibility is written out rather than taken from Kotlin's default.
 */
abstract class StreamElement protected constructor(clazz: Class<out StreamElement>) : Extension(clazz)
