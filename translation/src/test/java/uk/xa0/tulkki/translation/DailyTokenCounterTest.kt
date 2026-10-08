package uk.xa0.tulkki.translation

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert
import org.junit.Test

/** The cap arithmetic, and the fact that a new day resets the count by itself. */
class DailyTokenCounterTest {

    private val store = TranslationDoubles.MemoryCounterStore()
    private val counter = DailyTokenCounter(store)

    @Test
    fun nothingIsSpentBeforeAnythingIsSpent() {
        Assert.assertEquals(0, counter.used("2026-01-01"))
    }

    @Test
    fun tokensAccumulateWithinTheDay() {
        Assert.assertEquals(150, counter.add("2026-01-01", 150))
        Assert.assertEquals(230, counter.add("2026-01-01", 80))
        Assert.assertEquals(230, counter.used("2026-01-01"))
    }

    @Test
    fun anotherDayReadsAsZero() {
        counter.add("2026-01-01", 5_000)
        Assert.assertEquals(0, counter.used("2026-01-02"))
        // And asking about the new day must not have destroyed the old count until it is spent.
        Assert.assertEquals(5_000, store.tokens)
    }

    @Test
    fun spendingOnTheNewDayStartsFromZero() {
        counter.add("2026-01-01", 5_000)
        Assert.assertEquals(40, counter.add("2026-01-02", 40))
    }

    @Test
    fun negativeTokensAreIgnored() {
        counter.add("2026-01-01", 100)
        Assert.assertEquals(100, counter.add("2026-01-01", -50))
    }

    @Test
    fun theCapIsReachedOnlyAtTheCap() {
        Assert.assertFalse(DailyTokenCounter.exhausted(99, 100))
        Assert.assertTrue(DailyTokenCounter.exhausted(100, 100))
        Assert.assertTrue(DailyTokenCounter.exhausted(101, 100))
    }

    @Test
    fun aCapOfZeroIsNoCap() {
        Assert.assertFalse(DailyTokenCounter.exhausted(1_000_000, 0))
        Assert.assertFalse(DailyTokenCounter.exhausted(1_000_000, -1))
        Assert.assertEquals(
                Int.MAX_VALUE, DailyTokenCounter.remaining(1_000_000, 0))
    }

    @Test
    fun remainingNeverGoesNegative() {
        Assert.assertEquals(25, DailyTokenCounter.remaining(75, 100))
        Assert.assertEquals(0, DailyTokenCounter.remaining(140, 100))
    }

    @Test
    fun theLastTenthIsReservedForReceivedTranslation() {
        // docs/MIGRATION.md item 17, three: the send path stops at the reserve line, received translation
        // may spend into it, and the cap still hard-stops at the number itself.
        Assert.assertEquals(90, DailyTokenCounter.sendLine(100))
        Assert.assertEquals(9, DailyTokenCounter.sendLine(10))

        // A send at the reserve line is refused...
        Assert.assertTrue(
                DailyTokenCounter.exhausted(
                        90, 100, DailyTokenCounter.Purpose.SEND))
        Assert.assertFalse(
                DailyTokenCounter.exhausted(
                        89, 100, DailyTokenCounter.Purpose.SEND))
        // ...and a received call at the very same point is not.
        Assert.assertFalse(
                DailyTokenCounter.exhausted(
                        90, 100, DailyTokenCounter.Purpose.RECEIVED))
        // The number is a hard stop for both.
        Assert.assertTrue(
                DailyTokenCounter.exhausted(
                        100, 100, DailyTokenCounter.Purpose.SEND))
        Assert.assertTrue(
                DailyTokenCounter.exhausted(
                        100, 100, DailyTokenCounter.Purpose.RECEIVED))
    }

    @Test
    fun theReserveShowsAsRemainingFromBothSides() {
        // The same spend answers differently by purpose, which is the whole point of the parameter.
        Assert.assertEquals(
                0, DailyTokenCounter.remaining(90, 100, DailyTokenCounter.Purpose.SEND))
        Assert.assertEquals(
                10, DailyTokenCounter.remaining(90, 100, DailyTokenCounter.Purpose.RECEIVED))
        Assert.assertEquals(
                10, DailyTokenCounter.remaining(80, 100, DailyTokenCounter.Purpose.SEND))
        // A received call may spend the reserve but never past the number.
        Assert.assertEquals(
                0, DailyTokenCounter.remaining(100, 100, DailyTokenCounter.Purpose.RECEIVED))
    }

    @Test
    fun noCapIsNoCapForEitherPurpose() {
        Assert.assertFalse(
                DailyTokenCounter.exhausted(
                        1_000_000, 0, DailyTokenCounter.Purpose.SEND))
        Assert.assertFalse(
                DailyTokenCounter.exhausted(
                        1_000_000, -1, DailyTokenCounter.Purpose.RECEIVED))
        Assert.assertEquals(
                Int.MAX_VALUE,
                DailyTokenCounter.remaining(1_000_000, 0, DailyTokenCounter.Purpose.SEND))
        Assert.assertEquals(0, DailyTokenCounter.sendLine(0))
    }

    @Test
    fun theTwoArgumentFormIsTheHardCapTheDisplayDraws() {
        // It is Purpose.RECEIVED's line by construction, and it is what a progress bar asks: the
        // reserve is stated from sendLine, not by moving this number.
        Assert.assertEquals(
                DailyTokenCounter.exhausted(90, 100, DailyTokenCounter.Purpose.RECEIVED),
                DailyTokenCounter.exhausted(90, 100))
        Assert.assertEquals(
                DailyTokenCounter.remaining(90, 100, DailyTokenCounter.Purpose.RECEIVED),
                DailyTokenCounter.remaining(90, 100))
    }

    @Test
    fun theDayIsTheLocalDayNotUtc() {
        // 2026-01-01T22:30Z is already the 2nd in Helsinki.
        val instant = ZonedDateTime.parse("2026-01-01T22:30:00Z").toInstant().toEpochMilli()
        Assert.assertEquals(
                "2026-01-02",
                DailyTokenCounter.dayOf(instant, ZoneId.of("Europe/Helsinki")))
        Assert.assertEquals("2026-01-01", DailyTokenCounter.dayOf(instant, ZoneId.of("UTC")))
    }

    @Test
    fun theNextWakeUpIsTheNextLocalMidnight() {
        val helsinki = ZoneId.of("Europe/Helsinki")
        // Midday on the 1st in Helsinki, so the count resets at local midnight on the 2nd.
        val midday = ZonedDateTime.parse("2026-01-01T10:00:00Z").toInstant().toEpochMilli()
        Assert.assertEquals(
                ZonedDateTime.parse("2026-01-02T00:00:00+02:00").toInstant().toEpochMilli(),
                DailyTokenCounter.startOfNextDay(midday, helsinki))

        // 22:30Z is already the 2nd in Helsinki, so the next reset is midnight on the 3rd.
        val lateEvening =
                ZonedDateTime.parse("2026-01-01T22:30:00Z").toInstant().toEpochMilli()
        val next = DailyTokenCounter.startOfNextDay(lateEvening, helsinki)
        Assert.assertEquals(
                ZonedDateTime.parse("2026-01-03T00:00:00+02:00").toInstant().toEpochMilli(), next)
        Assert.assertTrue(next > lateEvening)
    }
}
