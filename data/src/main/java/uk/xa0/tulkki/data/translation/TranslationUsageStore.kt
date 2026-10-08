package uk.xa0.tulkki.data.translation

import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.model.Conversation

/**
 * The ledger's rows, on the app's own encrypted database (S5-6, schema 78): the one door from
 * `:translation` to `translation_usage` and `translation_usage_origin`.
 *
 * <p>**What this replaces.** `TranslationStore` held
 * `DatabaseBackend.getInstance(context).getWritableDatabase()` and composed the day's statements and
 * the `a=a+?` clause itself. The SQL is [TranslationUsageQueries]'s from here on, the cursor walk is
 * this class's, and no `Cursor` and no `SQLiteDatabase` leaves `:data`.
 *
 * <p>**The write is one call, two tables.** [add] files a call into the day's row and into the
 * day-and-origin row together, because the drill-down is drawn beside the day's own figure and the two
 * must agree; there is no second entry point that could write one without the other.
 *
 * <p>**What is decided here and what is not.** The six counts are the caller's - `:translation` owns
 * the tariff split and the clamp at zero. What *is* decided here is the shape of the read: which
 * bucket is "not tied to a room", what to call a bucket whose conversation is gone, and that the name
 * is resolved by joining `conversations` rather than stored. Those are properties of this table.
 */
class TranslationUsageStore private constructor(private val db: SQLiteDatabase) {

    companion object {
        @JvmStatic
        fun get(context: Context): TranslationUsageStore =
            TranslationUsageStore(HistoryDatabase.get(context))
    }

    /** The day's counts, or six zeroes when the day has no row. */
    fun read(day: String): UsageCounts {
        db.query(TranslationUsageQueries.READ_DAY, arrayOf<Any>(day)).use { cursor ->
            return if (cursor.moveToFirst()) counts(cursor) else UsageCounts.zero()
        }
    }

    /**
     * Files one call: the day's row and the day-and-origin row, each seeded with `IGNORE` and then
     * added to in place. A call that reported no tokens is not a row - writing a zero row would put a
     * date on the screen that nothing was ever spent on.
     *
     * @param origin the conversation the call belonged to, or null for a call tied to no conversation
     */
    fun add(day: String, origin: String?, counts: UsageCounts) {
        if (counts.isEmpty()) {
            return
        }
        // `''` and not null: the key is (day, origin) and SQLite treats NULLs in a unique key as
        // distinct, so a null bucket could be inserted twice and stop summing with the day's row.
        val key = origin ?: ""
        db.execSQL(TranslationUsageQueries.TOUCH_DAY, arrayOf<Any>(day))
        addTo(TranslationUsageQueries.ADD_TO_DAY, counts, day)
        db.execSQL(TranslationUsageQueries.TOUCH_ORIGIN, arrayOf<Any>(day, key))
        addTo(TranslationUsageQueries.ADD_TO_ORIGIN, counts, day, key)
    }

    /** The most recent days that have a row, newest first. A non-positive limit reads none. */
    fun recent(limit: Int): List<UsageDay> {
        if (limit <= 0) {
            return emptyList()
        }
        val days = ArrayList<UsageDay>()
        db.query(TranslationUsageQueries.RECENT_DAYS, arrayOf<Any>(limit)).use { cursor ->
            while (cursor.moveToNext()) {
                days.add(
                    UsageDay(
                        cursor.getString(index(cursor, TranslationUsageQueries.DAY)),
                        counts(cursor),
                    ),
                )
            }
        }
        return days
    }

    /**
     * One day, split by where its tokens went: every bucket that holds tokens, in `origin` order, with
     * the name resolved out of `conversations` at read time and the day's own figure beside them.
     */
    fun byOrigin(day: String): UsageByOrigin {
        val origins = ArrayList<UsageOrigin>()
        db.rawQuery(TranslationUsageQueries.ORIGINS_FOR_DAY, arrayOf(day)).use { cursor ->
            while (cursor.moveToNext()) {
                val origin = cursor.getString(index(cursor, TranslationUsageQueries.ORIGIN)) ?: ""
                val resolved =
                    cursor.getInt(index(cursor, TranslationUsageQueries.RESOLVED)) != 0
                val address = cursor.getString(index(cursor, TranslationUsageQueries.ADDRESS))
                origins.add(
                    UsageOrigin(
                        origin = origin,
                        kind = kindOf(origin, resolved, mode(cursor)),
                        address = if (resolved) address else null,
                        usage = counts(cursor),
                    ),
                )
            }
        }
        return UsageByOrigin(day, origins, read(day))
    }

    // -- the row's two directions ------------------------------------------------------------------

    /** The six counts, by name, in the `(peak, off-peak) × (hit, miss, output)` order of the columns. */
    private fun counts(cursor: Cursor): UsageCounts =
        UsageCounts(
            peakCacheHit = cursor.getInt(index(cursor, TranslationUsageQueries.PEAK_CACHE_HIT)),
            peakCacheMiss = cursor.getInt(index(cursor, TranslationUsageQueries.PEAK_CACHE_MISS)),
            peakOutput = cursor.getInt(index(cursor, TranslationUsageQueries.PEAK_OUTPUT)),
            offPeakCacheHit =
                cursor.getInt(index(cursor, TranslationUsageQueries.OFF_PEAK_CACHE_HIT)),
            offPeakCacheMiss =
                cursor.getInt(index(cursor, TranslationUsageQueries.OFF_PEAK_CACHE_MISS)),
            offPeakOutput = cursor.getInt(index(cursor, TranslationUsageQueries.OFF_PEAK_OUTPUT)),
        )

    /** The conversation's mode, as an `Int?`: a resolved row without one is a one-to-one by default. */
    private fun mode(cursor: Cursor): Int? {
        val at = cursor.getColumnIndex(Conversation.MODE)
        return if (at < 0 || cursor.isNull(at)) null else cursor.getInt(at)
    }

    /**
     * What a bucket is. The reserved several-rooms marker is checked **first**, before the join's
     * answer is consulted, or a batch's bucket would be read as a conversation the owner deleted; then
     * the `''` origin, which is not tied to a room; then resolution. Every bucket that holds tokens
     * gets one of the five.
     */
    private fun kindOf(origin: String, resolved: Boolean, mode: Int?): UsageOriginKind =
        when {
            origin == UsageOrigin.MULTI_ROOM_ORIGIN -> UsageOriginKind.MULTI_ROOM
            origin.isEmpty() -> UsageOriginKind.NOT_TIED_TO_A_ROOM
            !resolved -> UsageOriginKind.DELETED_CONVERSATION
            mode == Conversation.MODE_MULTI -> UsageOriginKind.GROUP_CHAT
            else -> UsageOriginKind.ONE_TO_ONE
        }

    /** One `col=col+?` statement: the six counts in the clause's own order, then the key. */
    private fun addTo(statement: String, counts: UsageCounts, vararg key: String) {
        val args = arrayOfNulls<Any>(6 + key.size)
        args[0] = counts.peakCacheHit
        args[1] = counts.peakCacheMiss
        args[2] = counts.peakOutput
        args[3] = counts.offPeakCacheHit
        args[4] = counts.offPeakCacheMiss
        args[5] = counts.offPeakOutput
        for (i in key.indices) {
            args[6 + i] = key[i]
        }
        db.execSQL(statement, args)
    }

    private fun index(cursor: Cursor, column: String): Int = cursor.getColumnIndexOrThrow(column)
}
