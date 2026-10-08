package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the bind-result hook the wire layer offers its host.
 *
 * Ported from `OnBindListener.java`. It is an island
 * interface, so the conversion is a faithful reproduction: the single abstract method keeps its
 * name and descriptor and the Java-visible SAM shape is unchanged. No caller names it today - the
 * one `:xmpp` hit is an import in `XmppConnectionService` - so the only surface is the interface
 * itself.
 */
interface OnBindListener {
    fun onBind(account: AccountRef)
}
