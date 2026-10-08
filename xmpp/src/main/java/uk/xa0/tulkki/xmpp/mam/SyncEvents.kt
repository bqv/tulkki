package uk.xa0.tulkki.xmpp.mam

import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: what the island tells the sync engine, declared island-side and implemented by the
 * composition root.
 *
 * "Design: synchronisation" §3.2. The islands import nothing of ours and an island may not name a
 * non-island module, so the interface lives here and speaks the island's own refs - the shape
 * `MessageArchiveService.CatchupFinishedHook` already uses. The engine is the only writer of the
 * cursor; this is how the facts reach it.
 *
 * **Nothing installs one, and nothing calls one, yet - on purpose.** The island call sites are the
 * trigger retirement's: `onMamFin` replaces the `anyCatchup` trigger at `processFin`, and
 * `onMamAborted` is the new event for the timeout and error paths that reach no trigger at all
 * today (§0). S5-8 is additive; wiring these in is what removes the heuristic, and until that lands
 * the old trigger is still the only thing that runs the pass over a gap. The install point is the
 * composition root's, exactly as `CatchupFinishedHook`'s is.
 *
 * Ported from `SyncEvents.java`. It is *ours*, so it is
 * converted in place: every method keeps its name and descriptor, and the one nullable parameter is
 * the Java's own - `XmppTulkkiHost.onMessageStored` takes `ConversationRef?`, which an account-wide
 * stored message answers with null.
 */
interface SyncEvents {

    /**
     * A session was established. `atWallClock` is the session instant the ledger's `gap_end` is
     * recorded from - recorded once, rather than re-derived from a field a resume still holds from
     * the previous bind (§1.1).
     */
    fun onSessionEstablished(account: AccountRef, atWallClock: Long, resumed: Boolean)

    /** A MAM `<fin>`: the only event that can close a region. */
    fun onMamFin(fin: MamFin)

    /** A query that timed out, errored or was killed before any `<fin>` arrived. */
    fun onMamAborted(account: AccountRef, reason: MamAbort)

    /**
     * A message reached the database. `archived` is the delivery marker the row is written with:
     * an archive delivery is sweepable, a live one advances the floor.
     */
    fun onMessageStored(
        account: AccountRef,
        conversation: ConversationRef?,
        timeSent: Long,
        archived: Boolean,
    )

    /** CSI went inactive or active. Active is a reconcile point, never a gap-opener (§2.2). */
    fun onClientStateChanged(account: AccountRef, active: Boolean)

    /** The session ended: every region still open becomes `DEGRADED`, and the anchors stay put. */
    fun onSessionEnded(account: AccountRef)
}
