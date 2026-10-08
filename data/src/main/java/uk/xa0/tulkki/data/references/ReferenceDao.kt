package uk.xa0.tulkki.data.references

import androidx.room.Dao
import androidx.room.Query

/**
 * `references/`'s own DAO (S5-3): the payload document of one message, and the lookup a reply falls
 * back to.
 *
 * <p>No `@Insert`: a row's existence is `messages/`'s. The three statements are all `@Query`, and
 * `ReferenceDaoTest` compares them against [ReferenceQueries] so neither spelling can drift.
 */
@Dao
internal interface ReferenceDao {

    /** The row's payload document, or `null` when the row carries none. */
    @Query("SELECT payloads FROM messages WHERE uuid = :uuid")
    fun payloadsOf(uuid: String): String?

    /** The document again, written whole. */
    @Query("UPDATE messages SET payloads = :payloads WHERE uuid = :uuid")
    fun setPayloads(uuid: String, payloads: String?): Int

    /** The newest row of the conversation that any of the three ids names, or `null`. */
    @Query(
        "SELECT uuid FROM messages WHERE conversationUuid = :conversation AND " +
            "(serverMsgId = :messageId OR remoteMsgId = :messageId OR uuid = :messageId) " +
            "ORDER BY timeSent DESC LIMIT 1"
    )
    fun quoteFallback(conversation: String, messageId: String): String?
}
