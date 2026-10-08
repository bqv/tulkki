package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The price page, read against the page.
 *
 * <p>Every test here runs on a verbatim slice of DeepSeek's own CNY price table
 * ({@code src/test/resources/pricepage/zh-pricing.html}, re-takeable with
 * {@code tools/refresh-price-fixture}), because the failure this class guards against is not "the
 * parser has a bug" but "the page changed and the parser kept confidently answering": a synthetic
 * fixture would keep passing while the real page moved under it.
 *
 * <p>The refusals matter more than the successes. A refresh that yields nothing keeps the numbers in
 * force, which is a screen the owner can argue with; a refresh that yields the wrong column is a
 * screen that is quietly wrong in the one direction nobody can check by looking at it.
 */
class TariffPageTest {

    /** The real slice, as the page served it. */
    private fun page(): String {
        try {
            val resource =
                    TariffPageTest::class.java.getResourceAsStream("/pricepage/zh-pricing.html")
            return resource.use { stream ->
                assertNotNull("the price-page fixture is missing from the test resources", stream)
                String(stream!!.readAllBytes(), StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            throw AssertionError("the price-page fixture could not be read", e)
        }
    }

    private fun read(document: String?): TariffPage.Table? {
        return TariffPage.parse(document, TariffPage.CNY_URL, "2026-09-27")
    }

    @Test
    fun thePagesOwnFiguresAreReadForBothModels() {
        val table = read(page())!!
        assertNotNull(table)

        val flash = table.row("deepseek-flash")!!
        assertNotNull(flash)
        assertEquals(0.04, flash.peakCacheHit, 1e-9)
        assertEquals(2.00, flash.peakCacheMiss, 1e-9)
        assertEquals(8.00, flash.peakOutput, 1e-9)
        assertEquals(0.02, flash.offPeakCacheHit, 1e-9)
        assertEquals(1.00, flash.offPeakCacheMiss, 1e-9)
        assertEquals(4.00, flash.offPeakOutput, 1e-9)

        val pro = table.row("deepseek-v4-pro")!!
        assertNotNull(pro)
        assertEquals(0.30, pro.peakCacheHit, 1e-9)
        assertEquals(9.00, pro.peakCacheMiss, 1e-9)
        assertEquals(27.00, pro.peakOutput, 1e-9)
    }

    @Test
    fun thePagesOwnFiguresAgreeWithTheBuildsShippedRow() {
        // Not a tautology: it is the check that the numbers embedded in TokenPrices are still the
        // page's. If DeepSeek repriced Flash, this test fails on the *build's* constants - which is
        // the signal to move them and cut a release, even though a running app could now refresh
        // instead of waiting for one.
        val table = read(page())!!
        val page_row = table.row("deepseek-flash")!!.model()

        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, page_row.peakCacheHit, 1e-9)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, page_row.peakCacheMiss, 1e-9)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, page_row.peakOutput, 1e-9)
    }

    @Test
    fun thePagesOffPeakSentenceIsReadAsTheFactor() {
        val table = read(page())!!
        assertEquals(0.5, table.offPeakFactor, 1e-9)
        assertEquals("2026-09-27", table.readOn)
        assertEquals(TariffPage.CNY_URL, table.source)
    }

    @Test
    fun thePagesPeakHoursAreReadInUtc() {
        // The footnote states Beijing 09:00-12:00 and 14:00-18:00, so the schedule the ledger holds
        // is 01:00-04:00 and 06:00-10:00 UTC - and it is the shipped schedule, which is the honest
        // reading of "the page has not moved the hours".
        val table = read(page())!!
        assertNotNull(table.schedule)
        val schedule = table.schedule!!
        assertEquals(60, schedule.morningStartMinutes)
        assertEquals(240, schedule.morningEndMinutes)
        assertEquals(360, schedule.afternoonStartMinutes)
        assertEquals(600, schedule.afternoonEndMinutes)
        assertTrue(schedule.isShipped())
    }

    @Test
    fun aPageWithNoTableIsRefused() {
        assertNull(read("<html><body><p>Nothing to see.</p></body></html>"))
        assertNull(read(""))
        assertNull(read(null))
        // The site answers 200 with its shell for a route it does not have, so this is the case a
        // wrong URL produces - and the only thing that can catch it is the parse.
        assertNull(read(page().replace("<tr", "<row")))
    }

    @Test
    fun aTableThatIsNotInYuanIsRefused() {
        // The English page publishes the same table in dollars. Storing those as yuan would put a
        // silent factor of seven under every figure on the Ledger, so the unit is part of the parse.
        val dollars = page().replace("元", " USD")
        assertNull(read(dollars))
    }

    @Test
    fun aTableWhoseOffPeakIsNotTheStatedFractionIsRefused() {
        // One edited figure is enough: the three ratios no longer agree, so the app's model of the
        // tariff - three peak prices and a factor - is not this table's, and half-applying it would
        // be a number the owner cannot check.
        assertNull(read(page().replace(">4元<", ">3.5元<")))
    }

    @Test
    fun aTableWhoseRowsContradictEachOtherIsRefused() {
        // Two rows whose off-peak/peak ratios disagree cannot both be a fraction of the same
        // tariff. Built by swapping the two input rows' off-peak figures for Flash, which is what
        // a column that moved under a parser anchored on labels would look like.
        val swapped =
                page().replace("<td>0.02元</td><td>0.15元</td>", "<td>1元</td><td>0.15元</td>")
                        .replace("<td>1元</td><td>4.5元</td>", "<td>0.02元</td><td>4.5元</td>")
        assertNull(read(swapped))
    }

    @Test
    fun aRowWithNoNumbersIsRefused() {
        // A column of dashes is not a price of zero: the whole table goes, and the build's own numbers
        // stay in force.
        assertNull(read(page().replace("<td>0.04元</td><td>0.30元</td>", "<td>-</td><td>-</td>")))
    }

    @Test
    fun theModelVersionRowIsNotMistakenForTheModelRow() {
        // The page has a "model version" row whose cells read exactly like model ids
        // (DeepSeek-V4.1-Flash). Inserted *before* the real header here, so nothing but the rule that
        // a row whose ids the price table already knows wins can save the parse.
        val poisoned =
                page().replace(
                        "<table style=\"text-align:center\">",
                        "<table style=\"text-align:center\"><tr><td colspan=\"3\">模型版本</td>"
                                + "<td>DeepSeek-V4.1-Flash</td><td>DeepSeek-V4-Pro-0813</td></tr>")
        val table = read(poisoned)!!
        assertNotNull(table)
        assertNotNull(table.row("deepseek-flash"))
        assertNotNull(table.row("deepseek-v4-pro"))
        assertNull(table.row("deepseek-v4.1-flash"))
    }

    @Test
    fun aPageThatLostTheWeekdayRuleStillYieldsItsPrices() {
        // Prices and hours are separate facts. The footnote's sentence about the hours is required to
        // still say "Monday to Friday" and "public holidays", because those are the shapes of the rule
        // the code implements; when it does not, the hours are left alone while the prices are kept.
        val table = read(page().replace("周一至周五", "每天"))!!
        assertNotNull(table)
        assertNotNull(table.row("deepseek-flash"))
        assertNull(table.schedule)
    }

    @Test
    fun aModelThePageDoesNotListKeepsTheBuildsOwnRow() {
        val table = read(page())!!
        val effective = table.effective(TokenPrices.V4_PRO)!!
        // The page does list Pro, so this is the page's row...
        assertEquals(0.30, effective.peakCacheHit, 1e-9)
        // ...while a model nobody lists is priced from the build's row rather than from an invented
        // one, which is the whole rule: a refresh may only move numbers the page actually states.
        val unknown =
                TokenPrices.Model("deepseek-something-else", 1.0, 2.0, 3.0, false)
        assertNull(table.row("deepseek-something-else"))
        assertEquals(unknown, table.effective(unknown))
    }

    @Test
    fun whatIsStoredReadsBackAsWhatWasRead() {
        val table = read(page())!!
        val stored = TariffPage.fromJson(TariffPage.toJson(table))!!

        assertNotNull(stored)
        assertEquals(table.offPeakFactor, stored.offPeakFactor, 1e-9)
        assertEquals(table.readOn, stored.readOn)
        assertEquals(table.source, stored.source)
        assertEquals(table.rows.size, stored.rows.size)
        for (row in table.rows) {
            val back = stored.row(row.id)!!
            assertNotNull(back)
            assertEquals(row.peakCacheHit, back.peakCacheHit, 1e-9)
            assertEquals(row.peakCacheMiss, back.peakCacheMiss, 1e-9)
            assertEquals(row.peakOutput, back.peakOutput, 1e-9)
            assertEquals(row.offPeakCacheHit, back.offPeakCacheHit, 1e-9)
            assertEquals(row.offPeakCacheMiss, back.offPeakCacheMiss, 1e-9)
            assertEquals(row.offPeakOutput, back.offPeakOutput, 1e-9)
        }
        assertEquals(table.schedule, stored.schedule)
    }

    @Test
    fun aStoredTableThatIsNotATariffIsRefused() {
        assertNull(TariffPage.fromJson(""))
        assertNull(TariffPage.fromJson(null))
        assertNull(TariffPage.fromJson("not json at all"))
        assertNull(TariffPage.fromJson("{}"))
        assertNull(TariffPage.fromJson("[]"))
        // A factor of one or more is not a discount, and a missing one is not a default: the whole
        // table goes back to the build's numbers rather than being half-believed.
        assertNull(
                TariffPage.fromJson(
                        "{\"factor\":1.5,\"models\":[{\"id\":\"deepseek-flash\","
                                + "\"peak\":[0.04,2,8],\"offPeak\":[0.02,1,4]}]}"))
        assertNull(
                TariffPage.fromJson(
                        "{\"models\":[{\"id\":\"deepseek-flash\","
                                + "\"peak\":[0.04,2,8],\"offPeak\":[0.02,1,4]}]}"))
        // And a table whose prices are not prices is refused on the way out too, so a corrupted or
        // half-written value cannot price the ledger from a hole.
        assertNull(
                TariffPage.fromJson(
                        "{\"factor\":0.5,\"models\":[{\"id\":\"deepseek-flash\","
                                + "\"peak\":[0,2,8],\"offPeak\":[0.02,1,4]}]}"))
    }

    @Test
    fun aStoredTableWithAnImpossibleScheduleKeepsItsPricesAndLosesItsHours() {
        // The schedule is optional in storage as it is in the page: a table written by an older build,
        // or one whose hours did not survive, still prices the ledger.
        val stored =
                TariffPage.fromJson(
                        "{\"factor\":0.5,\"readOn\":\"2026-09-27\",\"source\":\"x\","
                                + "\"models\":[{\"id\":\"deepseek-flash\","
                                + "\"peak\":[0.04,2,8],\"offPeak\":[0.02,1,4]}]}")!!
        assertNotNull(stored)
        assertNull(stored.schedule)
        assertNotNull(stored.row("deepseek-flash"))
        assertFalse(stored.isEmpty())
    }

    @Test
    fun thePageIsReadAsTextRatherThanAsMarkup() {
        // The parser walks the table's rows and reads the words in its cells; entities and tags must
        // not end up in a price, and a row's cells must not run together.
        assertEquals("\u4ef7\u683c (2)", TariffPage.text("价格<sup>(2)</sup>"))
        assertEquals("a & b c", TariffPage.text("a &amp; b <b>c</b>"))
        assertEquals(2, TariffPage.tableRows("<tr><td>a</td><td>b</td></tr><tr><td>c</td></tr>").size)
    }
}
