package uk.xa0.tulkki.data.backup

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.omemo.OmemoQueries

/**
 * The import's write surface (S5-6): the one door from a backup file's rows into the file.
 *
 * <p>**What this replaces.** `ImportBackupWorker` held `DatabaseBackend.getWritableDatabase()`,
 * composed the transaction, checked a table allow-list it declared itself, and called
 * `insertWithOnConflict` on whatever table the *file* named. `docs/MIGRATION.md`, "Design: the data
 * layer" §6 step 6 names this worker among the four direct `getWritableDatabase()` consumers to
 * port. The worker now names the row and this decides what may be written where.
 *
 * <p>**Why a `ContentValues` and not a `BackupRow`.** The export's read model answers strings - it
 * reads the file - while the import's input is the *parsed JSON*, which distinguishes a number from
 * a string and must keep that distinction (`options` is written as a JSON number, every other column
 * as text). `ContentValues` is exactly that: a by-name row of typed values, which is the file's own
 * `values` object and nothing more. It is not a handle and not a statement: the table name, the
 * conflict rule, the allow-list and the OMEMO rule are all decided here, and a caller cannot insert
 * into a table this object does not accept.
 *
 * <p>**The OMEMO rule is the one branch.** `includeOmemo = false` restores everything except the
 * key material: `prekeys`, `signed_prekeys` and `sessions` are skipped outright, and `identities`
 * keeps only the rows whose `ownkey` is `0` - the peers' identities, without the account's own. The
 * legacy code read `ownkey` with `getAsInteger(...) == 0`, which unboxes and throws on a row that
 * has no `ownkey` at all; a missing column there means the row is not the account's own, so this
 * skips it rather than failing the whole restore on one malformed row.
 */
class BackupImport private constructor(private val db: SQLiteDatabase) {

    /**
     * Whether the app can restore into the table a backup file names. The file carrier (`files`) is
     * not here: it is not a table, and the worker that owns the file format writes those rows itself.
     */
    fun accepts(table: String): Boolean = BackupQueries.TABLES.contains(table)

    /**
     * One row of one table, `IGNORE` on conflict - the restore must not overwrite what the app has
     * already written - answering the new row's id, or `-1` when the row was a duplicate and `0` when
     * the OMEMO rule skipped it. It is the caller's to log; nothing here decides what to say.
     */
    fun insert(table: String, values: ContentValues, includeOmemo: Boolean): Long {
        if (!includeOmemo && BackupQueries.OMEMO_TABLES.contains(table)) {
            val own = values.getAsInteger(OmemoQueries.OWN) ?: -1
            if (table != OmemoQueries.IDENTITIES_TABLE || own != 0) {
                return 0L
            }
        }
        return db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * The account's restored message count, by the header's own names. The restore reads it once, at
     * the end, for its one log line.
     */
    fun restoredMessageCount(local: String, server: String): Int =
        db.query(BackupQueries.RESTORED_MESSAGE_COUNT, arrayOf<Any>(local, server)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /**
     * The whole file's walk is one transaction: a restore that is stopped, or that throws, leaves the
     * file as it was rather than half of somebody's history. The three calls are `SQLiteDatabase`'s
     * own, in the order it requires - [end] without [setSuccessful] is a rollback.
     */
    fun begin() = db.beginTransaction()

    /** Marks the walk complete; the next [end] commits rather than rolls back. */
    fun setSuccessful() = db.setTransactionSuccessful()

    /** Ends the transaction, committing only if [setSuccessful] ran. */
    fun end() = db.endTransaction()

    companion object {

        /**
         * Over the one open database. This is also what *opens* it, so the worker reaches the same
         * file the app has - Room's version check and migrations included - rather than a connection
         * of its own. It throws `SQLiteNotADatabaseException` for a file whose key does not match,
         * which the worker catches to delete and re-create the file before importing.
         */
        @JvmStatic fun get(context: Context): BackupImport = BackupImport(HistoryDatabase.get(context))
    }
}
