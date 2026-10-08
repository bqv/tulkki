package uk.xa0.tulkki.data.presence

import androidx.room.Dao
import androidx.room.Query

/**
 * `presence/`'s own DAO (S5-3): the saved status lines, their upsert, and the two removals the live
 * writer's body is made of.
 *
 * <p>There is no `@Insert` here and that is deliberate: a whole-row write cannot express the
 * `(message, status)` conflict the table deduplicates on without an index the entity cannot declare
 * (Room's index read skips SQLite's `sqlite_autoindex_%`), so the upsert is spelled and
 * `PresenceTemplateDaoTest` executes it against the table's own `UNIQUE`.
 *
 * <p>The SQL is spelled here and again in [PresenceQueries]; the test keeps the two equal.
 */
@Dao
internal interface PresenceTemplateDao {

    /** Every saved status line, most recently used first. */
    @Query("SELECT * FROM presence_templates ORDER BY last_used DESC")
    fun all(): List<PresenceTemplateEntity>

    /** One line by the pair the table's own `UNIQUE` names. */
    @Query("SELECT * FROM presence_templates WHERE message = :message AND status = :status")
    fun byMessageAndStatus(message: String?, status: String?): PresenceTemplateEntity?

    /** The upsert: one row per pair, replaced rather than duplicated. */
    @Query(
        "INSERT OR REPLACE INTO presence_templates (uuid, last_used, message, status) " +
            "VALUES (:uuid, :lastUsed, :message, :status)"
    )
    fun upsert(uuid: String?, lastUsed: Long?, message: String?, status: String?): Long

    /** Every line with one message. */
    @Query("DELETE FROM presence_templates WHERE message = :message")
    fun removeByMessage(message: String?): Int

    /** Keep the newest `:keep` rows, drop the rest. */
    @Query(
        "DELETE FROM presence_templates WHERE uuid NOT IN " +
            "(SELECT uuid FROM presence_templates ORDER BY last_used DESC LIMIT :keep)"
    )
    fun trimToNewest(keep: Int): Int
}
