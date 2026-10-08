package uk.xa0.tulkki.data.sync

import androidx.room.Dao
import androidx.room.Query

/**
 * The account cursor's reads and writes (S5-3, the `sync/` capability).
 *
 * <p>**The SQL is spelled here and nowhere else, and the test reads it back off these annotations.**
 * The tidy alternative - a `const val` per statement in [SyncQueries], named by the annotation -
 * compiles to the same SQL but is not what is written, because a later reader must be able to see
 * the query Room will run without opening another file. `SyncDaoTest` asserts by reflection that
 * each annotation equals its [SyncQueries] constant and then executes the annotation's own SQL over
 * the JDBC fixture, so a drift between the two is a red test rather than a surprise.
 *
 * <p>`INSERT OR REPLACE` rather than `@Insert`: the cursor is one row per account and every writer
 * (a `<fin>`, a live stanza carrying a `serverMsgId`, a clear-history action, the upgrade seed)
 * holds the whole row, so the statement is the upsert. Room accepts `void` or `long` for an INSERT
 * `@Query` and rejects anything else, measured in batch 1.
 *
 * <p>`internal`, like every DAO: the module's public surface is the read models
 * (`docs/MIGRATION.md`, "Design: the data layer" §2.5).
 */
@Dao
internal interface SyncCursorDao {

    /** The account's one row, or `null` when this account has never synced (`Cursor.initial()`). */
    @Query("SELECT * FROM sync_cursor WHERE account_uuid = :account")
    fun byAccount(account: String): SyncCursorEntity?

    /**
     * The rowid of the written row. `INSERT OR REPLACE` is a delete-and-insert in SQLite, which is
     * what "the cursor is one row and this is its value" means here - there is no second writer to
     * merge a partial update against.
     *
     * <p>**The row is written only while the account is still in the file**, which is what
     * [SyncQueries.CURSOR_UPSERT]'s guard is for: the registration flow's abort deletes the account
     * on the database-writer executor while the socket's teardown reports `onSessionEnded` to the
     * engine on its own thread, and the cursor's `FOREIGN KEY` answered that with code 787 on
     * `tulkki-sync` - the process death this guard is for. Nothing is created and nothing is
     * retried: the account is gone and its uuid is never reused. A cursor written before the delete
     * commits is taken by the foreign key's own `ON DELETE CASCADE`.
     */
    @Query(
        "INSERT OR REPLACE INTO sync_cursor " +
            "(account_uuid, anchor_stanza_id, anchor_time, anchor_source, gap_end, updated_at) " +
            "SELECT :account, :stanzaId, :time, :source, :gapEnd, :updatedAt " +
            "WHERE EXISTS (SELECT 1 FROM accounts WHERE uuid = :account)"
    )
    fun upsert(
        account: String,
        stanzaId: String?,
        time: Long,
        source: Long,
        gapEnd: Long,
        updatedAt: Long,
    ): Long

    /**
     * The defensive delete of "Design: synchronisation" §1.5: a re-added account gets a new uuid and
     * the foreign key's cascade fires, but the cursor must never survive an account it describes.
     * 1 when a row was removed, 0 when there was nothing to remove.
     */
    @Query("DELETE FROM sync_cursor WHERE account_uuid = :account")
    fun remove(account: String): Int
}
