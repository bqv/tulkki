package uk.xa0.tulkki.data.schema

import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.TranslationTables

/**
 * Schema 80: item 17's per-row failure cause on the translation queue.
 *
 * <p>**The column, and why it is nullable.** `failure_cause` is `TEXT` and may be `NULL`. `NULL`
 * means *no cause recorded*, and that is deliberately two situations at once: a row that was never
 * blocked, and one stopped by an ordinary retryable API failure - the queue's own
 * `attempts`/`next_attempt_at` axis already carries that one, so the new column must not claim it.
 * The stored values are the cause vocabulary as strings, and it is a settled set of five:
 * `no_key`, `rejected_key`, `no_credit` and `cap_reached`, which the app can clear, and
 * `check_refused`, the local check's own refusal, which it never clears automatically. There is
 * deliberately **no spelling for an ordinary API failure** - the retryable axis above owns it, which
 * is what `NULL` means - and no `unreachable` value either: a network failure is that same retryable
 * axis, not a cause the app could clear. The enum behind those strings lives with the consumer in
 * `:translation`; storage holds the string and no rule.
 *
 * <p>**The two functions, and why they differ.** [applySchema] is the *whole* definition of 80, so
 * the fresh-install path calls it and nothing else; it runs [Schema79.applySchema] and then the
 * guarded `ALTER`, which on a fresh file is a no-op because Room's own `createAllTables` already
 * made the column from `TranslationQueueEntity`. [applyUpgrade] is only what a 79 file is missing -
 * this version's one column - and deliberately does **not** re-run 79 or earlier. The guard is
 * [SchemaExec.hasColumn], not `IF NOT EXISTS`: SQLite has no `ADD COLUMN IF NOT EXISTS`, and the
 * fresh-install path is exactly the caller that needs the read.
 */
object Schema80 {

    /** The file's version once this schema is in place. `DatabaseBackend.DATABASE_VERSION` names it. */
    const val VERSION = 80

    /**
     * The one statement 80 adds. Nullable by omission: no `NOT NULL`, so `NULL` can mean "no cause".
     *
     * <p>The column name is `:data`'s own constant (`TranslationTables`), never a literal typed
     * here: the table's name is spelled once, where the table is declared.
     */
    @JvmField
    val ADD_FAILURE_CAUSE_COLUMN =
        "ALTER TABLE " +
            TranslationTables.QUEUE_TABLE +
            " ADD COLUMN " +
            TranslationTables.QUEUE_FAILURE_CAUSE +
            " TEXT"

    @JvmStatic fun applySchema(db: SupportSQLiteDatabase) = applySchema(SchemaExecs.of(db))

    @JvmStatic fun applySchema(db: SQLiteDatabase) = applySchema(SchemaExecs.of(db))

    /**
     * The whole definition of 80: everything 79 defines, plus this version's own column. The one
     * caller is the fresh-install path, which needs the final shape and not the history of how the
     * file would have got there.
     */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        Schema79.applySchema(exec)
        addFailureCauseIfAbsent(exec)
    }

    @JvmStatic fun applyUpgrade(db: SupportSQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    @JvmStatic fun applyUpgrade(db: SQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    /** What a 79 file lacks: the guarded column. */
    @JvmStatic
    fun applyUpgrade(exec: SchemaExec) {
        addFailureCauseIfAbsent(exec)
    }

    private fun addFailureCauseIfAbsent(exec: SchemaExec) {
        if (!exec.hasColumn(TranslationTables.QUEUE_TABLE, TranslationTables.QUEUE_FAILURE_CAUSE)) {
            exec.exec(ADD_FAILURE_CAUSE_COLUMN)
        }
    }
}
