package uk.xa0.tulkki.data.sync

/**
 * The ledger's completeness proof: what replaces `MessageArchiveService.anyCatchup`.
 *
 * <p>"Design: synchronisation" §2.3 states it once: a region is `COMPLETE` iff its `<fin>` said
 * `complete='true'`, or its `count` proved the total, or it was closed by an explicit proof of the
 * same kind for **its own** `(conversation, region)`. The account's gap is complete iff every region
 * opened for it is `COMPLETE`. `anyCatchup` asked a shared mutable set three questions it could not
 * answer - which region was missing, whether a region was ever delivered, and whether "finished"
 * meant "complete" - and every cell below is one of those three.
 *
 * <p>Pure, like [CursorMath]: the entries are values, the `<fin>` is a value ([Fin]), and no clock is
 * read. [Fin] carries only the fields `processFin` already reads, so the island's `MamFin` can be
 * adapted to it without `:data` naming a mutable island `Query`.
 *
 * <p>**A region is keyed by its own tuple**, which is why the account-wide and the reverse regions
 * cannot close each other even when they share a `gap_start`: the account-wide region is the empty
 * conversation and the reverse one is a conversation's, `region` 0 against 1.
 */
internal object GapLedger {

    /** One ledger row, as the proof needs it. */
    data class Entry(val region: CursorMath.Region, val state: Long, val reason: String?)

    /**
     * A `<fin>`, reduced to the facts the proof reads. `order` is [ORDER_NORMAL] or
     * [ORDER_REVERSE]; `count` is RSM's `<count>` when the server sent one; `totalCount` is how many
     * the query actually delivered, which is what upstream compares it with. `newestTime` and
     * `newestReference` are the newest stanza the page accounted for - the island's `MamFin.last()`
     * for a forward page, its `first()` for a reverse one - and they are what a proven close advances
     * the paging anchor to.
     */
    data class Fin(
        val conversation: String,
        val start: Long,
        val order: Long,
        val complete: Boolean,
        val count: Int?,
        val totalCount: Int,
        val newestTime: Long = 0L,
        val newestReference: String? = null,
    )

    /** What one `<fin>` decided: the ledger, the region it closed, and whether the anchor may move. */
    data class Outcome(
        val entries: List<Entry>,
        val closed: CursorMath.Region?,
        val advanceAnchor: Boolean,
    )

    /** The island's `PagingOrder`, as this package's pure model spells it. */
    const val ORDER_NORMAL = 0L

    const val ORDER_REVERSE = 1L

    /** `sync_gap.reason`'s vocabulary, for the four this object writes ("Design: synchronisation" §1.1). */
    const val REASON_ABORTED_AT_LIMIT = "ABORTED_AT_LIMIT"

    const val REASON_TIMEOUT = "TIMEOUT"

    /** The server answered an error rather than a `<fin>`: `MamAbort.ERROR`'s row vocabulary. */
    const val REASON_ERROR = "ERROR"

    const val REASON_KILLED = "KILLED"

    const val REASON_SESSION_LOST = "SESSION_LOST"

    /** The regions a session must open, as the ledger rows they become: every one starts `OPEN`. */
    fun openRegions(regions: List<CursorMath.Region>): List<Entry> =
        regions.map { Entry(it, SyncQueries.GAP_STATE_OPEN.toLong(), null) }

    /** §2.3, and the whole of the replacement: every region opened for the account is `COMPLETE`. */
    fun complete(entries: List<Entry>): Boolean =
        entries.isNotEmpty() && entries.all { it.state == SyncQueries.GAP_STATE_COMPLETE.toLong() }

    /**
     * One `<fin>`. It closes **its own** region - matched on the conversation, the `gap_start` it was
     * opened with and the kind its paging order implies - and it closes it only on a proof: the RSM
     * `complete` attribute, or a `count` no larger than what was delivered. The abort bound at
     * `MAM_MAX_MESSAGES` is the opposite answer: the region is `DEGRADED` with
     * [REASON_ABORTED_AT_LIMIT] rather than closed, and **the anchor may not advance** - upstream
     * stopped early, and moving the frontier would decide a region nobody read.
     */
    fun applyFin(entries: List<Entry>, fin: Fin, maxMessages: Int): Outcome {
        val key = CursorMath.Region(fin.conversation, fin.start, kindOf(fin.order))
        val index = entries.indexOfFirst { it.region == key }
        if (index < 0) {
            return Outcome(entries, null, advanceAnchor = false)
        }
        val proved = fin.complete || (fin.count != null && fin.count <= fin.totalCount)
        if (proved) {
            return Outcome(closed(entries, index, SyncQueries.GAP_STATE_COMPLETE.toLong(), null), key, true)
        }
        if (fin.totalCount >= maxMessages) {
            return Outcome(
                closed(entries, index, SyncQueries.GAP_STATE_DEGRADED.toLong(), REASON_ABORTED_AT_LIMIT),
                null,
                advanceAnchor = false,
            )
        }
        // No proof either way: the server pages on, and the ledger waits for it.
        return Outcome(entries, null, advanceAnchor = false)
    }

    /**
     * The region's query timed out, errored or was killed. Today this path reaches no trigger at all,
     * which is the hole §0 names; the row stays owed, as [reason] says, and never `COMPLETE`.
     */
    fun abort(entries: List<Entry>, region: CursorMath.Region, reason: String): List<Entry> {
        val index = entries.indexOfFirst { it.region == region }
        if (index < 0) {
            return entries
        }
        return closed(entries, index, SyncQueries.GAP_STATE_DEGRADED.toLong(), reason)
    }

    /**
     * The session ended with regions still open: every one of them becomes `DEGRADED` with
     * [REASON_SESSION_LOST] - a region that was opened and never proven - while the ones already
     * closed are left exactly as they are. The anchors are untouched: they are the record of what was
     * accounted for.
     */
    fun sessionLost(entries: List<Entry>): List<Entry> =
        entries.map { entry ->
            if (entry.state == SyncQueries.GAP_STATE_OPEN.toLong()) {
                entry.copy(state = SyncQueries.GAP_STATE_DEGRADED.toLong(), reason = REASON_SESSION_LOST)
            } else {
                entry
            }
        }

    /**
     * Re-open a degraded region for the next session, or for CSI going active without a reconnect.
     * The region - and so its `gap_start` - is the one it already had: the primary key is unchanged,
     * so re-opening cannot create a second row, and the sweep that a close owes cannot be owed twice
     * for one absence.
     */
    fun reopened(entries: List<Entry>): List<Entry> =
        entries.map { entry ->
            if (entry.state == SyncQueries.GAP_STATE_DEGRADED.toLong()) {
                Entry(entry.region, SyncQueries.GAP_STATE_OPEN.toLong(), null)
            } else {
                entry
            }
        }

    /** The kind a paging order implies: a forward page is the `FIRST` region, a reverse one `RECENT`. */
    fun kindOf(order: Long): Long =
        if (order == ORDER_REVERSE) CursorMath.REGION_RECENT else CursorMath.REGION_FIRST

    private fun closed(entries: List<Entry>, index: Int, state: Long, reason: String?): List<Entry> =
        entries.mapIndexed { at, entry -> if (at == index) entry.copy(state = state, reason = reason) else entry }
}
