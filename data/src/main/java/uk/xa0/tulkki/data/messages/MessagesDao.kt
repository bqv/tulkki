package uk.xa0.tulkki.data.messages

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * `messages/`'s own DAO (S5-3): the reading order, one row by key, the insert/update/delete trio,
 * and the search read.
 *
 * <p>**The SQL is spelled here, and [MessagesQueries] spells it again.** That duplication is the
 * instrument: `MessagesDaoTest` reads these annotations and asserts they are exactly those
 * constants, so a statement that drifts on either side is a red test rather than a query Room runs
 * and no one read. Naming the constant from the annotation would compile to the same SQL and hide
 * the statement from a later reader (`SyncQueries`'s comment carries the same rule).
 *
 * <p>**The search read is a `@RawQuery`, and it has to be.** It matches `messages_index`, an FTS4
 * table Room does not declare, and Room's compile-time verifier rejects a `@Query` that names a
 * table in no entity list - recorded in [MessagesQueries] with the compiler's own words. So the
 * statement handed to [search] is `DatabaseBackend.buildMessageSearchQuery`'s, built from the live
 * term and its match string; the package owns the read and does not restate the SQL.
 */
@Dao
internal interface MessagesDao {

    /** The conversation's rows in reading order. */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversation " +
            "ORDER BY timeSent ASC"
    )
    fun byConversation(conversation: String): List<MessageEntity>

    /** One row by its own key, or `null`. */
    @Query("SELECT * FROM messages WHERE uuid = :uuid")
    fun byUuid(uuid: String): MessageEntity?

    /** A new row. `IGNORE` because the engine may hand the same stanza over twice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: MessageEntity): Long

    /** The whole row again, which is how every live writer updates one. */
    @Update fun update(message: MessageEntity)

    /** One row gone; the conversation's cascade is the foreign key's, not this statement's. */
    @Query("DELETE FROM messages WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String): Int

    /**
     * The conversation's rows as a `Flow`, re-emitted whenever one of them changes (S5-6). The
     * statement is [byConversation]'s and `MessagesDaoTest` keeps the two spellings equal; what is
     * new is the return type, Room's coroutine read, which is what lets a screen collect a read
     * model rather than poll a cursor (`docs/MIGRATION.md`, "Design: the data layer" §2.7: "`Flow`s,
     * never `LiveData`, never a `Cursor`").
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversation " +
            "ORDER BY timeSent ASC"
    )
    fun watchByConversation(conversation: String): Flow<List<MessageEntity>>

    /**
     * The search read. `DatabaseBackend.buildMessageSearchQuery(term, conversationUuid)` builds the
     * statement and its arguments; it matches `translated_body`, so a covered row has nothing to
     * match and no result can leak an original.
     */
    @RawQuery
    fun search(query: SupportSQLiteQuery): List<String>

    /**
     * The rows a set of ids names, in one statement (S5-6): the search read's second half, so the
     * ported consumer fetches its whole result set with one query rather than one per hit. Room
     * expands the list into as many placeholders as it holds; the caller restores the order it asked
     * for, because `IN` does not promise one.
     */
    @Query("SELECT * FROM messages WHERE uuid IN (:uuids)")
    fun byUuids(uuids: List<String>): List<MessageEntity>
}
