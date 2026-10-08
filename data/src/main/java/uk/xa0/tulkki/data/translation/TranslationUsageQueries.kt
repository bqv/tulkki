package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.model.Conversation

/**
 * `translation/`'s ledger statements (S5-6, schema 78): the day's tokens, and the same tokens split by
 * where the call went.
 *
 * <p>**Two tables, one write.** A call is filed twice - once into the day's row and once into the
 * day-and-origin row - and the pair is what makes the drill-down sum to the figure beside it. Both
 * halves are written the same way and for the same reason: an `INSERT OR IGNORE` seeds the key and an
 * `UPDATE … = col + ?` adds in place, never `INSERT OR REPLACE`, which deletes the old row first and
 * is exactly how a day's history would be lost by the second call of the day.
 *
 * <p>**The origin is `''` and never null.** `translation_usage_origin`'s key is `(day, origin)`, and
 * SQLite treats every `NULL` in a unique key as distinct - a nullable bucket could be inserted twice
 * for one day, which is how a breakdown stops adding up. `''` is the one spelling of "this call was
 * not tied to a room", and it is the same convention `sync_gap` uses for an account-wide row.
 *
 * <p>`TranslationStore` composed the day half itself out of `TranslationTables`' names; this is the
 * ledger slice of `docs/MIGRATION.md`, "Design: the data layer" §6 step 6, and
 * [TranslationUsageStore] is the only caller.
 */
internal object TranslationUsageQueries {

    const val DAY_TABLE = TranslationTables.USAGE_TABLE
    const val DAY = TranslationTables.USAGE_DAY

    const val ORIGIN_TABLE = TranslationTables.USAGE_ORIGIN_TABLE
    const val ORIGIN = TranslationTables.USAGE_ORIGIN

    const val PEAK_CACHE_HIT = TranslationTables.USAGE_PEAK_CACHE_HIT
    const val PEAK_CACHE_MISS = TranslationTables.USAGE_PEAK_CACHE_MISS
    const val PEAK_OUTPUT = TranslationTables.USAGE_PEAK_OUTPUT
    const val OFF_PEAK_CACHE_HIT = TranslationTables.USAGE_OFF_PEAK_CACHE_HIT
    const val OFF_PEAK_CACHE_MISS = TranslationTables.USAGE_OFF_PEAK_CACHE_MISS
    const val OFF_PEAK_OUTPUT = TranslationTables.USAGE_OFF_PEAK_OUTPUT

    /** The address the bucket's origin resolves to, and whether it resolved at all. */
    const val ADDRESS = "resolved_address"

    const val RESOLVED = "resolved"

    /** The six columns, in the order the bind arguments are passed: `col=col+?` six times. */
    @JvmField
    val ADD: String =
        listOf(
                PEAK_CACHE_HIT,
                PEAK_CACHE_MISS,
                PEAK_OUTPUT,
                OFF_PEAK_CACHE_HIT,
                OFF_PEAK_CACHE_MISS,
                OFF_PEAK_OUTPUT,
            )
            .joinToString(",") { "$it=$it+?" }

    /** The day's row, seeded. `IGNORE`, so a row that is already there is never zeroed. */
    @JvmField
    val TOUCH_DAY: String = "INSERT OR IGNORE INTO " + DAY_TABLE + " (" + DAY + ") VALUES (?)"

    /** The day's six counts, added to. */
    @JvmField
    val ADD_TO_DAY: String = "UPDATE " + DAY_TABLE + " SET " + ADD + " WHERE " + DAY + " = ?"

    /** The day's row as it stands, or none. */
    @JvmField
    val READ_DAY: String = "SELECT * FROM " + DAY_TABLE + " WHERE " + DAY + " = ?"

    /** The most recent days that have a row, newest first, at most `limit` of them. */
    @JvmField
    val RECENT_DAYS: String =
        "SELECT * FROM " + DAY_TABLE + " ORDER BY " + DAY + " DESC LIMIT ?"

    /** One day-and-origin row, seeded. `IGNORE` for the same reason as the day's. */
    @JvmField
    val TOUCH_ORIGIN: String =
        "INSERT OR IGNORE INTO " + ORIGIN_TABLE + " (" + DAY + ", " + ORIGIN + ") VALUES (?,?)"

    /** One day-and-origin row's six counts, added to. */
    @JvmField
    val ADD_TO_ORIGIN: String =
        "UPDATE " + ORIGIN_TABLE + " SET " + ADD + " WHERE " + DAY + " = ? AND " + ORIGIN + " = ?"

    /**
     * One day's buckets, with the name resolved at read time.
     *
     * <p>The join is a `LEFT` one and [RESOLVED] carries whether it found anything, because a bucket
     * whose conversation has been deleted must still appear - its tokens were still spent - and it
     * cannot be told apart from a conversation that exists without a `contactJid` by its values. The
     * address is read from `conversations` and is never written back: the ledger holds a key, and an
     * address lives only in the row `conversations` already owns.
     */
    @JvmField
    val ORIGINS_FOR_DAY: String =
        "SELECT t." +
            ORIGIN +
            " AS " +
            ORIGIN +
            ", c." +
            Conversation.CONTACTJID +
            " AS " +
            ADDRESS +
            ", CASE WHEN c." +
            Conversation.UUID +
            " IS NULL THEN 0 ELSE 1 END AS " +
            RESOLVED +
            ", c." +
            Conversation.MODE +
            " AS " +
            Conversation.MODE +
            ", t." +
            PEAK_CACHE_HIT +
            ", t." +
            PEAK_CACHE_MISS +
            ", t." +
            PEAK_OUTPUT +
            ", t." +
            OFF_PEAK_CACHE_HIT +
            ", t." +
            OFF_PEAK_CACHE_MISS +
            ", t." +
            OFF_PEAK_OUTPUT +
            " FROM " +
            ORIGIN_TABLE +
            " t LEFT JOIN " +
            Conversation.TABLENAME +
            " c ON c." +
            Conversation.UUID +
            " = t." +
            ORIGIN +
            " WHERE t." +
            DAY +
            " = ?" +
            " ORDER BY t." +
            ORIGIN +
            " ASC"
}
