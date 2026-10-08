package uk.xa0.tulkki.data.sync

import uk.xa0.tulkki.data.model.Conversation

/**
 * The arithmetic of one cursor: the paging anchor, the sweep floor and the regions a session opens.
 *
 * <p>Pure by construction ("Design: synchronisation" §7.1): no `Context`, no `Cursor`, no `Account`,
 * no clock - every function takes the instant it reasons about, so `CursorMathTest` drives the rules
 * a device would and a fake clock can stand at any point in a session.
 *
 * <p>**Three facts, three functions** (§1.2). [clamped], [raised], [usableForPaging] and [hasWidth]
 * are the paging anchor's arithmetic. [sweepFloorAfter] is the sweep floor's: the rule
 * `StartupBacklog.everything` carries today, ported per conversation - a page read back full leaves
 * the floor just behind its oldest row, so the next read walks further on, and a page that was not
 * full advances it to the newest row the read examined, whether or not that row was worth spending
 * on. [regions] is the third: the regions a session opens, enumerated by upstream's own rule
 * (`MessageArchiveService.catchup` and its `query(conversation, start, end, allowCatchup)`), so the
 * ledger knows the whole answer before the first query goes out.
 *
 * <p>`anchorTime == 0` is the engine's [initial] and means "no gap, and none will be invented"
 * (§1.3): upstream's `catchup()` returns before querying on a zero timestamp, and this keeps it.
 */
internal object CursorMath {

    /** One account's cursor row, as the arithmetic needs it. */
    data class Cursor(
        val anchorStanzaId: String?,
        val anchorTime: Long,
        val anchorSource: Long,
        val gapEnd: Long,
    )

    /** One conversation's cursor row. `mode` is `Conversation.MODE_SINGLE`/`MODE_MULTI`. */
    data class ConversationCursor(
        val conversation: String,
        val mode: Int,
        val anchorStanzaId: String?,
        val anchorTime: Long,
        val sweptThrough: Long,
    )

    /** A cursor with the account it belongs to: the file holds one row per account. */
    data class AccountCursor(val account: String, val cursor: Cursor)

    /**
     * A region a session owes. `conversation` is the empty string for the account-wide catch-up
     * (`SyncGapEntity`), and `kind` is [REGION_FIRST] for a forward page or [REGION_RECENT] for the
     * reverse one.
     */
    data class Region(val conversation: String, val gapStart: Long, val kind: Long)

    /** One row a sweep read examined: when it was sent, and whether it already carries an answer. */
    data class SweptRow(val timeSent: Long, val answered: Boolean)

    /** The account-wide scope: `sync_gap.conversation_uuid`'s empty string. */
    const val ACCOUNT_WIDE = ""

    /** `sync_gap.region`'s two values: a forward page, and the reverse query beside it. */
    const val REGION_FIRST = 0L

    const val REGION_RECENT = 1L

    /** A fresh account or conversation: no anchor, source INITIAL, no session measured. */
    fun initial(): Cursor = Cursor(null, 0L, SyncQueries.ANCHOR_SOURCE_INITIAL.toLong(), 0L)

    /**
     * §1.5's clamp: an anchor ahead of the session is a clock jump or a corrupt row, and MAM's
     * `start` would be in the future, so the region would never close. Clamp to the session instant
     * and mark it `INITIAL` - recorded, never silently repaired.
     */
    fun clamped(cursor: Cursor, sessionInstant: Long): Cursor =
        if (cursor.anchorTime > sessionInstant) {
            cursor.copy(anchorTime = sessionInstant, anchorSource = SyncQueries.ANCHOR_SOURCE_INITIAL.toLong())
        } else {
            cursor
        }

    /**
     * §1.5: a stanza id without a time is legal upstream - `Query` prefers `reference` and ignores
     * `start` - so it pages, and it cannot measure a gap width. The width falls back to `INITIAL`.
     */
    fun usableForPaging(cursor: Cursor): Boolean = cursor.anchorTime > 0 || cursor.anchorStanzaId != null

    /** And the other half of the same pair: only a time measures. */
    fun hasWidth(cursor: Cursor): Boolean = cursor.anchorTime > 0

    /**
     * §1.5: the account anchor is never behind a conversation anchor - the account-wide query lost a
     * race with a per-conversation one - so it is raised to the newest of them before a gap opens,
     * reference and all. The source is left where it was: raising the frontier is a correction, not
     * a delivery this device just saw, and the row's own source still names how the account last
     * learned where it was.
     */
    fun raised(cursor: Cursor, conversationCursors: List<ConversationCursor>): Cursor {
        val newest = conversationCursors.maxByOrNull { it.anchorTime } ?: return cursor
        if (newest.anchorTime <= cursor.anchorTime) {
            return cursor
        }
        return cursor.copy(
            anchorStanzaId = newest.anchorStanzaId,
            anchorTime = newest.anchorTime,
        )
    }

    /**
     * The sweep floor after one page. A full page may have been truncated by the read's own `LIMIT`,
     * so the floor is left just behind its oldest row and the next read walks further on; a page that
     * was not full has been examined to its end, so the floor moves up to the newest row examined -
     * *examined*, not chosen, which is what stops a row that needs no translation from being re-read
     * for ever.
     */
    fun sweepFloorAfter(rows: List<SweptRow>, floor: Long, readLimit: Int): Long {
        if (rows.isEmpty()) {
            return floor
        }
        if (rows.size >= readLimit) {
            return rows.minOf { it.timeSent } - 1L
        }
        return maxOf(floor, rows.maxOf { it.timeSent })
    }

    /** The rows a sweep may spend on: newer than the floor, and still without an answer. */
    fun sweepRows(rows: List<SweptRow>, floor: Long): List<Long> =
        rows.filter { !it.answered && it.timeSent > floor }.map { it.timeSent }.sorted()

    /**
     * A page that advanced the forward anchor (§1.1: the RSM id of the newest archived stanza
     * accounted for). The reverse region pages the other way, and its page is a read of what the
     * forward walk has not reached: it never moves the forward anchor.
     */
    fun anchorAfterPage(
        cursor: Cursor,
        kind: Long,
        newestTime: Long,
        newestReference: String?,
        source: Long,
    ): Cursor {
        if (kind != REGION_FIRST || newestTime <= cursor.anchorTime) {
            return cursor
        }
        return cursor.copy(
            anchorStanzaId = newestReference,
            anchorTime = newestTime,
            anchorSource = source,
        )
    }

    /** One account's cursor out of every row the file holds. Another account's row is not it. */
    fun forAccount(cursors: List<AccountCursor>, account: String): Cursor =
        cursors.firstOrNull { it.account == account }?.cursor ?: initial()

    /**
     * The regions a session opens, by upstream's own rule (§2.2). The account-wide region always;
     * and, when the absence reaches the catch-up window, one reverse region plus one forward region
     * per single conversation whose own anchor precedes the window's edge - which is exactly the pair
     * `query(conversation, start, end, allowCatchup = true)` issues, and why the split is at exactly
     * the window and not at the conversation's anchor.
     *
     * <p>Nothing is enumerated for a cursor with no width: `anchor_time == 0` means the first page is
     * what upstream asks for, and this engine opens no gap for it (§1.3).
     *
     * <p>**The decision uses the account's own anchor, not [raised]'s correction**, and that is
     * upstream's arithmetic rather than a preference: `catchup()` compares `endCatchup` against
     * `MamReference.max(getLastMessageReceived, getLastClearDate)`, and a conversation's anchor is
     * compared only with the edge. Raising the account anchor to the newest conversation *before*
     * this decision would let one modern conversation turn an old absence into a short one and drop
     * every other conversation's older region, with no query left to cover it. [raised] is the stored
     * row's correction (§1.5), applied by the engine before it records the cursor, and it is
     * deliberately not folded in here.
     */
    fun regions(
        cursor: Cursor,
        conversationCursors: List<ConversationCursor>,
        sessionInstant: Long,
        window: Long,
    ): List<Region> {
        if (!hasWidth(cursor)) {
            return emptyList()
        }
        val anchored = clamped(cursor, sessionInstant)
        val regions = ArrayList<Region>()
        if (sessionInstant - anchored.anchorTime < window) {
            regions += Region(ACCOUNT_WIDE, anchored.anchorTime, REGION_FIRST)
            return regions
        }
        val cut = sessionInstant - window
        regions += Region(ACCOUNT_WIDE, cut, REGION_FIRST)
        for (conversation in conversationCursors) {
            if (conversation.mode != Conversation.MODE_SINGLE) {
                continue
            }
            if (conversation.anchorTime >= cut) {
                continue
            }
            // The reverse region [its own anchor, the cut] and the forward one [the cut, now]:
            // upstream issues both, and the two `<fin>`s must not close each other.
            regions += Region(conversation.conversation, conversation.anchorTime, REGION_RECENT)
            regions += Region(conversation.conversation, cut, REGION_FIRST)
        }
        return regions
    }
}
