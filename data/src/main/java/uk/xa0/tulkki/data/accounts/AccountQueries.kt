package uk.xa0.tulkki.data.accounts

import uk.xa0.tulkki.data.model.Account

/**
 * `accounts/`'s schema and every statement the package publishes (S5-3, the `accounts/`
 * capability).
 *
 * <p>One table, and its DDL was `DatabaseBackend.onCreate`'s until this commit. The entity Room
 * validates is what a fresh install gets and `Schema76`'s rebuild is what an upgrade gets, so the
 * two cannot spell two `accounts` tables; the 75 shape is the migration's and the host fixture's own
 * `ACCOUNTS` literal. The rebuilt shape - `options` and `port` normalised from `NUMBER` to `INTEGER`
 * - stays with `Schema76`, because a create-copy-drop-rename is the migration's own name-for-name
 * copy and nothing about it changes here: the table's version does not move, so neither does the
 * schema.
 *
 * <p>`AccountEntity` is the storage shape and `uk.xa0.tulkki.data.model.Account` stays the type the
 * islands speak (`docs/MIGRATION.md`, "Design: the data layer" §2.3).
 */
internal object AccountQueries {

    const val TABLE = Account.TABLENAME
    const val UUID = Account.UUID
    const val ORDERING = Account.ORDERING

    // -- the statements the DAO publishes ----------------------------------------------------------

    /** One account by its own key. */
    const val BY_UUID = "SELECT * FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /**
     * Every account, in the order the owner arranged them. `DatabaseBackend.getAccounts` orders by
     * `ordering` and nothing else, and an account with no ordering sorts first because SQLite reads
     * NULL as smallest - kept rather than tidied, so a read here answers what the live one answers.
     */
    const val ALL_ACCOUNTS = "SELECT * FROM " + TABLE + " ORDER BY " + ORDERING + " ASC"

    /** One account gone; every child row follows through the schema's own cascades. */
    const val DELETE_BY_UUID = "DELETE FROM " + TABLE + " WHERE " + UUID + " = :uuid"
}
