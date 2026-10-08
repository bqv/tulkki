package uk.xa0.tulkki.xmpp.models

/**
 * The `<request/>` half of a message-receipt family. Converted from the Java abstract class; only
 * Kotlin subclasses extend it and the constructor keeps its `protected` visibility.
 */
abstract class DeliveryReceiptRequest protected constructor(clazz: Class<out Extension>) :
    Extension(clazz)
