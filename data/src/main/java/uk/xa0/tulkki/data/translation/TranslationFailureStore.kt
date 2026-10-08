package uk.xa0.tulkki.data.translation

import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.model.Message

/**
 * The failures screen's rows, on the app's own encrypted database (S5-6): the one door from
 * `:translation` to the two projections behind the list.
 *
 * <p>**What this replaces.** `TranslationStore` held
 * `DatabaseBackend.getInstance(context).getWritableDatabase()` and walked both cursors itself, reading
 * each column by `TranslationTables`' and the models' own names. The SQL is
 * [TranslationFailureQueries]'s from here on, the cursor walk is this class's, and no `Cursor` and no
 * `SQLiteDatabase` leaves `:data`.
 *
 * <p>**What the state numbers are.** The send read's two binds are the caller's, exactly as the
 * queue's states are: which number means "failed" and which means "received" are `:translation`'s
 * facts, and `:data` may not import them.
 *
 * <p>The failure time is read tolerantly, by index rather than by name: a database where the schema-73
 * migration did not land has no failure time, and that is the same fact as a row written before it -
 * neither may stop the screen from listing the failure itself.
 */
class TranslationFailureStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        @JvmStatic
        fun get(context: Context): TranslationFailureStore =
            TranslationFailureStore(HistoryDatabase.get(context))
    }

    /** The received messages that failed: the queue's own rows, newest-first order not withstanding. */
    fun queueFailures(): List<QueueFailureRow> {
        val rows = ArrayList<QueueFailureRow>()
        db.rawQuery(TranslationFailureQueries.QUEUE_FAILURES, null).use { cursor ->
            while (cursor.moveToNext()) {
                val failedAt = cursor.getColumnIndex(TranslationTables.QUEUE_FAILED_AT)
                rows.add(
                    QueueFailureRow(
                        messageUuid =
                            cursor.getString(
                                index(cursor, TranslationTables.QUEUE_MESSAGE_UUID),
                            ),
                        conversationUuid =
                            cursor.getString(
                                index(cursor, TranslationTables.QUEUE_CONVERSATION_UUID),
                            ),
                        body = cursor.getString(index(cursor, TranslationTables.QUEUE_BODY)),
                        state = cursor.getInt(index(cursor, TranslationTables.QUEUE_STATE)),
                        attempts = cursor.getInt(index(cursor, TranslationTables.QUEUE_ATTEMPTS)),
                        createdAt = cursor.getLong(index(cursor, TranslationTables.QUEUE_CREATED_AT)),
                        failedAt = if (failedAt < 0 || cursor.isNull(failedAt)) 0L else cursor.getLong(failedAt),
                        lastError =
                            cursor.getString(index(cursor, TranslationTables.QUEUE_LAST_ERROR)),
                        failureCause =
                            cursor.getString(
                                index(cursor, TranslationTables.QUEUE_FAILURE_CAUSE),
                            ),
                        conversationJid =
                            cursor.getString(
                                index(cursor, TranslationFailureQueries.CONVERSATION_JID),
                            ),
                    ),
                )
            }
        }
        return rows
    }

    /**
     * The sends whose translation failed, from the message rows themselves.
     *
     * @param failedState the translation state a failed send carries
     * @param receivedStatus the status boundary: a received message is the queue's business, not this
     *     read's
     */
    fun sendFailures(failedState: Int, receivedStatus: Int): List<SendFailureRow> {
        val rows = ArrayList<SendFailureRow>()
        db.rawQuery(
                TranslationFailureQueries.SEND_FAILURES,
                arrayOf(failedState.toString(), receivedStatus.toString()),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        SendFailureRow(
                            messageUuid = cursor.getString(index(cursor, Message.UUID)),
                            conversationUuid =
                                cursor.getString(index(cursor, Message.CONVERSATION)),
                            body = cursor.getString(index(cursor, Message.BODY)),
                            timeSent = cursor.getLong(index(cursor, Message.TIME_SENT)),
                            conversationJid =
                                cursor.getString(
                                    index(cursor, TranslationFailureQueries.CONVERSATION_JID),
                                ),
                        ),
                    )
                }
            }
        return rows
    }

    private fun index(cursor: Cursor, column: String): Int = cursor.getColumnIndexOrThrow(column)
}
