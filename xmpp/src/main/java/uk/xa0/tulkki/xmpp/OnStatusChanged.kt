package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: an account's connection state changed, spoken in the island's own reference type.
 *
 * Ported from `OnStatusChanged.java`. The one
 * implementor is a Java anonymous class in `XmppConnectionService`; both call sites hand it a live
 * account, so the parameter is non-null and the SAM shape is unchanged.
 */
interface OnStatusChanged {
    fun onStatusChanged(account: AccountRef)
}
