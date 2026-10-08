package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the account-list refresh, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. The Java was left Java because "a Kotlin spelling annotates every
 * parameter non-null" - this member has no parameter, so no nullability reading was needed and the
 * declaration is the Java's, one method with no arguments. The interface is not a `fun interface`:
 * nothing in the tree constructs it as a SAM lambda (`git grep "OnAccountUpdate {"` finds only
 * `object :` sites, which a plain interface already serves).
 */
interface OnAccountUpdate {
    fun onAccountUpdate()
}
