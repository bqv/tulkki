package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: whether the "jump to message" search found its target, un-nested out of
 * `XmppConnectionService`.
 *
 * Converted from the Java. The two members take nothing, so no parameter's nullability had to be
 * decided. The one implementer is the Java `ui/ConversationFragment.java:739`-adjacent anonymous
 * class, which implements both members by hand, so nothing about the shape changes.
 */
interface JumpToMessageListener {
    fun onSuccess()

    fun onNotFound()
}
