package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.ContactRef

/**
 * Tulkki: presence-of-a-contact callback, spoken in the island's own reference type.
 *
 * Ported from `OnContactStatusChanged.java`. The one
 * implementor is a Java lambda in `XmppConnectionService.onContactStatusChanged`; its parameters
 * are non-null (the lambda immediately dereferences `contact` and `online` is a primitive), and
 * the Java SAM shape is kept so that lambda still compiles.
 */
interface OnContactStatusChanged {
    fun onContactStatusChanged(contact: ContactRef, online: Boolean)
}
