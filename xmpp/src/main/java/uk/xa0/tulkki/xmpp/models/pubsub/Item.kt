package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0060 publish-subscribe: the contract a `<item/>` implements. Converted from the Java
 * interface; `getExtension` stays on it because the Java one declared it, and every implementor
 * inherits the matching member from [Extension]. `getId` answers null when the attribute is absent.
 */
interface Item {

    fun <T : Extension> getExtension(clazz: Class<T>): T?

    fun getId(): String?
}
