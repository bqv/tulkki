package uk.xa0.tulkki.data.sync

import androidx.room.Dao
import androidx.room.Query

/**
 * The gap ledger, and the two sweeps that read it (S5-3, the `sync/` capability).
 *
 * <p>A gap is keyed by the four-tuple `(account, conversation, gap_start, region)`, which is why
 * `conversation_uuid` is `NOT NULL DEFAULT ''` - the empty string is the account-wide scope and a
 * nullable column would let the same row in twice (`SyncGapEntity`'s comment).
 *
 * <p>**The two sweep queries live here because the decision they answer is this package's.** The
 * gap sweep is the archive half: `delivery = 1` rows inside the regions the ledger has open. The
 * live-miss sweep is the floor half: `delivery = 0` rows newer than the conversation's
 * `swept_through`, bounded per conversation instead of by one global `long`
 * ("Design: synchronisation" §1.2). Both return the row's `uuid`, and [SyncQueries] says why the
 * projection is not `*`.
 *
 * <p>The SQL is spelled here, as in `SyncCursorDao`; `SyncDaoTest` compares the annotations to
 * [SyncQueries] and executes them.
 */
@Dao
internal interface SyncGapDao {

    /** One region's row, or `null` when it was never opened. */
    @Query(
        "SELECT * FROM sync_gap WHERE account_uuid = :account " +
            "AND conversation_uuid = :conversation AND gap_start = :gapStart AND region = :region"
    )
    fun byKey(account: String, conversation: String, gapStart: Long, region: Long): SyncGapEntity?

    /**
     * Every region still open for an account, oldest first. A region that was opened and never
     * proven is re-opened from the same `gap_start`, so the reader needs them in that order.
     */
    @Query(
        "SELECT * FROM sync_gap WHERE account_uuid = :account AND state = 0 " +
            "ORDER BY gap_start ASC, region ASC"
    )
    fun openForAccount(account: String): List<SyncGapEntity>

    /**
     * Every region of the account, `COMPLETE` and `DEGRADED` included, in the same order. The engine
     * rebuilds its ledger from these on the first event after a process death: completeness is a
     * statement about all of a gap's regions, and a degraded one is re-opened from its row.
     */
    @Query(
        "SELECT * FROM sync_gap WHERE account_uuid = :account " +
            "ORDER BY gap_start ASC, region ASC"
    )
    fun forAccount(account: String): List<SyncGapEntity>

    /**
     * Opens (or re-opens) one region. `INSERT OR REPLACE` is an episode being written whole: the
     * engine holds the `gap_start`, the `gap_end` it measured at session establishment, the region
     * and the open instant together.
     *
     * <p>Guarded on the account's presence for the reason `SyncCursorDao.upsert` gives: the same
     * transition writes this row and the cursor, so the region is the engine's other account-keyed
     * insert and the foreign key would fail here in exactly the same race.
     */
    @Query(
        "INSERT OR REPLACE INTO sync_gap " +
            "(account_uuid, conversation_uuid, gap_start, gap_end, region, state, reason, opened_at, " +
            "closed_at) SELECT :account, :conversation, :gapStart, :gapEnd, :region, :state, " +
            ":reason, :openedAt, :closedAt " +
            "WHERE EXISTS (SELECT 1 FROM accounts WHERE uuid = :account)"
    )
    fun open(
        account: String,
        conversation: String,
        gapStart: Long,
        gapEnd: Long,
        region: Long,
        state: Long,
        reason: String?,
        openedAt: Long,
        closedAt: Long?,
    ): Long

    /**
     * Closes one region as COMPLETE or DEGRADED. It is a targeted `UPDATE` rather than a rewrite so
     * that a close can only ever move `state`, `reason` and `closed_at` - the anchor and the region
     * the row already carries are not the caller's to restate.
     */
    @Query(
        "UPDATE sync_gap SET state = :state, reason = :reason, closed_at = :closedAt " +
            "WHERE account_uuid = :account AND conversation_uuid = :conversation " +
            "AND gap_start = :gapStart AND region = :region"
    )
    fun close(
        account: String,
        conversation: String,
        gapStart: Long,
        region: Long,
        state: Long,
        reason: String?,
        closedAt: Long?,
    ): Int

    /**
     * The archive half of the sweep: `delivery = 1` rows inside the account's open regions. The
     * empty conversation is the account-wide region and covers every conversation of that account;
     * `SyncQueries.GAP_SWEEP_CANDIDATES` carries the reason that clause is load-bearing.
     */
    @Query(
        "SELECT m.uuid FROM messages m JOIN conversations c ON c.uuid = m.conversationUuid " +
            "WHERE c.accountUuid = :account AND m.status = 0 AND m.translation_state = 0 " +
            "AND m.delivery = 1 AND EXISTS (SELECT 1 FROM sync_gap g " +
            "WHERE g.account_uuid = :account AND g.state = 0 " +
            "AND (g.conversation_uuid = '' OR g.conversation_uuid = m.conversationUuid))"
    )
    fun gapSweepCandidates(account: String): List<String>

    /** The live-miss half: `delivery = 0` rows above one conversation's own floor. */
    @Query(
        "SELECT uuid FROM messages WHERE conversationUuid = :conversation AND status = 0 " +
            "AND translation_state = 0 AND delivery = 0 AND timeSent > :sweptThrough " +
            "ORDER BY timeSent DESC LIMIT :limit"
    )
    fun liveMissCandidates(conversation: String, sweptThrough: Long, limit: Int): List<String>
}
