package uk.xa0.tulkki.translation

import java.time.ZoneId
import java.util.LinkedHashMap

/**
 * The app's own spend, kept as tokens per local day so that money can be recomputed at display time.
 *
 * <p>This is the class that makes the editability of [TokenPrices] mean something. A row holds
 * the six token counts ([TokenUsage]) for one day and no figure at all, so changing a price
 * re-prices the whole history rather than only the calls made after the change. Storing money would
 * have frozen every past day at the price in force when it was made, and the owner's whole point in
 * having editable prices is to correct the assumption behind the numbers.
 *
 * <p>The day is the same local calendar day the daily cap uses ([DailyTokenCounter.dayOf]), so
 * the cap and this ledger cannot disagree about when "today" ended. The <em>tariff</em> is a separate
 * fact and is decided from the call's own instant in UTC ([PeakTariff]), never from the local
 * day: a day can hold calls from both windows, and a device whose timezone is wrong must not move a
 * call to the cheap tariff.
 *
 * <p>That also means a call is filed under the tariff it actually paid, while the row it lands in is
 * the local day it happened on - the two are independent, and both are the honest reading of "the
 * day's spend".
 *
 * <p>The storage behind it is Android/SQLite in production (`TranslationStore`), so this class
 * is pure Kotlin over a small interface and the SQLite half is only demonstrable on a device. Its
 * arithmetic, its day attribution and its tariff split are pinned by JVM tests.
 *
 * <p>The same, with the tariff schedule named as well.
 *
 * <p>The schedule is asked for at the moment a call is filed rather than captured here, so a page
 * read while the app is running applies to the next call - and a test can hand in a fixed one
 * without any settings existing at all.
 */
class UsageLedger(
        private val store: Store,
        private val zone: ZoneId,
        schedules: ScheduleSource?) {

    private val schedules: ScheduleSource =
            schedules ?: ScheduleSource { PeakTariff.storedOrShipped() }

    /** The ledger whose days are the device's own, which is what the cap already uses. */
    constructor(store: Store) : this(store, ZoneId.systemDefault(), null)

    /** The same, with the zone named, so a test can put a call on the day it means. */
    constructor(store: Store, zone: ZoneId) : this(store, zone, null)

    /** Where the rows live. Implemented by the SQLite store, and by memory for JVM tests. */
    interface Store {
        /** The day's row, or [TokenUsage.zero] when the day has none. */
        fun read(day: String): TokenUsage

        /**
         * Adds `delta` to `day`'s row, creating it when it is not there yet, and to the row for
         * `origin` on the same day.
         *
         * @param origin the conversation the call belonged to, or null for a call that belongs to no
         *     conversation - a gloss, a note, the silent language sample, a pre-send suggestion. The
         *     two writes are one call because the day's figure and the day's breakdown must agree.
         */
        fun add(day: String, origin: String?, delta: TokenUsage)

        /** The most recent days that have a row, newest first, at most `limit` of them. */
        fun recent(limit: Int): List<Day>?
    }

    /** One day's row: the local date and its tokens. The money is computed from these. */
    class Day(day: String, usage: TokenUsage?) {
        /** The local calendar day, as an ISO date, exactly as [DailyTokenCounter] spells it. */
        @JvmField val day: String = day

        @JvmField val usage: TokenUsage = usage ?: TokenUsage.zero()

        override fun toString(): String = "$day $usage"
    }

    /** Where the schedule in force comes from, so a refreshed one is a seam rather than a static. */
    fun interface ScheduleSource {
        fun get(): PeakTariff.Schedule?
    }

    /**
     * Files one call's token split: the day from `atMillis` in the ledger's zone, the tariff
     * from the same instant in UTC.
     *
     * @param atMillis the call's own instant - when the request was made, not when a screen is drawn
     * @param origin the conversation the call belonged to - its uuid and nothing else - or null for a
     *     call that belongs to no conversation (a gloss, a note, the silent language sample, a
     *     pre-send suggestion). It is what the ledger's day drill-down groups by; the name is resolved
     *     from `conversations` when the read happens, never stored here.
     */
    @JvmOverloads
    fun record(atMillis: Long, cacheHit: Int, cacheMiss: Int, output: Int, origin: String? = null) {
        val schedule = schedules.get()
        val delta =
                TokenUsage.forCall(
                        cacheHit,
                        cacheMiss,
                        output,
                        if (schedule == null) PeakTariff.isPeak(atMillis) else schedule.isPeak(atMillis))
        if (delta.isEmpty()) {
            // A call that reported no tokens is not a row. Writing a zero day would put a date on the
            // screen that nothing was ever spent on, which reads as a bug rather than as history.
            return
        }
        store.add(dayOf(atMillis), origin, delta)
    }

    /** The local day an instant belongs to, which is the cap's own boundary. */
    fun dayOf(atMillis: Long): String = DailyTokenCounter.dayOf(atMillis, zone)

    /** The recent days the usage screen shows, newest first. */
    fun recentDays(): List<Day> = recentDays(SHOWN_DAYS)

    /** The same, with the bound named. */
    fun recentDays(limit: Int): List<Day> =
            if (limit <= 0) {
                emptyList()
            } else {
                store.recent(limit) ?: emptyList()
            }

    /**
     * The ledger in a map: the store the settings use when there is no database - a JVM test, and
     * nothing else. It has the same semantics as the table, so a test of the ledger is a test of the
     * rules rather than of the fixture.
     */
    class MemoryStore : Store {
        private val byDay: MutableMap<String, TokenUsage> = LinkedHashMap()

        override fun read(day: String): TokenUsage = byDay[day] ?: TokenUsage.zero()

        override fun add(day: String, origin: String?, delta: TokenUsage) {
            byDay[day] = read(day).plus(delta)
        }

        override fun recent(limit: Int): List<Day> {
            val days = ArrayList(byDay.keys)
            days.sortDescending()
            val rows = ArrayList<Day>()
            for (day in days) {
                if (rows.size >= limit) {
                    break
                }
                rows.add(Day(day, read(day)))
            }
            return rows
        }
    }

    companion object {

        /**
         * How many days the usage screen shows, newest first. The table keeps every day for ever - a
         * row is a few bytes and a window would be a lie about history the owner may want to add up -
         * while the screen is bounded so it stays a screen rather than a scroll through years. The
         * total on screen is the total across what is shown, and says so.
         */
        const val SHOWN_DAYS = 30

        /** What [recentDays] adds up to, computed at the prices in force right now. */
        @JvmStatic
        fun totalYuan(days: List<Day>?, prices: TokenPrices): Double {
            var total = 0.0
            if (days == null) {
                return total
            }
            for (day in days) {
                total += prices.yuan(day.usage)
            }
            return total
        }
    }
}
