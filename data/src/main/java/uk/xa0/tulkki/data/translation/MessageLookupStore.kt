package uk.xa0.tulkki.data.translation

import android.content.Context
import android.database.Cursor
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * The by-uuid message read, on the app's own encrypted database (S5-6): the one door from
 * `:translation` to the rows a gap sweep named.
 *
 * <p>**What this replaces.** `TranslationStore` held
 * `DatabaseBackend.getInstance(context).getWritableDatabase()`, built the `IN (...)` statement itself
 * and called `Message.fromCursor` on the cursor it walked. The SQL is [MessageLookupQueries]''s from
 * here on, the chunking and the cursor walk are this class's, and no `Cursor` and no `SQLiteDatabase`
 * leaves `:data`. What comes back is [Message]s, which are this module's own model - the last `db()`
 * in `:translation` becomes a call to this.
 *
 * <p>**Two rows are skipped, and both are the store's rather than a decision's.** A row is skipped
 * when the conversation it names cannot be resolved (a [Message] needs its conversation to exist) and
 * when the row cannot be parsed at all - the same breadth upstream reads a message with, and for the
 * same reason: one malformed row must not cost a start the whole pass. Whether a message that *does*
 * come back is worth translating is `StartupBacklog.eligible`'s question, asked elsewhere.
 */
class MessageLookupStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val TAG = "Tulkki"

        @JvmStatic
        fun get(context: Context): MessageLookupStore =
            MessageLookupStore(HistoryDatabase.get(context))
    }

    /**
     * The messages those uuids name, in whatever order the file answers, with the rows it cannot place
     * left out.
     *
     * @param conversationOf how a stored row's conversation uuid becomes the conversation; a uuid that
     *     resolves to nothing skips that row
     */
    fun messagesByUuid(
        messageUuids: List<String>?,
        conversationOf: (String) -> Conversation?,
    ): List<Message> {
        val messages = ArrayList<Message>()
        if (messageUuids.isNullOrEmpty()) {
            return messages
        }
        var at = 0
        while (at < messageUuids.size) {
            val chunk =
                messageUuids.subList(at, minOf(at + MessageLookupQueries.CHUNK, messageUuids.size))
            val statement = MessageLookupQueries.byUuid(chunk.size)
            db.rawQuery(statement, chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val message = message(cursor, conversationOf)
                    if (message != null) {
                        messages.add(message)
                    }
                }
            }
            at += MessageLookupQueries.CHUNK
        }
        return messages
    }

    /** One row as a message, or null when it names no conversation this process knows. */
    private fun message(
        cursor: Cursor,
        conversationOf: (String) -> Conversation?,
    ): Message? {
        val conversationUuid = cursor.getString(index(cursor, Message.CONVERSATION))
        val conversation = conversationUuid?.let(conversationOf)
        if (conversation == null) {
            return null
        }
        return try {
            Message.fromCursor(cursor, conversation)
        } catch (e: Exception) {
            Log.w(TAG, "could not read a message for the bounded pass over history", e)
            null
        }
    }

    private fun index(cursor: Cursor, column: String): Int = cursor.getColumnIndexOrThrow(column)
}
