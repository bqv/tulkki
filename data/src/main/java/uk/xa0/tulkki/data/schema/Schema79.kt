package uk.xa0.tulkki.data.schema

import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation

/**
 * Schema 79: the per-conversation doubt-hold switch.
 *
 * <p>**The column, and why it is nullable.** `doubt_hold` is `INTEGER` and may be `NULL`: `NULL`
 * means *the conversation never chose*, `0` means the hold is off here, `1` means it is explicitly
 * on. That is `language_override`'s shape, and for the same reasons - `NULL` is "not overridden",
 * so no migration has to write a value into rows that never decided, and a future change to the
 * shipped default moves only the conversations that never chose. **The default itself is not here
 * and not in the model**: it is resolved where the rule is consulted (`:translation`), so the
 * column and the model store the tri-state and nothing else.
 *
 * <p>**The two functions, and why they differ.** [applySchema] is the *whole* definition of 79, so
 * the fresh-install path calls it and nothing else; it runs [Schema78.applySchema] and then the
 * guarded `ALTER`, which on a fresh file is a no-op because Room's own `createAllTables` already
 * made the column from `ConversationEntity`. [applyUpgrade] is only what a 78 file is missing -
 * this version's one column - and deliberately does **not** run 78's stale-77 repair: a file that
 * reached 78 was repaired by `MIGRATION_77_78`, and running it twice is not what the shape means.
 *
 * <p>The guard is [SchemaExec.hasColumn], not `IF NOT EXISTS`: SQLite has no
 * `ADD COLUMN IF NOT EXISTS`, and the fresh-install path above is exactly the caller that needs the
 * read - the one statement in this version is the one statement that cannot be made idempotent by
 * its own text.
 */
object Schema79 {

    /** The file's version once this schema is in place. `DatabaseBackend.DATABASE_VERSION` names it. */
    const val VERSION = 79

    /**
     * The one statement 79 adds. Nullable by omission: no `NOT NULL`, so it means "never chose".
     *
     * <p>The column name is the model's own constant, never a literal typed here: `Conversation`
     * already owns `TABLENAME`, and a storage name spelled twice is a name that can drift.
     */
    @JvmField
    val ADD_DOUBT_HOLD_COLUMN =
        "ALTER TABLE " +
            Conversation.TABLENAME +
            " ADD COLUMN " +
            Conversation.DOUBT_HOLD +
            " INTEGER"

    @JvmStatic fun applySchema(db: SupportSQLiteDatabase) = applySchema(SchemaExecs.of(db))

    @JvmStatic fun applySchema(db: SQLiteDatabase) = applySchema(SchemaExecs.of(db))

    /**
     * The whole definition of 79: everything 78 defines, plus this version's own column. The one
     * caller is the fresh-install path, which needs the final shape and not the history of how the
     * file would have got there.
     */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        Schema78.applySchema(exec)
        addDoubtHoldIfAbsent(exec)
    }

    @JvmStatic fun applyUpgrade(db: SupportSQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    @JvmStatic fun applyUpgrade(db: SQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    /** What a 78 file lacks: the guarded column. */
    @JvmStatic
    fun applyUpgrade(exec: SchemaExec) {
        addDoubtHoldIfAbsent(exec)
    }

    private fun addDoubtHoldIfAbsent(exec: SchemaExec) {
        if (!exec.hasColumn(Conversation.TABLENAME, Conversation.DOUBT_HOLD)) {
            exec.exec(ADD_DOUBT_HOLD_COLUMN)
        }
    }
}
