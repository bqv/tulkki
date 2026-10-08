package uk.xa0.tulkki.translation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Which DeepSeek calls are billed at the peak tariff, decided from the call's own instant in UTC.
 *
 * <p>The rule is DeepSeek's: <strong>peak is Beijing time Monday to Friday, 09:00-12:00 and
 * 14:00-18:00</strong>, excluding Chinese public holidays; everything else - weekends and holidays
 * all day - is off-peak, at half the price. In UTC those two windows are 01:00-04:00 and 06:00-10:00,
 * and that is the form used here: the instant is read against a fixed UTC offset, so the device's own
 * timezone cannot move a call from one tariff to the other, and neither can a wrong clock setting on
 * the phone change the arithmetic.
 *
 * <p>A window is half-open - <em>start inclusive, end exclusive</em> - so 04:00:00 UTC is already
 * off-peak, which is what "09:00-12:00 Beijing" means to the minute.
 *
 * <p>The holiday exclusion is known offline from the published annual notice rather than derived:
 * see [ChineseHolidays]. A holiday is a Beijing calendar day, so it is looked up in the
 * Beijing date of the instant while the weekday comes from the UTC one; for every instant inside a
 * peak window the two agree, because the windows are 01:00-10:00 UTC and adding eight hours keeps
 * the same calendar day.
 *
 * <p>Pure Kotlin, no Android types, so the windows and their edges are pinned by JVM tests. There is
 * no clock here at all: an instant is handed in, which is what makes "the call decides its own
 * tariff" a property of the code rather than of when the screen happens to be drawn.
 */
object PeakTariff {

    /** DeepSeek's Beijing peak windows, expressed in UTC minutes from midnight. */
    const val MORNING_START_MINUTES = 1 * 60

    const val MORNING_END_MINUTES = 4 * 60
    const val AFTERNOON_START_MINUTES = 6 * 60
    const val AFTERNOON_END_MINUTES = 10 * 60

    /** Beijing is UTC+8 with no daylight saving, so a fixed offset is the honest reading. */
    private val BEIJING: ZoneOffset = ZoneOffset.ofHours(8)

    /** A fixed day length, because the windows are minutes of a UTC day and UTC has no leap second. */
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /**
     * The windows themselves, so that a schedule read off DeepSeek's own pricing page can be used in
     * place of the shipped one.
     *
     * <p>Only the two times are data. The <em>shape</em> of the rule - Monday to Friday, excluding
     * Chinese public holidays, with everything else off-peak - is the code's, not a value, because it
     * is not a number a table can hold: applying a fetched window under a rule the page no longer
     * states would be a worse guess than keeping the one that shipped. That is also why a fetched
     * schedule is only ever stored when the sentence it came from still says both of those things.
     *
     * <p>The times are minutes of a UTC day, half-open at both ends as above.
     */
    data class Schedule(
            @JvmField val morningStartMinutes: Int,
            @JvmField val morningEndMinutes: Int,
            @JvmField val afternoonStartMinutes: Int,
            @JvmField val afternoonEndMinutes: Int) {

        /** Whether these are the windows the build ships, so a screen can say which it is using. */
        fun isShipped(): Boolean = this == SHIPPED

        /** Whether an instant falls in one of these windows, weekday and holidays included. */
        fun isPeak(epochMillisUtc: Long): Boolean =
                isPeak(epochMillisUtc, PeakTariff.utcWeekday(epochMillisUtc))

        /** The same, with the instant's UTC weekday handed in so a test can state it. */
        fun isPeak(epochMillisUtc: Long, utcWeekday: DayOfWeek): Boolean {
            if (utcWeekday == DayOfWeek.SATURDAY || utcWeekday == DayOfWeek.SUNDAY) {
                return false
            }
            if (PeakTariff.ChineseHolidays.isHoliday(PeakTariff.beijingDate(epochMillisUtc))) {
                return false
            }
            val minuteOfDayUtc = PeakTariff.minuteOfDayUtc(epochMillisUtc)
            return PeakTariff.inWindow(minuteOfDayUtc, morningStartMinutes, morningEndMinutes) ||
                    PeakTariff.inWindow(minuteOfDayUtc, afternoonStartMinutes, afternoonEndMinutes)
        }

        override fun toString(): String =
                window(morningStartMinutes, morningEndMinutes) +
                        " and " +
                        window(afternoonStartMinutes, afternoonEndMinutes) +
                        " UTC"

        companion object {

            /** The shipped schedule: Beijing 09:00-12:00 and 14:00-18:00, in UTC. */
            @JvmField
            val SHIPPED: Schedule =
                    Schedule(
                            PeakTariff.MORNING_START_MINUTES,
                            PeakTariff.MORNING_END_MINUTES,
                            PeakTariff.AFTERNOON_START_MINUTES,
                            PeakTariff.AFTERNOON_END_MINUTES)

            private fun window(start: Int, end: Int): String =
                    String.format(
                            Locale.ROOT, "%02d:%02d-%02d:%02d", start / 60, start % 60, end / 60, end % 60)
        }
    }

    /**
     * The schedule in force: the one a refreshed pricing page brought, or the shipped one.
     *
     * <p>Read at the moment a call is filed rather than captured when the ledger was built, so a
     * refreshed schedule applies to the next call instead of to the next run of the app. A process
     * that has never read the settings gets the shipped schedule, exactly as it gets the shipped
     * prompts.
     */
    internal fun storedOrShipped(): Schedule {
        val settings = TranslationSettings.current()
        val stored = settings?.tariffSchedule()
        return stored ?: Schedule.SHIPPED
    }

    /**
     * Whether `epochMillisUtc` falls in a peak window, from the instant alone, under the
     * <em>shipped</em> schedule. The ambient schedule is the ledger's business - see
     * [storedOrShipped] - and this stays pure so that "the window edges are what the tests
     * say they are" cannot depend on what a screen or a settings file happens to hold.
     */
    @JvmStatic
    fun isPeak(epochMillisUtc: Long): Boolean = Schedule.SHIPPED.isPeak(epochMillisUtc)

    /**
     * The same answer, with the instant's UTC weekday handed in.
     *
     * <p>The weekday is a parameter because it is the half of "peak" that is not a time of day, and a
     * test should be able to state it rather than encode it in a timestamp. The holiday lookup still
     * comes from the instant, because a holiday is a date and not a weekday.
     */
    @JvmStatic
    fun isPeak(epochMillisUtc: Long, utcWeekday: DayOfWeek): Boolean =
            Schedule.SHIPPED.isPeak(epochMillisUtc, utcWeekday)

    /** The Beijing calendar day of an instant, as `yyyymmdd`, for the holiday table. */
    @JvmStatic
    fun beijingDate(epochMillisUtc: Long): Int {
        val date = Instant.ofEpochMilli(epochMillisUtc).atZone(BEIJING).toLocalDate()
        return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }

    @JvmStatic
    fun utcWeekday(epochMillisUtc: Long): DayOfWeek =
            Instant.ofEpochMilli(epochMillisUtc).atZone(ZoneOffset.UTC).dayOfWeek

    /** Minutes since midnight UTC, 0..1439; `floorMod` keeps a pre-1970 instant honest. */
    private fun minuteOfDayUtc(epochMillisUtc: Long): Int =
            (Math.floorMod(epochMillisUtc, MILLIS_PER_DAY) / 60_000L).toInt()

    private fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean =
            minuteOfDay >= start && minuteOfDay < end

    /**
     * The Chinese public holidays DeepSeek's peak rule excludes, as published ranges.
     *
     * <p><strong>The table is keyed by year and an unlisted year has no exemptions.</strong> The
     * holiday schedule cannot be derived offline - it follows the lunar calendar and the State
     * Council's own adjustments - so it is embedded from the published notice, which means it can go
     * stale. It is keyed by year so that staleness can only ever fail in the <em>safe</em>
     * direction: if 2027's dates are not in the table, a 2027 weekday is priced by the weekday rule
     * alone, which charges peak prices on days that turn out to be holidays. That over-estimates the
     * spend on a handful of days a year. The other design - carrying a range forward past its year -
     * would keep charging half price for days that are no longer holidays, and would quietly make the
     * app look cheaper than it was, which is the one direction nobody can check by looking at it.
     *
     * <p>The ranges are the official 2026 notice (Guo Ban Fa Ming Dian [2025] No. 7, 2025-11-04),
     * inclusive of both ends. The notice's "adjusted working days" (Jan 4, Feb 14, Feb 28, May 9,
     * Sep 20, Oct 10) are deliberately not modelled: a weekend is off-peak by the weekday rule
     * whether it is an adjusted working day or not.
     */
    object ChineseHolidays {

        /**
         * Inclusive `{startMonth, startDay, endMonth, endDay}` ranges, per year. No range
         * crosses a year boundary, so a `month * 100 + day` comparison is enough.
         */
        private val RANGES: Map<Int, Array<IntArray>> = ranges()

        /** The newest year the table names; after it, calls are priced by the weekday rule alone. */
        const val LAST_LISTED_YEAR = 2026

        private fun ranges(): Map<Int, Array<IntArray>> {
            val map = HashMap<Int, Array<IntArray>>()
            map[2026] =
                    arrayOf(
                            intArrayOf(1, 1, 1, 3), // New Year's Day
                            intArrayOf(2, 15, 2, 23), // Spring Festival
                            intArrayOf(4, 4, 4, 6), // Qingming
                            intArrayOf(5, 1, 5, 5), // Labour Day
                            intArrayOf(6, 19, 6, 21), // Dragon Boat
                            intArrayOf(9, 25, 9, 27), // Mid-Autumn
                            intArrayOf(10, 1, 10, 7)) // National Day
            return map
        }

        /** Whether a Beijing calendar day is inside one of a listed year's published ranges. */
        @JvmStatic
        fun isHoliday(date: LocalDate?): Boolean {
            if (date == null) {
                return false
            }
            return isHoliday(date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth)
        }

        /** The same, from an `yyyymmdd` date. */
        @JvmStatic
        fun isHoliday(yyyymmdd: Int): Boolean {
            val ranges = RANGES[yyyymmdd / 10_000]
            if (ranges == null) {
                // An unlisted year has no exemptions: the weekday rule alone decides, which charges
                // peak on any holiday it does not know about and so never under-reports the spend.
                return false
            }
            val monthDay = yyyymmdd % 10_000
            for (range in ranges) {
                if (monthDay >= range[0] * 100 + range[1] && monthDay <= range[2] * 100 + range[3]) {
                    return true
                }
            }
            return false
        }

        /** Whether any range is known for `year`. */
        @JvmStatic
        fun isListed(year: Int): Boolean = RANGES.containsKey(year)
    }
}
