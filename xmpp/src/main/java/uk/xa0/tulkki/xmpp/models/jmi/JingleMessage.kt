package uk.xa0.tulkki.xmpp.models.jmi

import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0353 Jingle Message Initiation: the base of the five message elements. Converted from the
 * Java class; only Kotlin subclasses ([Accept], [Proceed], [Propose], [Reject], [Retract]) extend it,
 * and `getSessionId` still answers null when the `id` attribute is absent.
 */
abstract class JingleMessage(clazz: Class<out JingleMessage>) : Extension(clazz) {

    fun getSessionId(): String? = getAttribute("id")
}
