package uk.xa0.tulkki.data.markers

import androidx.room.Dao
import androidx.room.Query

/**
 * `markers/`'s own DAO (S5-3): the read flag, the markable flag and the marker JSON, one column at a
 * time.
 *
 * <p>Every statement is a targeted `UPDATE` or a single-column `SELECT`; [MarkerQueries] says why
 * that is a decision rather than a style. There is no `@Insert` and no `@Update` here for the same
 * reason: a row's existence is `messages/`'s, and this package only ever moves three of its columns.
 *
 * <p>The SQL is spelled here and again in [MarkerQueries]; `MarkerDaoTest` keeps the two equal.
 */
@Dao
internal interface MarkerDao {

    /** One row's `read` flag. */
    @Query("UPDATE messages SET read = :read WHERE uuid = :uuid")
    fun markRead(uuid: String, read: Long): Int

    /** One row's `markable` flag. */
    @Query("UPDATE messages SET markable = :markable WHERE uuid = :uuid")
    fun markMarkable(uuid: String, markable: Long): Int

    /** The JSON array of marker senders, or `null` when none has been recorded. */
    @Query("SELECT readByMarkers FROM messages WHERE uuid = :uuid")
    fun readByMarkersOf(uuid: String): String?

    /** The JSON array again, written whole. */
    @Query("UPDATE messages SET readByMarkers = :markers WHERE uuid = :uuid")
    fun setReadByMarkers(uuid: String, markers: String?): Int
}
