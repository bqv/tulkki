package uk.xa0.tulkki.data.presence

import uk.xa0.tulkki.data.model.AbstractEntity
import uk.xa0.tulkki.data.model.PresenceTemplate

/**
 * `presence/`'s schema and every statement the package publishes (S5-3, the `presence/`
 * capability).
 *
 * <p>One table, `presence_templates`: the owner's saved status lines, at most nine of them, ordered
 * by when they were last used. Its `CREATE` was `DatabaseBackend`'s until this commit; the table's
 * 75 spelling is `Schema75Fixture.PRESENCE_TEMPLATES`'s now - the host fixture is what drives the
 * legacy file these paths are proved against - and the rebuilt shape - `last_used` normalised from
 * `NUMBER`, and the key the file never had - is `Schema77`'s, on the same terms as `roster/`'s two
 * tables.
 *
 * <p>**The three statements the live writer's body is made of, kept whole.** `insertPresenceTemplate`
 * deletes the row with the same message, trims the table to its nine newest rows, then inserts; a
 * package that published only the insert would lose the trim, and the trim is what keeps the picker
 * to nine. `TRIM_TO_NEWEST` is that statement with its bound as a parameter, so the number is the
 * caller's and not a second spelling of nine.
 */
internal object PresenceQueries {

    const val TABLE = PresenceTemplate.TABELNAME
    const val UUID = AbstractEntity.UUID
    const val LAST_USED = PresenceTemplate.LAST_USED
    const val MESSAGE = PresenceTemplate.MESSAGE
    const val STATUS = PresenceTemplate.STATUS

    // -- the statements the DAO publishes ----------------------------------------------------------

    /** Every saved status line, most recently used first: the picker's own read. */
    const val ALL = "SELECT * FROM " + TABLE + " ORDER BY " + LAST_USED + " DESC"

    /** One line by the pair the table's own `UNIQUE` names. */
    const val BY_MESSAGE_AND_STATUS =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            MESSAGE +
            " = :message AND " +
            STATUS +
            " = :status"

    /** The upsert: one row per `(message, status)`, replaced rather than duplicated. */
    const val UPSERT =
        "INSERT OR REPLACE INTO " +
            TABLE +
            " (" +
            UUID +
            ", " +
            LAST_USED +
            ", " +
            MESSAGE +
            ", " +
            STATUS +
            ") VALUES (:uuid, :lastUsed, :message, :status)"

    /** Every line with one message, which is the first half of the live writer's body. */
    const val REMOVE_BY_MESSAGE = "DELETE FROM " + TABLE + " WHERE " + MESSAGE + " = :message"

    /** The second half: keep the nine newest rows, drop the rest. */
    const val TRIM_TO_NEWEST =
        "DELETE FROM " +
            TABLE +
            " WHERE " +
            UUID +
            " NOT IN (SELECT " +
            UUID +
            " FROM " +
            TABLE +
            " ORDER BY " +
            LAST_USED +
            " DESC LIMIT :keep)"
}
