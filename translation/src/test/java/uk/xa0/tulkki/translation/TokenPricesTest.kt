package uk.xa0.tulkki.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prices, the money arithmetic, and which model row is used for what the key reports. */
class TokenPricesTest {

    private val TOLERANCE = 1e-9

    private fun peak(hit: Int, miss: Int, output: Int): TokenUsage {
        return TokenUsage.forCall(hit, miss, output, true)
    }

    private fun offPeak(hit: Int, miss: Int, output: Int): TokenUsage {
        return TokenUsage.forCall(hit, miss, output, false)
    }

    // -- the arithmetic ---------------------------------------------------------------------------

    @Test
    fun aDayWithNoTokensCostsNothing() {
        assertEquals(0.0, TokenPrices.defaults().yuan(TokenUsage.zero()), TOLERANCE)
    }

    @Test
    fun eachPriceIsPerMillionTokens() {
        val prices = TokenPrices.defaults()
        assertEquals(0.04, prices.yuan(peak(1_000_000, 0, 0)), TOLERANCE)
        assertEquals(2.00, prices.yuan(peak(0, 1_000_000, 0)), TOLERANCE)
        assertEquals(8.00, prices.yuan(peak(0, 0, 1_000_000)), TOLERANCE)
    }

    @Test
    fun theThreeKindsAddUp() {
        val prices = TokenPrices.defaults()
        // 1M of each at 0.04 + 2 + 8 = 10.04.
        assertEquals(10.04, prices.yuan(peak(1_000_000, 1_000_000, 1_000_000)), TOLERANCE)
    }

    @Test
    fun offPeakIsExactlyHalf() {
        val prices = TokenPrices.defaults()
        val atPeak = peak(500, 3_000, 7_000)
        val atOffPeak = offPeak(500, 3_000, 7_000)
        assertEquals(prices.yuan(atPeak) / 2.0, prices.yuan(atOffPeak), TOLERANCE)
    }

    @Test
    fun bothTariffsInOneDayAreBothCharged() {
        val prices = TokenPrices.defaults()
        val mixed = peak(1_000_000, 0, 0).plus(offPeak(1_000_000, 0, 0))
        assertEquals(0.04 + 0.02, prices.yuan(mixed), TOLERANCE)
    }

    @Test
    fun aSmallOrdinaryDayIsStillSomething() {
        // 12k cached input, 1k uncached input and 800 output: not zero, which is the whole reason the
        // screen prints four decimals.
        val prices = TokenPrices.defaults()
        val yuan = prices.yuan(offPeak(12_000, 1_000, 800))
        assertEquals((12_000 * 0.02 + 1_000 * 1.00 + 800 * 4.00) / 1_000_000.0, yuan, TOLERANCE)
        assertTrue(yuan > 0)
        assertEquals("\u00a50.0044", TokenPrices.formatYuan(yuan))
    }

    // -- the fallbacks ----------------------------------------------------------------------------

    @Test
    fun blankPricesFallBackToTheEmbeddedDefaults() {
        val prices = TokenPrices.of("", "   ", null)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, prices.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, prices.peakCacheMiss, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, prices.peakOutput, TOLERANCE)
    }

    @Test
    fun unparseableZeroAndNegativePricesFallBackRatherThanBecomingFree() {
        val prices = TokenPrices.of("not a number", "0", "-1")
        assertEquals(
                "a zero price would silently claim the app is free",
                TokenPrices.DEFAULT_PEAK_CACHE_HIT,
                prices.peakCacheHit,
                TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, prices.peakCacheMiss, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, prices.peakOutput, TOLERANCE)
        assertTrue("an unreadable price must never cost nothing", prices.yuan(peak(0, 0, 1_000_000)) > 0)
    }

    @Test
    fun aStoredPriceWinsWhenItIsUsable() {
        val prices = TokenPrices.of("0.08", "4", "16")
        assertEquals(0.08, prices.peakCacheHit, TOLERANCE)
        assertEquals(16.00, prices.yuan(peak(0, 0, 1_000_000)), TOLERANCE)
    }

    @Test
    fun aBlankFieldFallsBackToTheRowInForceNotToTheCheapRow() {
        val prices = TokenPrices.of("", "", "", TokenPrices.V4_PRO)
        assertEquals(TokenPrices.V4_PRO.peakCacheHit, prices.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.V4_PRO.peakOutput, prices.peakOutput, TOLERANCE)
    }

    // -- the model rows ---------------------------------------------------------------------------

    @Test
    fun theModelThisAppSendsHasAPriceRow() {
        assertNotNull(
                "the app's own model must be priced by a row of its own",
                TokenPrices.model(DeepSeekClient.DEFAULT_MODEL))
        assertEquals(
                TokenPrices.FLASH, TokenPrices.model(DeepSeekClient.DEFAULT_MODEL))
    }

    @Test
    fun theListConfirmsTheModelTheAppSends() {
        val selection =
                TokenPrices.select(DeepSeekClient.DEFAULT_MODEL, listOf("deepseek-v4-pro", "deepseek-flash"))
        assertEquals(TokenPrices.FLASH, selection.model)
        assertTrue(selection.known)
        assertEquals(java.lang.Boolean.TRUE, selection.listed)
        assertFalse(selection.appModelMissingFromKey())
    }

    @Test
    fun aKeyThatDoesNotOfferTheAppsModelSaysSoLoudly() {
        val selection =
                TokenPrices.select(DeepSeekClient.DEFAULT_MODEL, listOf("deepseek-v4-pro"))
        assertTrue(selection.appModelMissingFromKey())
        // The row in force is still the app's own model's: the warning is the loud part, and the
        // estimate above it must not be emptied by a list that disagrees.
        assertEquals(TokenPrices.FLASH, selection.model)
    }

    @Test
    fun anUnreadableListIsNotTheSameAnswerAsAMissingModel() {
        val selection =
                TokenPrices.select(DeepSeekClient.DEFAULT_MODEL, null)
        assertNull("unread is unknown, not absent", selection.listed)
        assertFalse(selection.appModelMissingFromKey())
        assertEquals(TokenPrices.FLASH, selection.model)
    }

    @Test
    fun anUnknownModelIsPricedFromTheCheapestRowTheKeyActuallyOffers() {
        // A legacy or misspelled name: the key's own list is the only evidence about what serves it,
        // and a legacy chat name is billed as the cheap model, not the expensive one.
        val selection =
                TokenPrices.select("deepseek-legacy-chat", listOf("deepseek-v4-pro", "deepseek-flash"))
        assertFalse(selection.known)
        assertEquals(TokenPrices.FLASH, selection.model)
    }

    @Test
    fun anUnknownModelWithNoUsableListIsPricedFromTheMostExpensiveRow() {
        // Nothing is known, so the estimate must err high rather than quietly price an unknown model
        // at the cheap row.
        val selection =
                TokenPrices.select("who-knows", listOf("something-else"))
        assertFalse(selection.known)
        assertEquals(TokenPrices.V4_PRO, selection.model)
        assertEquals(TokenPrices.V4_PRO, TokenPrices.inForceFor("who-knows"))
    }

    // -- formatting -------------------------------------------------------------------------------

    @Test
    fun pricesAreShownWithoutTrailingNoise() {
        assertEquals("0.04", TokenPrices.format(0.04))
        assertEquals("2", TokenPrices.format(2.0))
        assertEquals("8", TokenPrices.format(8.0))
        assertEquals("0.125", TokenPrices.format(0.125))
    }

    @Test
    fun theYuanFigureKeepsFourDecimalsSoAnOrdinaryDayIsNotZero() {
        assertEquals("\u00a50.0000", TokenPrices.formatYuan(0.0))
        assertEquals("\u00a50.0840", TokenPrices.formatYuan(0.084))
        assertEquals("\u00a512.3456", TokenPrices.formatYuan(12.3456))
    }

    @Test
    fun theModelListIsShownAsOneLineWithDuplicatesDropped() {
        assertEquals(
                "deepseek-flash, deepseek-v4-pro",
                TokenPrices.joinIds(TokenPrices.distinctIds(listOf("deepseek-flash", "", " deepseek-v4-pro ", "DEEPSEEK-FLASH"))))
    }

    @Test
    fun aPaddedDuplicateFoldsIntoItsFirstSighting() {
        // Until this row the stored (trimmed) id was compared against the incoming *untrimmed* one, so
        // the padded third entry was a second "deepseek-flash" and the line showed it twice. The
        // repair is deliberate and this test is what stops the old comparison being re-inherited.
        assertEquals(
                "deepseek-flash, deepseek-v4-pro",
                TokenPrices.joinIds(
                        TokenPrices.distinctIds(
                                listOf(" deepseek-flash ", "deepseek-v4-pro", "  DEEPSEEK-FLASH  "))))
        // The first sighting wins and the order stays the key's, which padding must not disturb.
        assertEquals(listOf("b", "a"), TokenPrices.distinctIds(listOf(" b ", " a ", "b", "a")))
    }
}
