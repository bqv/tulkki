package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three editable prices, as the settings hold them.
 *
 * <p>The settings are the process-wide in-memory instance every other JVM test uses, so this pins the
 * part the screen depends on: an untouched install gets the embedded defaults, an edit is used, and a
 * blank or unparseable field goes back to the default rather than to a free app.
 */
class TranslationSettingsPricesTest {

    private val TOLERANCE = 1e-9

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
    }

    @After
    fun tearDown() {
        // The instance is process-wide on purpose; leave it as a fresh install for the next test.
        TranslationSettings.inMemory()
    }

    @Test
    fun anUntouchedInstallUsesTheEmbeddedDefaults() {
        val prices = settings.tokenPrices()
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, prices.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, prices.peakCacheMiss, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, prices.peakOutput, TOLERANCE)
        assertEquals(TokenPrices.FLASH, prices.model)
    }

    @Test
    fun anEditedPriceIsUsedAndShownBack() {
        settings.setPeakCacheHitPrice("0.1")
        settings.setPeakCacheMissPrice("5")
        settings.setPeakOutputPrice("20")

        val prices = settings.tokenPrices()
        assertEquals(0.1, prices.peakCacheHit, TOLERANCE)
        assertEquals(5.0, prices.peakCacheMiss, TOLERANCE)
        assertEquals(20.0, prices.peakOutput, TOLERANCE)
        assertEquals("0.1", settings.peakCacheHitPrice())
        assertEquals("5", settings.peakCacheMissPrice())
        assertEquals("20", settings.peakOutputPrice())
    }

    @Test
    fun aBlankOrUnparseablePriceIsTheDefaultRatherThanFree() {
        settings.setPeakCacheHitPrice("")
        settings.setPeakCacheMissPrice("   ")
        settings.setPeakOutputPrice("free")

        val prices = settings.tokenPrices()
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, prices.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, prices.peakCacheMiss, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, prices.peakOutput, TOLERANCE)
        assertTrue(
                "a zero price would claim the app is free",
                prices.yuan(TokenUsage.forCall(1_000_000, 1_000_000, 1_000_000, false)) > 0)
    }

    @Test
    fun theFallbackRowCanBeNamedForTheModelTheKeyReports() {
        val prices = settings.tokenPrices(TokenPrices.V4_PRO)
        assertEquals(TokenPrices.V4_PRO.peakCacheHit, prices.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.V4_PRO, prices.model)
    }

    @Test
    fun theLedgerIsReadableThroughTheSettings() {
        assertNotNull(settings.usageLedger())
        settings.recordUsage(
                java.time.ZonedDateTime.parse("2026-10-12T04:00:00Z").toInstant().toEpochMilli(),
                DeepSeekClient.Usage(20, 30, 0, 20, 50))
        assertEquals(1, settings.usageLedger().recentDays().size)
        assertEquals(50, settings.usageLedger().recentDays().get(0).usage.totalTokens())
    }

    @Test
    fun aRefreshedTableIsTheDefaultAndTheOwnersOwnNumberStillWins() {
        // The order of the three layers is the whole point of the feature: the page's numbers replace
        // the build's as the thing the fields fall back to, and a field the owner has filled in beats
        // both, because what they wrote is a judgement and a fetched table is only a better-informed
        // default. The factor travels with the table, so a page that stopped halving would stop being
        // halved here.
        val row =
                TariffPage.Row("deepseek-flash", 0.05, 3.00, 9.00, 0.02, 1.20, 3.60)
        settings.setPublishedTariff(
                TariffPage.Table(
                        listOf(row),
                        0.4,
                        PeakTariff.Schedule(0, 180, 360, 600),
                        "2026-09-28",
                        TariffPage.CNY_URL))

        val refreshed = settings.tokenPrices(TokenPrices.FLASH)
        assertEquals(0.05, refreshed.peakCacheHit, TOLERANCE)
        assertEquals(3.00, refreshed.peakCacheMiss, TOLERANCE)
        assertEquals(9.00, refreshed.peakOutput, TOLERANCE)
        assertEquals(0.4, refreshed.offPeakFactor, TOLERANCE)
        assertNotNull(settings.tariffSchedule())
        assertEquals(0, settings.tariffSchedule()!!.morningStartMinutes)

        settings.setPeakCacheMissPrice("2.5")
        val edited = settings.tokenPrices(TokenPrices.FLASH)
        assertEquals("the owner's number wins over the page's", 2.5, edited.peakCacheMiss, TOLERANCE)
        assertEquals("and the page still supplies the ones they did not write", 0.05, edited.peakCacheHit, TOLERANCE)

        // Clearing the table is how the build's own numbers and hours come back - the same gesture as
        // clearing a prompt field, and the only way back that cannot drift from what shipped.
        settings.clearPublishedTariff()
        assertEquals(
                TokenPrices.DEFAULT_PEAK_CACHE_HIT,
                settings.tokenPrices(TokenPrices.FLASH).peakCacheHit,
                TOLERANCE)
        assertEquals(TokenPrices.OFF_PEAK_FACTOR, settings.tokenPrices().offPeakFactor, TOLERANCE)
        assertNull(settings.tariffSchedule())
        assertEquals("the edited field is still the owner's", "2.5", settings.peakCacheMissPrice())
    }

    @Test
    fun anUnusableTableIsNotKept() {
        // A table that fails its own checks must not be stored: the alternative is a Ledger priced
        // from a half-written value, which is the one direction nobody can check by looking.
        settings.setPublishedTariff(null)
        assertNull(settings.publishedTariff())
        settings.setPublishedTariff(
                TariffPage.Table(
                        listOf(
                                TariffPage.Row("deepseek-flash", 0.0, 2.0, 8.0, 0.0, 1.0, 4.0)),
                        0.5,
                        null,
                        "2026-09-28",
                        TariffPage.CNY_URL))
        assertNull("a zero price is not a table", settings.publishedTariff())
    }
}
