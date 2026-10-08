package uk.xa0.tulkki.translation

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-day ledger: which day a call lands on, which tariff it is filed under, and what it adds to. */
class UsageLedgerTest {

    private val store = UsageLedger.MemoryStore()
    private val ledger = UsageLedger(store, ZoneId.of("UTC"))

    private fun utc(iso: String): Long {
        return ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    }

    @Test
    fun aPeakCallIsFiledUnderThePeakTriple() {
        ledger.record(utc("2026-10-12T02:00:00Z"), 100, 200, 50)

        val usage = store.read("2026-10-12")
        assertEquals(100, usage.peakCacheHit)
        assertEquals(200, usage.peakCacheMiss)
        assertEquals(50, usage.peakOutput)
        assertEquals(0, usage.offPeakTokens())
    }

    @Test
    fun anOffPeakCallIsFiledUnderTheOffPeakTriple() {
        // The same Monday, two hours later: 04:00 UTC is 12:00 Beijing, outside the window.
        ledger.record(utc("2026-10-12T04:00:00Z"), 100, 200, 50)

        val usage = store.read("2026-10-12")
        assertEquals(0, usage.peakTokens())
        assertEquals(100, usage.offPeakCacheHit)
        assertEquals(200, usage.offPeakCacheMiss)
        assertEquals(50, usage.offPeakOutput)
    }

    @Test
    fun bothTariffsCanShareOneLocalDay() {
        ledger.record(utc("2026-10-12T02:00:00Z"), 10, 20, 30)
        ledger.record(utc("2026-10-12T04:00:00Z"), 1, 2, 3)

        val usage = store.read("2026-10-12")
        assertEquals(60, usage.peakTokens())
        assertEquals(6, usage.offPeakTokens())
        assertEquals(66, usage.totalTokens())
    }

    @Test
    fun callsOnDifferentDaysAreDifferentRows() {
        ledger.record(utc("2026-10-12T02:00:00Z"), 10, 0, 0)
        ledger.record(utc("2026-10-13T02:00:00Z"), 20, 0, 0)

        assertEquals(10, store.read("2026-10-12").totalTokens())
        assertEquals(20, store.read("2026-10-13").totalTokens())
    }

    @Test
    fun theDayIsTheCallsOwnLocalDay() {
        // 22:30 UTC on the 1st is already the 2nd in Helsinki.
        val helsinki = UsageLedger(store, ZoneId.of("Europe/Helsinki"))
        helsinki.record(utc("2026-01-01T22:30:00Z"), 5, 0, 0)

        assertEquals("2026-01-02", helsinki.dayOf(utc("2026-01-01T22:30:00Z")))
        assertTrue(store.read("2026-01-02").totalTokens() > 0)
        assertEquals(0, store.read("2026-01-01").totalTokens())
    }

    @Test
    fun theDevicesTimeZoneCannotMoveACallToTheCheaperTariff() {
        // The same instant, filed by two ledgers whose local days differ: the tariff is the UTC one
        // in both, because peak is decided from the instant and never from the local day.
        val utc = UsageLedger(UsageLedger.MemoryStore(), ZoneId.of("UTC"))
        val auckland =
                UsageLedger(UsageLedger.MemoryStore(), ZoneId.of("Pacific/Auckland"))

        val instant = utc("2026-10-12T02:00:00Z")
        utc.record(instant, 1, 1, 1)
        auckland.record(instant, 1, 1, 1)

        val utcDay = utc.recentDays(1).get(0)
        val aucklandDay = auckland.recentDays(1).get(0)
        assertEquals(3, utcDay.usage.peakTokens())
        assertEquals(3, aucklandDay.usage.peakTokens())
        assertEquals(0, aucklandDay.usage.offPeakTokens())
    }

    @Test
    fun aCallThatReportedNothingIsNotARow() {
        ledger.record(utc("2026-10-12T02:00:00Z"), 0, 0, 0)

        assertTrue("a zero day would put a date on screen that nothing was spent on",
                ledger.recentDays().isEmpty())
    }

    @Test
    fun aNegativeCountCannotMakeADayCheaper() {
        ledger.record(utc("2026-10-12T02:00:00Z"), -50, -50, -50)

        assertTrue(ledger.recentDays().isEmpty())
    }

    @Test
    fun theRecentDaysAreNewestFirstAndBounded() {
        ledger.record(utc("2026-10-01T12:00:00Z"), 1, 0, 0)
        ledger.record(utc("2026-10-03T12:00:00Z"), 1, 0, 0)
        ledger.record(utc("2026-10-02T12:00:00Z"), 1, 0, 0)

        val days = ledger.recentDays(2)
        assertEquals(2, days.size)
        assertEquals("2026-10-03", days.get(0).day)
        assertEquals("2026-10-02", days.get(1).day)
    }

    @Test
    fun theTotalIsTheSumOfTheDaysShownAtThePricesInForce() {
        ledger.record(utc("2026-10-12T02:00:00Z"), 0, 1_000_000, 0)
        ledger.record(utc("2026-10-12T04:00:00Z"), 0, 1_000_000, 0)

        val days = ledger.recentDays()
        // 1M uncached input at peak (2.00) plus 1M at off-peak (1.00).
        assertEquals(3.0, UsageLedger.totalYuan(days, TokenPrices.defaults()), 1e-9)
    }

    @Test
    fun theScreenWindowIsTheOnlyBoundOnWhatIsShown() {
        // The store keeps every day; the ledger's own default is the screen's bound, not a pruning.
        for (day in 1..UsageLedger.SHOWN_DAYS + 5) {
            // No origin: this cell is about the window's bound, not about the breakdown.
            store.add(
                    java.lang.String.format("2026-01-%02d", day),
                    null,
                    TokenUsage.forCall(1, 0, 0, false))
        }
        assertEquals(UsageLedger.SHOWN_DAYS, ledger.recentDays().size)
        assertEquals(UsageLedger.SHOWN_DAYS + 5, store.recent(1_000).size)
    }
}
