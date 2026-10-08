package uk.xa0.tulkki.data.translation

import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase

/**
 * The write-back, on the app's own encrypted database (S5-6): the one door from `:translation` to a
 * message's three translation columns and a conversation's detected language.
 *
 * <p>**What this replaces.** `TranslationStore` held
 * `DatabaseBackend.getInstance(context).getWritableDatabase()` and composed these four statements and
 * their `ContentValues` itself. The SQL is [MessageTranslationQueries]'s from here on, the cursor walk
 * is this class's, and no `Cursor` and no `SQLiteDatabase` leaves `:data`.
 *
 * <p>**Every write names its row by key and touches only its own columns.** That is the whole safety
 * property of a write-back: the row was read elsewhere, by the model, and a statement that named a
 * column it did not read is how a redraw would clobber the message it is drawing. The read-back is
 * not the interface's - the loaded `Message`/`Conversation` is updated by `TranslationStore`, which
 * has the host; this class only moves the row.
 */
class MessageTranslationStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        @JvmStatic
        fun get(context: Context): MessageTranslationStore =
            MessageTranslationStore(HistoryDatabase.get(context))
    }

    /** One message's answer: the translated body, the language it is in, and the state. */
    fun writeTranslation(
        messageUuid: String,
        translatedBody: String?,
        language: String?,
        state: Int,
    ) {
        db.execSQL(
            MessageTranslationQueries.WRITE_TRANSLATION,
            arrayOf<Any?>(translatedBody, language, state, messageUuid),
        )
    }

    /** One message's stored translation goes: `forget`, when an edit replaced the message's text. */
    fun clearTranslation(messageUuid: String) {
        db.execSQL(MessageTranslationQueries.CLEAR_TRANSLATION, arrayOf<Any>(messageUuid))
    }

    /**
     * The conversation's own detected language, from the row - asked only when the interface has no
     * loaded conversation to ask, so a weak reading cannot replace a language already established
     * without comparing against what is stored.
     */
    fun conversationLanguage(conversationUuid: String): String? {
        db.rawQuery(
                MessageTranslationQueries.READ_CONVERSATION_LANGUAGE,
                arrayOf(conversationUuid),
            )
            .use { cursor: Cursor ->
                return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }

    /** The conversation's detected language, written back. */
    fun recordConversationLanguage(conversationUuid: String, detectedLanguage: String) {
        db.execSQL(
            MessageTranslationQueries.WRITE_CONVERSATION_LANGUAGE,
            arrayOf<Any>(detectedLanguage, conversationUuid),
        )
    }
}
