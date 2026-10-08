package uk.xa0.tulkki.data.reactions

import uk.xa0.tulkki.data.model.Message

/**
 * `reactions/`'s statements (S5-3, the `reactions/` capability).
 *
 * <p>This package owns **one column of `messages`, not a table** (`docs/MIGRATION.md`, "Design: the
 * data layer" §2.3): `reactions`, the JSON the reaction row is drawn from. It has no entity and no
 * DDL - `messages/` owns the `CREATE`, and the column has been in it since schema 68 and earlier -
 * so what it publishes is the pair that reads and replaces it.
 *
 * <p>**The whole JSON is the unit, and that is a decision.** The database holds no row per reaction:
 * the column is one document, the model parses it into a list and writes the list back, and a
 * reactor's change toggles the document rather than a row. So there is no `INSERT` here and no
 * per-reactor `UPDATE`; a statement that appended would be a second spelling of the document's
 * meaning, and the first writer that disagreed with the model would produce a column the model
 * cannot parse.
 */
internal object ReactionQueries {

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID
    const val REACTIONS = Message.REACTIONS

    /** The row's reaction document, or `null` when nobody has reacted. */
    const val REACTIONS_OF =
        "SELECT " + REACTIONS + " FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** The document again, written whole: a toggle rewrites it, it does not edit it in place. */
    const val SET_REACTIONS =
        "UPDATE " + TABLE + " SET " + REACTIONS + " = :reactions WHERE " + UUID + " = :uuid"
}
