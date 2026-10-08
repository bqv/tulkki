package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the outcome of a certificate-key account creation, un-nested out of
 * `XmppConnectionService`.
 *
 * Converted from the Java. [onAccountCreated]'s `account` is **non-null**: the only call site,
 * `AccountLifecycle.kt:148`, passes the account `accounts().create(...)` just returned, and both
 * `:ui` implementers dereference it with no guard (`account.getJid()`), so a Kotlin `?` would only
 * add a contract nobody can satisfy. `informUser`'s argument is a Java `int`.
 */
interface OnAccountCreated {
    fun onAccountCreated(account: AccountRef)

    fun informUser(r: Int)
}
