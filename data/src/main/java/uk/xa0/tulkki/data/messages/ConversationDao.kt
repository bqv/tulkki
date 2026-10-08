package uk.xa0.tulkki.data.messages

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The conversation table's DAO (S5-3): one row by key, the account's rows, the list's last-message
 * pointer, and the write trio.
 *
 * <p>Singular for the reason [ConversationQueries]'s comment gives: the design's §2.5 table spells
 * the plural, the naming rule bans it, and the persisted table name is not ours.
 *
 * <p>The SQL is spelled here as in [MessagesDao]; [ConversationQueries] spells it again and
 * `MessagesDaoTest` keeps the two equal. Insert and update are the generator's, from
 * [ConversationEntity], because a whole-row write is the entity's columns either way.
 */
@Dao
internal interface ConversationDao {

    /** One row by its own key, or `null`. */
    @Query("SELECT * FROM conversations WHERE uuid = :uuid")
    fun byUuid(uuid: String): ConversationEntity?

    /** Every row of one account, in a stable order. */
    @Query("SELECT * FROM conversations WHERE accountUuid = :account ORDER BY uuid ASC")
    fun rowsForAccount(account: String): List<ConversationEntity>

    /**
     * The list's own read: one row per conversation of the account **with its last-message pointer
     * already projected**, in one statement, and no body column (`docs/MIGRATION.md`, "Design: the
     * Compose UI" §2.3). S5-6 widens S5-3's narrower pointer projection to the row [ConversationRow]
     * embeds, so the synchronous list and the `Flow` below run the same statement.
     */
    @Query(
        "SELECT c.uuid, c.name, c.contactUuid, c.accountUuid, c.contactJid, c.created, c.status, " +
            "c.mode, c.attributes, c.detected_language, c.language_override, c.doubt_hold, (SELECT m.uuid FROM " +
            "messages m WHERE m.conversationUuid = c.uuid ORDER BY m.timeSent DESC LIMIT 1) AS " +
            "lastMessageId, (SELECT m.timeSent FROM messages m WHERE m.conversationUuid = c.uuid " +
            "ORDER BY m.timeSent DESC LIMIT 1) AS lastMessageAt FROM conversations c " +
            "WHERE c.accountUuid = :account ORDER BY c.uuid ASC"
    )
    fun listForAccount(account: String): List<ConversationRow>

    /** A new row. `IGNORE` so a re-created conversation is not a second one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(conversation: ConversationEntity): Long

    /** The whole row again; the account foreign key's cascade is what deletes rows. */
    @Update fun update(conversation: ConversationEntity)

    /** One row gone, and with it - through the cascade - its messages. */
    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String): Int

    /** One row by its own key as a `Flow`, re-emitted on every change (S5-6). */
    @Query("SELECT * FROM conversations WHERE uuid = :uuid")
    fun watchByUuid(uuid: String): Flow<ConversationEntity?>

    /** The list again as a `Flow`, re-emitted whenever one of the account's rows changes (S5-6). */
    @Query(
        "SELECT c.uuid, c.name, c.contactUuid, c.accountUuid, c.contactJid, c.created, c.status, " +
            "c.mode, c.attributes, c.detected_language, c.language_override, c.doubt_hold, (SELECT m.uuid FROM " +
            "messages m WHERE m.conversationUuid = c.uuid ORDER BY m.timeSent DESC LIMIT 1) AS " +
            "lastMessageId, (SELECT m.timeSent FROM messages m WHERE m.conversationUuid = c.uuid " +
            "ORDER BY m.timeSent DESC LIMIT 1) AS lastMessageAt FROM conversations c " +
            "WHERE c.accountUuid = :account ORDER BY c.uuid ASC"
    )
    fun watchAccount(account: String): Flow<List<ConversationRow>>
}
