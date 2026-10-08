package uk.xa0.tulkki.data.sync

import androidx.room.Dao
import androidx.room.Query

/**
 * The per-conversation cursor's reads and writes (S5-3, the `sync/` capability).
 *
 * <p>Its own DAO rather than a third table on [SyncCursorDao]: the two carry different decisions
 * ("Design: synchronisation" §1.2 separates the account's paging anchor from the conversation's own
 * anchor and sweep floor), and §2.5's list of two names for `sync/` does not settle where the
 * `sync_conversation` rows live - this is that home. The statements are the design's.
 *
 * <p>The SQL is spelled here, as in `SyncCursorDao`; `SyncDaoTest` compares the annotations to
 * [SyncQueries] and executes them.
 */
@Dao
internal interface SyncConversationDao {

    /** The conversation's cursor, or `null` when it has none - `anchor_time = 0`, not an error. */
    @Query("SELECT * FROM sync_conversation WHERE conversation_uuid = :conversation")
    fun byConversation(conversation: String): SyncConversationEntity?

    /**
     * The account sweep: every conversation of one account, `conversation_uuid`-ordered so a screen
     * and a test see the same order. This is the query the denormalised `account_uuid` and its index
     * exist for.
     */
    @Query("SELECT * FROM sync_conversation WHERE account_uuid = :account ORDER BY conversation_uuid ASC")
    fun byAccount(account: String): List<SyncConversationEntity>

    /** The rowid of the written row; see `SyncCursorDao.upsert`. */
    @Query(
        "INSERT OR REPLACE INTO sync_conversation " +
            "(conversation_uuid, account_uuid, anchor_stanza_id, anchor_time, archive_first_id, " +
            "swept_through, updated_at) " +
            "VALUES (:conversation, :account, :stanzaId, :time, :archiveFirst, :sweptThrough, " +
            ":updatedAt)"
    )
    fun upsert(
        conversation: String,
        account: String,
        stanzaId: String?,
        time: Long,
        archiveFirst: String?,
        sweptThrough: Long,
        updatedAt: Long,
    ): Long

    /** One column, so the sweep does not rewrite the anchor it is not touching. 1 when it moved. */
    @Query(
        "UPDATE sync_conversation SET swept_through = :sweptThrough, updated_at = :updatedAt " +
            "WHERE conversation_uuid = :conversation"
    )
    fun advanceSweptThrough(conversation: String, sweptThrough: Long, updatedAt: Long): Int
}
