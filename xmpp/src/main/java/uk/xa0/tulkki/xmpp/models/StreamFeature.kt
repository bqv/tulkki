package uk.xa0.tulkki.xmpp.models

/**
 * A stream-negotiation feature: an [Extension] advertised in `<stream:features/>`. Converted from
 * the Java abstract class; the constructor was `public` and stays so, and only Kotlin classes
 * extend it now that the SASL mechanisms moved.
 */
abstract class StreamFeature(clazz: Class<out StreamFeature>) : Extension(clazz)
