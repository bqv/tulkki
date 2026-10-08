package uk.xa0.tulkki.data.blocking

import androidx.room.Dao
import androidx.room.Query

/**
 * The blocklist's writes and reads (S5-3, the `blocking/` capability).
 *
 * <p>**The SQL is spelled here and nowhere else, and the test reads it back off these annotations.**
 * The tidy alternative - a `const val` per statement in [BlockingQueries], named by the annotation -
 * compiles to the same SQL but is not what is written, because a later reader must be able to see
 * the query Room will run without opening another file. `BlockingDaoTest` asserts by reflection that
 * each annotation equals its [BlockingQueries] constant and then executes the annotation's own SQL
 * over the JDBC fixture, so a drift between the two is a red test rather than a surprise.
 *
 * <p>**Two things about registration, both measured.** A `daos = [...]` entry alone generates nothing:
 * Room only processes a DAO the database exposes, so the accessor (`HistoryDatabase.blockingDao()`)
 * is what makes this interface real - without it the `@Query`s below are inert and the compile is
 * silent. And an INSERT `@Query` may return `void` or `long` and nothing else: Room rejects `int`
 * with "INSERT query functions must either return void or long (the rowid of the inserted row)".
 *
 * <p>`block` is `INSERT OR IGNORE` rather than `@Insert`: blocking a JID twice is a no-op the owner
 * may well cause (two devices, a re-tap), and the answer says which happened.
 *
 * <p>`internal`, like every DAO: the module's public surface is the read models (`docs/MIGRATION.md`,
 * "Design: the data layer" §2.5).
 */
@Dao
internal interface BlockingDao {

    /** The account's blocklist, JID-ordered so a screen and a test see the same order. */
    @Query("SELECT jid FROM blocked_jids WHERE account_uuid = :account ORDER BY jid ASC")
    fun blockedJids(account: String): List<String>

    /** 1 when the pair is blocked, 0 otherwise - the existence question `isBlocked` asks. */
    @Query("SELECT COUNT(*) FROM blocked_jids WHERE account_uuid = :account AND jid = :jid")
    fun count(account: String, jid: String): Int

    /**
     * The rowid of the inserted row. Room accepts `void` or `long` for an INSERT and rejects
     * anything else ("INSERT query functions must either return void or long"), measured rather than
     * assumed; `-1` is SQLite's answer when `OR IGNORE` ignored the insert, which is how a caller
     * tells "blocked now" from "was already blocked".
     */
    @Query("INSERT OR IGNORE INTO blocked_jids (account_uuid, jid) VALUES (:account, :jid)")
    fun block(account: String, jid: String): Long

    /** 1 when a row was removed, 0 when there was nothing to remove. */
    @Query("DELETE FROM blocked_jids WHERE account_uuid = :account AND jid = :jid")
    fun unblock(account: String, jid: String): Int
}
