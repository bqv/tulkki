package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: the two outcomes of a conference affiliation change, un-nested out of
 * `XmppConnectionService`.
 *
 * Converted from the Java. `jid` is **non-null** on both arms: `ConferenceAdmin.kt:60/65` passes
 * `user.asBareJid()`, whose Kotlin declaration is `Jid` (not `Jid?`), and every implementer
 * dereferences it (`jid.asBareJid().toString()`). `resId` is a Java `int`.
 */
interface OnAffiliationChanged {
    fun onAffiliationChangedSuccessful(jid: Jid)

    fun onAffiliationChangeFailed(jid: Jid, resId: Int)
}
