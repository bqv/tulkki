package uk.xa0.tulkki.data.reactions

import androidx.room.Dao
import androidx.room.Query

/**
 * `reactions/`'s own DAO (S5-3): the reaction document of one message, read and replaced whole.
 *
 * <p>Two statements, and [ReactionQueries] says why there is no third: the column is one JSON
 * document, so a reactor's toggle is a replace and not a row. There is no `@Insert` because a row's
 * existence is `messages/`'s.
 *
 * <p>The SQL is spelled here and again in [ReactionQueries]; `ReactionDaoTest` keeps the two equal.
 */
@Dao
internal interface ReactionDao {

    /** The row's reaction document, or `null` when nobody has reacted. */
    @Query("SELECT reactions FROM messages WHERE uuid = :uuid")
    fun reactionsOf(uuid: String): String?

    /** The document again, written whole. */
    @Query("UPDATE messages SET reactions = :reactions WHERE uuid = :uuid")
    fun setReactions(uuid: String, reactions: String?): Int
}
