package uk.xa0.tulkki.data.updb

import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.SchemaExec
import uk.xa0.tulkki.data.schema.SchemaExecs

/**
 * The one write the UnifiedPush file needs **before Room opens it** (S5-3).
 *
 * <p>It is the shape `docs/MIGRATION.md`, "Design: the data layer" §3.2 and §6 step 5 give the
 * legacy handover: a sequential preflight that runs the legacy-side repair, inside one transaction
 * on one connection, and only then lets the owner open the file. `UnifiedPushDatabase.getInstance`
 * calls [adopt] before it builds the Room database, and the two derive their key once and share it.
 *
 * <p><strong>Why a preflight rather than a `Migration`.</strong> The file is at
 * `user_version = 1` and the `@Database` is at version 1, so Room has no upgrade to run: it opens
 * the file, finds no `room_master_table`, and runs `onValidateSchema` against the declared entity.
 * The `push` table's `expiration` is declared `NUMBER`, `SchemaInfoUtil.findAffinity` has no numeric
 * member, so the file's column normalises to `UNDEFINED` while the entity's is `INTEGER` - and
 * `TableInfo.Column.equalsCommon` compares affinity unconditionally as its last term. The result is
 * not a repair, it is *"Pre-packaged database has an invalid schema"*: a refused open, on the file
 * whose warm-up runs at every `XmppConnectionService` start. No `ALTER` changes a declared type, so
 * the repair is the create-copy-drop-rename `Schema76.rebuild` already performs for the history
 * tables - the surrogate `_id` filled from the legacy `rowid`, every column given its true affinity,
 * and **no row added, removed or made NOT NULL**.
 *
 * <p><strong>Idempotent, and a no-op on a fresh install.</strong> [rebuildIfLegacy] does nothing
 * when a `room_master_table` is already present (Room has adopted the file), when there is no `push`
 * table at all (nothing to adopt), and when `push` already carries `_id` (a run that rebuilt the
 * file but was killed before Room wrote its identity - rebuilding a rebuilt table would be
 * content-preserving, and this says so rather than relying on it). [adopt] does nothing at all when
 * the file does not exist or is empty, which is what a fresh install looks like before Room creates
 * it: it returns before SQLCipher is asked to open anything.
 *
 * <p><strong>What it cannot check from here.</strong> `UnifiedPushDatabase.migrate` may re-encrypt
 * this file, and `encryptLegacyPlaintextDatabase` may lift a pre-encryption file into SQLCipher;
 * both run before this preflight in `getInstance`, so what this opens is always a keyed file. The
 * JVM harness drives [rebuildIfLegacy] over a plain JDBC connection, because SQLCipher ships
 * Android ABIs only (`docs/MIGRATION.md`, "Design: the data layer" §4.4).
 *
 * <p>It is a public object, like `Schema76`/`Schema77` beside it and for the same reason: the JVM
 * tests drive its own function, and it holds no state to leak. It is not part of the module's
 * public *API* - nothing outside this package calls it.
 */
object LegacyPreflight {

    /** Room's own bookkeeping table: its presence is what "already adopted" means. */
    const val ROOM_MASTER_TABLE = "room_master_table"

    /** Whether there is a file worth opening at all. A fresh install has none. */
    @JvmStatic
    fun hasLegacyFile(file: File): Boolean = file.isFile && file.length() > 0L

    /**
     * The production entry point: opens the file with the key the Room database will be given,
     * rebuilds inside one transaction, and closes it again. Nothing is left half-rebuilt, because
     * the rebuild *is* the transaction.
     */
    @JvmStatic
    fun adopt(file: File, keyBytes: ByteArray) {
        if (!hasLegacyFile(file)) {
            return
        }
        val db =
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                keyBytes,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                DatabaseBackend.ARGON2_DATABASE_HOOK,
            )
        try {
            db.beginTransaction()
            try {
                if (rebuildIfLegacy(SchemaExecs.of(db))) {
                    db.setTransactionSuccessful()
                }
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /**
     * The rebuild itself, over the seam the JVM harness can implement. True when the file was
     * rebuilt, false when there was nothing to do - the caller commits only for a true.
     */
    @JvmStatic
    fun rebuildIfLegacy(exec: SchemaExec): Boolean {
        if (hasTable(exec, ROOM_MASTER_TABLE)) {
            return false
        }
        if (!hasTable(exec, UpdbQueries.TABLE)) {
            return false
        }
        if (exec.hasColumn(UpdbQueries.TABLE, UpdbQueries.ID)) {
            return false
        }
        Schema76.rebuild(
            exec,
            UpdbQueries.TABLE,
            UpdbQueries.COLUMNS,
            UpdbQueries.TAIL,
            rowidColumn = UpdbQueries.ID,
        )
        exec.exec(UpdbQueries.UNIQUE_INSTANCE_INDEX)
        return true
    }

    private fun hasTable(exec: SchemaExec, name: String): Boolean =
        exec.rows(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).isNotEmpty()
}
