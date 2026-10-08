package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the call-log listener contract, un-nested out of `XmppConnectionService`
 *.
 *
 * A nested type cannot keep a one-line delegation the way a method can, so the whole declaration
 * moves. `:ui`'s `CallsActivity` names it in its supertype list as a fully qualified name and
 * imports nothing new, so the hoist costs the ratchet no key.
 */
fun interface OnCallLogUpdated {
    fun onCallLogUpdated()
}
