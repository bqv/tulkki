package uk.xa0.tulkki.xmpp.models

/**
 * The `<received/>` half of a message-receipt family: XEP-0184's and XEP-0333's marker. Converted
 * from the Java abstract class; only Kotlin subclasses extend it, the constructor keeps its
 * `protected` visibility, and `getId` stays abstract and nullable, read off `getAttribute`.
 */
abstract class DeliveryReceipt protected constructor(clazz: Class<out Extension>) : Extension(clazz) {

    abstract fun getId(): String?
}
