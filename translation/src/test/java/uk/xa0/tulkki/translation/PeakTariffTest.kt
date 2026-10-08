package uk.xa0.tulkki.translation

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peak tariff: both UTC windows and their edges, a weekend, and the holiday rule.
 *
 * <p>Every instant here is built from a UTC string, so the test states the tariff's own clock rather
 * than borrowing the machine's.
 */
class PeakTariffTest {

    private fun utc(iso: String): Long {
        return java.time.ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    }

    // -- the two windows, in UTC ----------------------------------------------------------------

    @Test
    fun theMorningWindowIsOneToFourUtcPeak() {
        // Monday 2026-01-05: 09:00-12:00 Beijing is 01:00-04:00 UTC.
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T01:00:00Z")))
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T03:59:00Z")))
    }

    @Test
    fun theAfternoonWindowIsSixToTenUtcPeak() {
        // 14:00-18:00 Beijing is 06:00-10:00 UTC.
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T06:00:00Z")))
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T09:59:00Z")))
    }

    @Test
    fun aWindowIsStartInclusiveAndEndExclusive() {
        // 01:00 UTC is peak because it is 09:00 in Beijing; 04:00 UTC is not, because it is 12:00.
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T01:00:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T04:00:00Z")))
        assertTrue(PeakTariff.isPeak(utc("2026-01-05T06:00:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T10:00:00Z")))
    }

    @Test
    fun theGapsBetweenAndAroundTheWindowsAreOffPeak() {
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T00:59:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T05:00:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T12:00:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-05T23:00:00Z")))
    }

    @Test
    fun aWeekendIsOffPeakAllDay() {
        // 2026-01-03 is a Saturday and 2026-01-04 a Sunday.
        assertFalse(PeakTariff.isPeak(utc("2026-01-03T02:00:00Z")))
        assertFalse(PeakTariff.isPeak(utc("2026-01-04T07:00:00Z")))
    }

    @Test
    fun theSameInstantAnsweredFromItsWeekdayAgrees() {
        val monday = utc("2026-01-05T02:00:00Z")
        assertTrue(PeakTariff.isPeak(monday, DayOfWeek.MONDAY))
        assertFalse(PeakTariff.isPeak(monday, DayOfWeek.SATURDAY))
    }

    // -- the holiday rule -------------------------------------------------------------------------

    @Test
    fun aWeekdayInsideAListedHolidayRangeIsOffPeak() {
        // 2026-10-05 is the Monday inside National Day (Oct 1-7): 10:00 Beijing, normally peak.
        assertFalse(PeakTariff.isPeak(utc("2026-10-05T02:00:00Z")))
        // 2026-02-17 is the Tuesday inside Spring Festival (Feb 15-23), likewise 14:00 Beijing.
        assertFalse(PeakTariff.isPeak(utc("2026-02-17T06:00:00Z")))
    }

    @Test
    fun theSameWeekdayOutsideTheRangeIsPeak() {
        // The Monday after National Day, and the Tuesday after Spring Festival.
        assertTrue(PeakTariff.isPeak(utc("2026-10-12T02:00:00Z")))
        assertTrue(PeakTariff.isPeak(utc("2026-02-24T06:00:00Z")))
    }

    @Test
    fun theRangesIncludeBothEndsAndExcludeTheirNeighbours() {
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20260101))
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20260103))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(20251231))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(20260104))
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20260215))
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20260223))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(20260224))
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20261007))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(20261008))
    }

    @Test
    fun aYearTheTableDoesNotNameHasNoExemptionsAndSoErrsHigh() {
        // 2027-01-04 is a Monday: the table stops at 2026, so it is priced by the weekday rule.
        assertFalse(
                "an unlisted year carries no exemptions",
                PeakTariff.ChineseHolidays.isHoliday(20270104))
        assertFalse(PeakTariff.ChineseHolidays.isListed(2027))
        assertTrue(PeakTariff.ChineseHolidays.isListed(2026))
        assertTrue(
                "an unknown year is charged peak rather than half",
                PeakTariff.isPeak(utc("2027-01-04T02:00:00Z")))
        // And the same date in a listed year, where it really is a holiday, is not.
        assertFalse(PeakTariff.isPeak(utc("2026-01-02T02:00:00Z")))
    }

    @Test
    fun theBeijingDateOfAnInstantIsWhatTheHolidayTableSees() {
        // 2026-10-07T20:00Z is already 2026-10-08 in Beijing, the day National Day is over, while the
        // UTC date is still inside the range. The holiday lookup must use the Beijing one.
        assertEquals(
                20261008, PeakTariff.beijingDate(utc("2026-10-07T20:00:00Z")))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(20261008))
        assertTrue(PeakTariff.ChineseHolidays.isHoliday(20261007))
    }

    // -- a schedule that came off the page ------------------------------------------------------

    @Test
    fun aRefreshedScheduleMovesTheWindowsAndKeepsTheRules() {
        // The two things a fetched schedule may change are the times; the shapes around them - weekdays
        // only, Chinese public holidays excluded - are the code's, because a pair of times cannot
        // express a weekend-only rule and half-applying one would be a guess. So: the same instant is
        // off-peak under the shipped windows and peak under windows an hour earlier, and a Saturday is
        // still off-peak under both.
        val early =
                PeakTariff.Schedule(0, 3 * 60, 6 * 60, 10 * 60)
        val mondayMidnight = utc("2026-01-05T00:30:00Z")
        assertFalse(PeakTariff.Schedule.SHIPPED.isPeak(mondayMidnight))
        assertTrue(early.isPeak(mondayMidnight))
        assertFalse(early.isShipped())

        val saturday = utc("2026-01-10T01:30:00Z")
        assertFalse(PeakTariff.Schedule.SHIPPED.isPeak(saturday))
        assertFalse(early.isPeak(saturday))

        // And a holiday is off-peak whatever the windows say: 2026-02-17 is inside the Spring Festival
        // range, which the table knows, so a weekday inside it is not peak even at 01:30 UTC.
        val holiday = utc("2026-02-17T01:30:00Z")
        assertEquals(DayOfWeek.TUESDAY, PeakTariff.utcWeekday(holiday))
        assertFalse(early.isPeak(holiday))
        assertFalse(PeakTariff.Schedule.SHIPPED.isPeak(holiday))
    }

    @Test
    fun theShippedScheduleIsTheOneTheConstantsName() {
        assertEquals(PeakTariff.MORNING_START_MINUTES, PeakTariff.Schedule.SHIPPED.morningStartMinutes)
        assertEquals(PeakTariff.MORNING_END_MINUTES, PeakTariff.Schedule.SHIPPED.morningEndMinutes)
        assertEquals(
                PeakTariff.AFTERNOON_START_MINUTES, PeakTariff.Schedule.SHIPPED.afternoonStartMinutes)
        assertEquals(PeakTariff.AFTERNOON_END_MINUTES, PeakTariff.Schedule.SHIPPED.afternoonEndMinutes)
        assertEquals(PeakTariff.Schedule.SHIPPED, PeakTariff.Schedule(60, 240, 360, 600))
        assertTrue(PeakTariff.Schedule.SHIPPED.isShipped())
    }
}
