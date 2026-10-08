package uk.xa0.tulkki.data.translation

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase

/**
 * The cache's rows, on the app's own encrypted database (S5-6): the one door from `:translation` to
 * `translation_cache`.
 *
 * <p>**What this replaces.** `TranslationStore` held
 * `DatabaseBackend.getInstance(context).getWritableDatabase()` and composed both cache statements
 * itself. The SQL is [TranslationCacheQueries]'s from here on, the cursor walk is this class's, and no
 * `Cursor` and no `SQLiteDatabase` leaves `:data`.
 *
 * <p>**Nothing is decided here.** The row is written with `CONFLICT_REPLACE`, which is the semantics
 * the old code had and the reason a re-bought answer overwrites rather than duplicates: the key is the
 * primary key. Whether an answer is worth caching at all - a translation that failed the language check
 * is not - is `:translation`'s rule and stays there.
 */
class TranslationCacheStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        @JvmStatic
        fun get(context: Context): TranslationCacheStore =
            TranslationCacheStore(HistoryDatabase.get(context))
    }

    /** The one row a key names, or null when the answer was never bought. */
    fun find(cacheKey: String): TranslationCacheRow? {
        db.query(TranslationCacheQueries.BY_KEY, arrayOf<Any>(cacheKey)).use { cursor ->
            return if (cursor.moveToFirst()) row(cursor) else null
        }
    }

    /** Records an answer that was just bought, replacing any row the same key already had. */
    fun put(row: TranslationCacheRow) {
        db.insertWithOnConflict(
            TranslationCacheQueries.TABLE,
            null,
            values(row),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // -- the row's two directions ------------------------------------------------------------------

    private fun values(row: TranslationCacheRow): ContentValues {
        val values = ContentValues()
        values.put(TranslationCacheQueries.KEY, row.cacheKey)
        values.put(TranslationCacheQueries.DETECTED_LANGUAGE, row.detectedLanguage)
        values.put(TranslationCacheQueries.TRANSLATED_BODY, row.translatedBody)
        values.put(TranslationCacheQueries.TOTAL_TOKENS, row.totalTokens)
        values.put(TranslationCacheQueries.CREATED_AT, row.createdAt)
        return values
    }

    /** One row, by name: one mapping, so two readers cannot disagree about a column. */
    private fun row(cursor: Cursor): TranslationCacheRow =
        TranslationCacheRow(
            cacheKey = cursor.getString(index(cursor, TranslationCacheQueries.KEY)),
            detectedLanguage =
                cursor.getString(index(cursor, TranslationCacheQueries.DETECTED_LANGUAGE)),
            translatedBody =
                cursor.getString(index(cursor, TranslationCacheQueries.TRANSLATED_BODY)),
            totalTokens = cursor.getInt(index(cursor, TranslationCacheQueries.TOTAL_TOKENS)),
            createdAt = cursor.getLong(index(cursor, TranslationCacheQueries.CREATED_AT)),
        )

    private fun index(cursor: Cursor, column: String): Int = cursor.getColumnIndexOrThrow(column)
}
