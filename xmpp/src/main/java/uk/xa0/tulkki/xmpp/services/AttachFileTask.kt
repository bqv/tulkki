package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: a queued attachment, which is a `Runnable` plus the one question it answers.
 *
 * Un-nested out of `XmppConnectionService` beside [AttachFilePort], whose `create` answers one; it
 * was a sibling nested type, not a member of the port, and it stays a sibling at this level.
 * `:app`'s `XmppTulkkiHost` spells the name and the `extends Runnable` its object expression
 * implements, which Kotlin keeps as a superinterface.
 */
interface AttachFileTask : Runnable {

    fun isVideoMessage(): Boolean
}
