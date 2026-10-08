package uk.xa0.tulkki.data.markers

import uk.xa0.tulkki.data.model.Message

/**
 * `markers/`'s statements (S5-3, the `markers/` capability).
 *
 * <p>This package owns **columns of `messages`, not a table** (`docs/MIGRATION.md`, "Design: the
 * data layer" §2.3): `read`, `markable` and `readByMarkers`. It therefore has no entity and no DDL of
 * its own - `messages/` owns the `CREATE`, and these three columns have been in it since schema 71
 * and earlier - and what it publishes is the four statements that flip and read them.
 *
 * <p>**Why the statements are targeted `UPDATE`s rather than whole-row writes.** A marker flip is
 * the one write in the message path that must not restate the 39 columns it is not touching: it runs
 * on the MUC-marker fan-out, once per marker per conversation, and a whole-row write there would
 * rewrite the body, the translation state and the transfer columns from a cursor that may be
 * minutes old. Each statement moves exactly its own column, which is also why `markers/` does not
 * reuse `MessagesDao.update`.
 */
internal object MarkerQueries {

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID
    const val READ = Message.READ
    const val MARKABLE = Message.MARKABLE
    const val READ_BY_MARKERS = Message.READ_BY_MARKERS

    /** One row's `read` flag. */
    const val MARK_READ =
        "UPDATE " + TABLE + " SET " + READ + " = :read WHERE " + UUID + " = :uuid"

    /** One row's `markable` flag: whether the sender allows a read marker to be sent back. */
    const val MARK_MARKABLE =
        "UPDATE " + TABLE + " SET " + MARKABLE + " = :markable WHERE " + UUID + " = :uuid"

    /** The JSON array of marker senders, or `null` when none has been recorded. */
    const val READ_BY_MARKERS_OF =
        "SELECT " + READ_BY_MARKERS + " FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** The JSON array again, written whole: the marker list is one value, not a row per sender. */
    const val SET_READ_BY_MARKERS =
        "UPDATE " + TABLE + " SET " + READ_BY_MARKERS + " = :markers WHERE " + UUID + " = :uuid"
}
