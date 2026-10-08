package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.TranslationTables

/**
 * `translation/`'s cache statements (S5-6): the answers already paid for, spelled once.
 *
 * <p>There are two of them and they are the whole surface, because the table is a key and its value:
 * one row by the key, and one row written with `REPLACE`. Nothing here decides anything - the key's
 * identity is `:translation`'s ([uk.xa0.tulkki.translation.CacheKey]) and the tariff arithmetic is
 * [uk.xa0.tulkki.translation.DailyTokenCounter]'s - so this is a name for each statement and nothing
 * else.
 *
 * <p>`TranslationStore` composed both itself, out of `TranslationTables`' column names, holding the
 * writable handle to do it; this is the cache slice of `docs/MIGRATION.md`, "Design: the data layer"
 * §6 step 6, and [TranslationCacheStore] is the only caller.
 */
internal object TranslationCacheQueries {

    const val TABLE = TranslationTables.CACHE_TABLE

    const val KEY = TranslationTables.CACHE_KEY
    const val DETECTED_LANGUAGE = TranslationTables.CACHE_DETECTED_LANGUAGE
    const val TRANSLATED_BODY = TranslationTables.CACHE_TRANSLATED_BODY
    const val TOTAL_TOKENS = TranslationTables.CACHE_TOTAL_TOKENS
    const val CREATED_AT = TranslationTables.CACHE_CREATED_AT

    /** The one row a key names, or none: the key is the primary key and this is a lookup, not a scan. */
    @JvmField
    val BY_KEY: String = "SELECT * FROM " + TABLE + " WHERE " + KEY + " = ?"

    /**
     * The cache's columns, in the file's own order. The row is read by name in
     * [TranslationCacheStore.find], so this list is the store's own checklist rather than a SELECT
     * list - the statement above is `SELECT *` because the table has no column a reader may skip.
     */
    @JvmField
    val COLUMNS: List<String> =
        listOf(KEY, DETECTED_LANGUAGE, TRANSLATED_BODY, TOTAL_TOKENS, CREATED_AT)
}
