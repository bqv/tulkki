package uk.xa0.tulkki.data.expiry

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Message

/**
 * The connection's one column probe, handed to [MessageExpiryStore.expire] rather than widened.
 *
 * <p>`DatabaseBackend.columnExists` belongs to the connection group - the class's own javadoc keeps
 * it there because live readers use it - and `expireOldMessages` is the group's one caller outside
 * that group. Rather than widen the private method's visibility for a capability being moved out,
 * the caller passes it in: `DatabaseBackend` hands `this::columnExists`, so the probe is still
 * evaluated at the same point in the writer (after the deletes, before the `file_deleted` value is
 * set), and no other caller can reach it.
 */
fun interface ColumnLookup {

    /** Whether `tableName` has `columnName`, as `DatabaseBackend.columnExists` answers it. */
    fun exists(db: SQLiteDatabase, tableName: String, columnName: String): Boolean
}

/**
 * `expiry/`'s capability: the automatic message deletion's two statements over `messages`.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag.** Two methods: the writer that
 * deletes everything past the owner's retention date and blanks what is kept, and the read the next
 * alarm is scheduled from. One table (`messages`, no table of its own), one caller class (`:xmpp`'s
 * `XmppConnectionService`). `docs/MIGRATION.md`'s `port-45` row is the inventory this comes out of,
 * and it records this group as the one the previous lane refused: the writer calls the connection
 * group's private `columnExists`, so it does not need "nothing but the opener" - the private helper
 * is handed in as a parameter instead, which is what lets the group move without widening a
 * visibility.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on both.** `DatabaseBackend`'s two
 * methods are now one-line delegations (the "one delegating step"), so `XmppConnectionService`'s two
 * call sites and the `DatabaseBackendRef` signatures compile against byte for byte what they did
 * before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries the
 * un-mangled name - a Kotlin `internal` member would be emitted as `expire$data` and Java could not
 * see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own body.** Both methods take
 * the caller's own non-null `long`; `nextExpiration` answers a real `long` and `expire` nothing. The
 * `ColumnLookup` is the caller's method reference, never null. The two `(String) null` puts are the
 * Java's own - "blank the body and the subject" - and stay `null as String?` so the `String`
 * overload is the one chosen.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original two declared none and `XmppConnectionService` catches nothing around them. The
 * transaction is opened, marked successful and closed exactly as the Java did it - no `try`/`finally`
 * is added, because the Java had none and the move's job is to preserve it.
 */
object MessageExpiryStore {

    /**
     * Everything older than the retention date, gone; what is kept has its body, subject, relative
     * path and reactions blanked, its encryption reset, and its `deleted` flag set. The
     * `file_deleted` column is written only where the file has it, which is what the handed-in probe
     * answers.
     *
     * @param timestamp the retention date, as `XmppConnectionService.getAutomaticMessageDeletionDate()`
     *     answered it
     * @param columnExists the connection's own probe, `DatabaseBackend::columnExists`
     */
    @JvmStatic
    fun expire(db: SQLiteDatabase, timestamp: Long, columnExists: ColumnLookup) {
        val args = arrayOf(timestamp.toString())
        db.beginTransaction()
        db.delete(Message.TABLENAME, Message.TIME_SENT + "<?", args)
        db.delete(Message.TABLENAME, Message.TIME_RECEIVED + "<?", args)

        val values = ContentValues()
        values.put(Message.BODY, null as String?)
        values.put(Message.SUBJECT, null as String?)
        values.put(Message.DELETED, 1)
        if (columnExists.exists(db, Message.TABLENAME, "file_deleted")) {
            values.put("file_deleted", 1)
        }
        values.put(Message.RELATIVE_FILE_PATH, null as String?)
        values.put(Message.ENCRYPTION, Message.ENCRYPTION_NONE)
        values.put(Message.REACTIONS, null as String?)

        db.update(
            Message.TABLENAME,
            values,
            Message.EXPIRE_AT + " > 0 AND " + Message.EXPIRE_AT + " < ?",
            arrayOf(System.currentTimeMillis().toString()),
        )
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    /**
     * The earliest expiry still in the future, or `0` when nothing is set to expire: the alarm the
     * service schedules from.
     */
    @JvmStatic
    fun nextExpiration(db: SQLiteDatabase): Long {
        val query =
            "SELECT MIN(" +
                Message.EXPIRE_AT +
                ") FROM " +
                Message.TABLENAME +
                " WHERE " +
                Message.EXPIRE_AT +
                " > 0"
        db.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 0
    }
}
