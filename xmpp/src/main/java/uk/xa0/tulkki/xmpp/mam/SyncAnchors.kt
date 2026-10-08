package uk.xa0.tulkki.xmpp.mam

import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: what the island asks the sync engine for, declared island-side and answered by the
 * composition root.
 *
 * "Design: synchronisation" §3.2. It is what replaces the three reads `catchup()` makes today -
 * `databaseBackend.getLastMessageReceived(account)`, `getLastClearDate(account)` and
 * `conversation.getLastMessageTransmitted()` - so that the island asks for an anchor rather than
 * reaching into a database for a derivation. The answer is the same `MamReference` pair the wire
 * speaks, so nothing about paging changes.
 *
 * **Nothing asks, yet.** Rewiring `catchup()` onto this is the retirement of the old derivations
 * (§1.6), which S5-8 deliberately does not start: until then this declares the seam.
 *
 * Ported from `SyncAnchors.java`. It is *ours*, so it is
 * converted in place: the two overloads keep their names and descriptors, and the Java implementor
 * (`uk.xa0.tulkki.xmpp.services.TulkkiPorts.syncAnchors()`) and the Kotlin one (`XmppTulkkiHost`) both
 * still override them. The returns are non-null because every implementation answers a
 * `MamReference` and no caller tests for null.
 */
interface SyncAnchors {

    /** Where the account-wide catch-up starts, or a zero reference when nothing has been seen. */
    fun anchorFor(account: AccountRef): MamReference

    /** Where one conversation's paging starts, or a zero reference when it has no known history. */
    fun anchorFor(conversation: ConversationRef): MamReference
}
