package uk.xa0.tulkki.translation

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One call's tokens, charged in one place.
 *
 * <p>Every DeepSeek call is charged twice from the same response - the response's own total to the
 * daily cap and its split to the per-day ledger - and the five copies this class replaced each did
 * both with no test naming any of them. So what is pinned here is the pairing itself: after one
 * {@link Spend#record}, the cap and the ledger both show the call or neither does.
 *
 * <p>The instant is the other half. It is a parameter rather than a clock reading, and these tests
 * take it from a fixed date on purpose: a day read from {@code System.currentTimeMillis()} instead
 * of the argument would land the call on today and almost every assertion here would fail.
 */
class SpendTest {

    private val ZONE = ZoneId.of("UTC")

    /** 2026-01-01T12:00:00Z, mid-afternoon in Helsinki, and inside no tariff window. */
    private val NOON =
            ZonedDateTime.parse("2026-01-01T12:00:00Z").toInstant().toEpochMilli()

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
    }

    @After
    fun tearDown() {
        // Process-wide on purpose (TranslationSettings.inMemory); leave it fresh for the next test.
        TranslationSettings.inMemory()
    }

    /** A response that reported 40 prompt tokens (all misses) and 10 completion tokens. */
    private fun fiftyTokens(): DeepSeekClient.Usage {
        return DeepSeekClient.Usage(40, 10, 0, 40, 50)
    }

    private fun capTokensAt(atMillis: Long, zone: ZoneId): Int {
        return settings.tokenCounter().used(DailyTokenCounter.dayOf(atMillis, zone))
    }

    @Test
    fun oneCallReachesBothTheCapAndTheLedger() {
        Spend.record(settings, fiftyTokens(), NOON, ZONE)

        assertEquals(
                "the response's own total is what the cap has always counted",
                50,
                capTokensAt(NOON, ZONE))

        val days: List<UsageLedger.Day> = settings.usageLedger().recentDays()
        assertEquals("one call, one ledger day", 1, days.size)
        val day = days.get(0)
        assertEquals(
                "the ledger day is the cap's own day boundary",
                DailyTokenCounter.dayOf(NOON, ZONE),
                day.day)
        assertEquals("the split adds up to the same call", 50, day.usage.totalTokens())
    }

    @Test
    fun aUsageOfNothingWritesNothing() {
        Spend.record(settings, DeepSeekClient.Usage.none(), NOON, ZONE)

        assertEquals(0, capTokensAt(NOON, ZONE))
        assertTrue(
                "a zero call is not a row: a date on the screen with nothing spent on it is a bug",
                settings.usageLedger().recentDays().isEmpty())
    }

    @Test
    fun aMissingUsageWritesNothing() {
        Spend.record(settings, null, NOON, ZONE)

        assertEquals(0, capTokensAt(NOON, ZONE))
        assertTrue(settings.usageLedger().recentDays().isEmpty())
    }

    @Test
    fun theDayComesFromTheInstantAndNotFromTheClock() {
        // Two calls a second either side of local midnight, on a fixed date in January 2026: if the
        // day were read from the clock, both would be filed under today's date and land on one day.
        // The zone here is the device's own, which is also what the ledger files under.
        val zone = ZoneId.systemDefault()
        val localMidnight =
                LocalDate.of(2026, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val lastSecondOfTheFirst = localMidnight - 1000L
        val firstSecondOfTheSecond = localMidnight + 1000L

        Spend.record(settings, fiftyTokens(), lastSecondOfTheFirst, zone)
        assertEquals(50, capTokensAt(lastSecondOfTheFirst, zone))

        Spend.record(settings, fiftyTokens(), firstSecondOfTheSecond, zone)
        assertEquals(50, capTokensAt(firstSecondOfTheSecond, zone))

        val days: List<UsageLedger.Day> = settings.usageLedger().recentDays()
        assertEquals("two instants on two calendar days are two days", 2, days.size)
        assertEquals(DailyTokenCounter.dayOf(firstSecondOfTheSecond, zone), days.get(0).day)
        assertEquals(DailyTokenCounter.dayOf(lastSecondOfTheFirst, zone), days.get(1).day)
        assertEquals(50, days.get(0).usage.totalTokens())
        assertEquals(50, days.get(1).usage.totalTokens())
    }

    @Test
    fun theCapDayFollowsTheZoneTheCallerNamed() {
        // The zone is the seam TranslationService threads (its own injected ZoneId): the day a pass
        // writes the cap under must be the day it read the cap with, and 23:00Z is already tomorrow
        // at UTC+14.
        val farAhead = ZoneId.of("Pacific/Kiritimati")
        val lateEvening = NOON + 11L * 60L * 60L * 1000L
        assertEquals("the two zones really are on different days here", "2026-01-01",
                DailyTokenCounter.dayOf(lateEvening, ZONE))
        assertEquals("2026-01-02", DailyTokenCounter.dayOf(lateEvening, farAhead))

        Spend.record(settings, fiftyTokens(), lateEvening, farAhead)

        assertEquals(50, capTokensAt(lateEvening, farAhead))
        assertEquals(0, capTokensAt(lateEvening, ZONE))
    }
}
