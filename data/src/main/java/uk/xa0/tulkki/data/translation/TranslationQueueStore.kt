package uk.xa0.tulkki.data.translation

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase

/**
 * The queue's rows, on the app's own encrypted database (S5-6): the one door from `:translation` to
 * `translation_queue`.
 *
 * <p>**What this replaces.** `TranslationStore` held `DatabaseBackend.getInstance(context).getWritableDatabase()`
 * and composed every queue statement and every `ContentValues` itself - the class's own comment calls
 * it "the only Tulkki class that knows about SQLite". `docs/MIGRATION.md`, "Design: the data layer" §6
 * step 6 names it among the four direct `getWritableDatabase()` consumers to port, and this is that
 * port's queue slice. The SQL is [TranslationQueueQueries]'s from here on, the cursor walk is this
 * class's, and no `Cursor` and no `SQLiteDatabase` leaves `:data`.
 *
 * <p>**Why the states are parameters.** Which number means "pending" is
 * `TranslationQueue.Item.STATE_PENDING`'s fact, and `:data` may not import `:translation`. Passing it
 * in is what keeps one spelling of it; a `:data` constant would be a second answer that a test could
 * only hope to keep in step.
 *
 * <p>**Why the file's own probe stays.** `failed_at` is schema 73's column, and a file where that
 * migration did not land must lose a failure time rather than the queue: the write names it only when
 * [failedAtPresent] says the column is really there, and the read tolerates its absence by index.
 * Both halves move here with the column, and the answer is cached for the process, because a file's
 * shape does not change under a running process.
 */
class TranslationQueueStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val TAG = "Tulkki"

        /**
         * Whether the file really has the schema-73 column, once it has been asked - `null` until
         * then. One answer serves every store built afterwards in this process.
         */
        @Volatile private var failedAtColumn: Boolean? = null

        @JvmStatic
        fun get(context: Context): TranslationQueueStore =
            TranslationQueueStore(HistoryDatabase.get(context))

        /** Test seam: the probe's answer is per process and a test builds many files. */
        @JvmStatic
        internal fun forgetTheProbe() {
            failedAtColumn = null
        }
    }

    /**
     * A new row, `IGNORE` on conflict: a message that is already queued must not lose its attempt
     * count and its due time to a duplicate insert.
     */
    fun insert(row: TranslationQueueRow) {
        db.insertWithOnConflict(
            TranslationQueueQueries.TABLE,
            null,
            values(row),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** The whole row again, which is how a live writer updates one. */
    fun update(row: TranslationQueueRow) {
        db.update(
            TranslationQueueQueries.TABLE,
            values(row),
            TranslationQueueQueries.MESSAGE_UUID + "=?",
            arrayOf(row.messageUuid),
        )
    }

    /** The one row the pump would take next, or null when nothing in [state] is due. */
    fun nextDue(now: Long, state: Int): TranslationQueueRow? =
        rows(TranslationQueueQueries.NEXT_DUE, arrayOf(state.toString(), now.toString()))
            .firstOrNull()

    /** At most [limit] rows of [state] that are due, oldest first. A non-positive limit reads none. */
    fun due(now: Long, limit: Int, state: Int): List<TranslationQueueRow> {
        if (limit <= 0) {
            return emptyList()
        }
        return rows(
            TranslationQueueQueries.due(limit),
            arrayOf(state.toString(), now.toString()),
        )
    }

    /** How many rows are in one state. */
    fun count(state: Int): Int =
        scalar(TranslationQueueQueries.COUNT_IN_STATE, state)?.toInt() ?: 0

    /** The earliest instant one state is due, or null when it holds no row. */
    fun earliest(state: Int): Long? = scalar(TranslationQueueQueries.EARLIEST_IN_STATE, state)

    /** Every row of one state goes. The caller owns which state that is. */
    fun deleteInState(state: Int) {
        db.execSQL(TranslationQueueQueries.DELETE_IN_STATE, arrayOf<Any>(state))
    }

    /**
     * Revives one message's row for the owner's tap: pending, no attempts, due now, and the target
     * language and cache key rewritten to the ones in force. The row must not already be done.
     */
    fun makeDue(
        messageUuid: String,
        targetLanguage: String?,
        cacheKey: String,
        now: Long,
        pendingState: Int,
        doneState: Int,
    ) {
        val args = arrayOfNulls<Any>(6)
        args[0] = pendingState
        args[1] = now
        args[2] = targetLanguage
        args[3] = cacheKey
        args[4] = messageUuid
        args[5] = doneState
        db.execSQL(TranslationQueueQueries.MAKE_DUE, args)
    }

    /** One message's row goes: `TranslationStore.forget`, when an edit replaced the message's text. */
    fun deleteByMessage(messageUuid: String) {
        db.execSQL(TranslationQueueQueries.DELETE_BY_MESSAGE, arrayOf<Any>(messageUuid))
    }

    /** Whether a row is queued for this message, whatever state it is in. */
    fun hasQueued(messageUuid: String?): Boolean {
        if (messageUuid == null) {
            return false
        }
        db.query(TranslationQueueQueries.HAS_QUEUED, arrayOf<Any>(messageUuid)).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    /**
     * Whether the file has the schema-73 `failed_at` column, asked once per process.
     *
     * <p>The migration catches and logs its `SQLiteException`, so "the upgrade ran" is not "the
     * column landed" - and unlike the schema-71 message columns, this one is on a table Tulkki
     * writes on every enqueue and every failure. Answering it from the table's own metadata keeps a
     * half-applied migration to a lost failure time, which the screen already says, instead of a
     * queue that cannot write at all.
     */
    fun failedAtPresent(): Boolean {
        failedAtColumn?.let {
            return it
        }
        var present = false
        try {
            db.query(TranslationQueueQueries.TABLE, null, null, null, null, null, null, "0")
                .use { cursor ->
                    present = cursor != null && cursor.getColumnIndex(TranslationQueueQueries.FAILED_AT) != -1
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read the translation queue's columns; assuming no failed_at", e)
        }
        failedAtColumn = present
        return present
    }

    // -- the row's two directions ------------------------------------------------------------------

    private fun values(row: TranslationQueueRow): ContentValues {
        val values = ContentValues()
        values.put(TranslationQueueQueries.MESSAGE_UUID, row.messageUuid)
        values.put(TranslationQueueQueries.CONVERSATION_UUID, row.conversationUuid)
        values.put(TranslationQueueQueries.BODY, row.body)
        values.put(TranslationQueueQueries.TARGET_LANGUAGE, row.targetLanguage)
        values.put(TranslationQueueQueries.CACHE_KEY, row.cacheKey)
        values.put(TranslationQueueQueries.STATE, row.state)
        values.put(TranslationQueueQueries.ATTEMPTS, row.attempts)
        values.put(TranslationQueueQueries.NEXT_ATTEMPT_AT, row.nextAttemptAt)
        values.put(TranslationQueueQueries.LAST_ERROR, row.lastError)
        values.put(TranslationQueueQueries.CREATED_AT, row.createdAt)
        values.put(TranslationQueueQueries.FAILURE_CAUSE, row.failureCause)
        if (failedAtPresent()) {
            // Only when the column is really there: a write naming a column that is not would throw,
            // and this is the queue's own row - a migration that did not land must not take the whole
            // translation queue down with a screen's new column.
            values.put(TranslationQueueQueries.FAILED_AT, row.failedAt)
        }
        return values
    }

    private fun rows(statement: String, args: Array<String>): List<TranslationQueueRow> {
        val out = ArrayList<TranslationQueueRow>()
        db.rawQuery(statement, args).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(row(cursor))
            }
        }
        return out
    }

    private fun scalar(statement: String, state: Int): Long? {
        db.rawQuery(statement, arrayOf(state.toString())).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return null
            }
            return cursor.getLong(0)
        }
    }

    /** One row, by name except for the column a file may not have. */
    private fun row(cursor: Cursor): TranslationQueueRow {
        val failedAt = cursor.getColumnIndex(TranslationQueueQueries.FAILED_AT)
        return TranslationQueueRow(
            messageUuid = cursor.getString(index(cursor, TranslationQueueQueries.MESSAGE_UUID)),
            conversationUuid =
                cursor.getString(index(cursor, TranslationQueueQueries.CONVERSATION_UUID)),
            body = cursor.getString(index(cursor, TranslationQueueQueries.BODY)),
            targetLanguage =
                cursor.getString(index(cursor, TranslationQueueQueries.TARGET_LANGUAGE)),
            cacheKey = cursor.getString(index(cursor, TranslationQueueQueries.CACHE_KEY)),
            state = cursor.getInt(index(cursor, TranslationQueueQueries.STATE)),
            attempts = cursor.getInt(index(cursor, TranslationQueueQueries.ATTEMPTS)),
            nextAttemptAt = cursor.getLong(index(cursor, TranslationQueueQueries.NEXT_ATTEMPT_AT)),
            lastError = cursor.getString(index(cursor, TranslationQueueQueries.LAST_ERROR)),
            createdAt = cursor.getLong(index(cursor, TranslationQueueQueries.CREATED_AT)),
            // The index, not OrThrow: a row written before schema 73, and a database where the
            // migration did not land, both mean "no failure time recorded".
            failedAt = if (failedAt < 0 || cursor.isNull(failedAt)) 0L else cursor.getLong(failedAt),
            failureCause =
                cursor.getString(index(cursor, TranslationQueueQueries.FAILURE_CAUSE)),
        )
    }

    private fun index(cursor: Cursor, column: String): Int = cursor.getColumnIndexOrThrow(column)
}
