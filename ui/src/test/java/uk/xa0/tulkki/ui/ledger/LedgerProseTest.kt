package uk.xa0.tulkki.ui.ledger

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.PeakTariff
import uk.xa0.tulkki.translation.TariffPage
import uk.xa0.tulkki.translation.TokenPrices

/**
 * §3.0's two explanatory paragraphs, cell by cell. §7.4 asks for exactly this shape: "Screen state is a
 * pure reducer, not a Composable ... in plain Kotlin with JVM tests next to them", and the readings the
 * two sentences are made of are decisions, not prose.
 *
 * <p>The one that matters most is the **frame change**: the windows are held as minutes of a UTC day
 * (`PeakTariff.Schedule`) while the sentence the owner reads says "Beijing time" (UTC+8). A cell that
 * found the UTC minutes on screen unchanged, or a shift applied twice, is the bug this file exists for.
 */
class LedgerProseTest {

    @Test
    fun theShippedWindowsReadAsThePageStatesThemInBeijingTime() {
        Assert.assertEquals(
            "the build ships Beijing 09:00-12:00 and 14:00-18:00, held as 01:00-04:00 and 06:00-10:00 UTC",
            "09:00-12:00 and 14:00-18:00",
            LedgerProse.beijingHours(PeakTariff.Schedule.SHIPPED),
        )
    }

    @Test
    fun aPageScheduleIsShiftedAndNotHardcoded() {
        // Morning 120-180 and afternoon 360-540 are UTC minutes, so Beijing is 10:00-11:00 and 14:00-17:00.
        Assert.assertEquals(
            "the hours follow the schedule in force, not the shipped pair",
            "10:00-11:00 and 14:00-17:00",
            LedgerProse.beijingHours(PeakTariff.Schedule(120, 180, 360, 540)),
        )
    }

    @Test
    fun aWindowIsShiftedIntoTheBeijingDayItBelongsTo() {
        // 990-1050 and 1080-1140 UTC are 16:30-17:30 and 18:00-19:00 UTC, i.e. the next Beijing day's
        // morning: 00:30-01:30 and 02:00-03:00. A plain `%` would spell these before midnight.
        Assert.assertEquals(
            "a UTC window whose shift crosses midnight reads as the Beijing morning it is",
            "00:30-01:30 and 02:00-03:00",
            LedgerProse.beijingHours(PeakTariff.Schedule(990, 1050, 1080, 1140)),
        )
    }

    @Test
    fun theHoursInForceAreThePagesOrTheBuilds() {
        Assert.assertSame(
            "no page read at all is the build's own windows",
            PeakTariff.Schedule.SHIPPED,
            LedgerProse.schedule(null),
        )
        Assert.assertSame(
            "a page whose hours sentence could not be read keeps the build's windows",
            PeakTariff.Schedule.SHIPPED,
            LedgerProse.schedule(table(schedule = false)),
        )
        Assert.assertEquals(
            "a page that stated its hours is the schedule in force",
            PeakTariff.Schedule(120, 180, 360, 540),
            LedgerProse.schedule(table(schedule = true)),
        )
    }

    @Test
    fun theDayIsThePagesOwnOnlyWhenThePageListsTheRowThatPricesTheApp() {
        val flash = flashSelection()
        Assert.assertEquals(
            "the page lists the row, so the day is the page's",
            PAGE_READ_ON,
            LedgerProse.pricesTakenOn(flash, table(schedule = true, model = "deepseek-flash")),
        )
        Assert.assertEquals(
            "the page prices a different model, so it cannot lend its date to this one",
            TokenPrices.PRICES_TAKEN_ON,
            LedgerProse.pricesTakenOn(flash, table(schedule = true, model = "deepseek-v4-pro")),
        )
        Assert.assertEquals(
            "no page read at all is the build's day",
            TokenPrices.PRICES_TAKEN_ON,
            LedgerProse.pricesTakenOn(flash, null),
        )
    }

    @Test
    fun theDerivedSentenceIsInsuranceBecauseNoShippedRowIsDerived() {
        Assert.assertFalse(
            "the shipped flash row is a published one, so the note takes the published wording",
            LedgerProse.spendNoteDerived(flashSelection()),
        )
        // The reason the `_derived` branch is unreachable today, stated as a cell rather than a comment:
        // this reddens the day a row the table can pick from stops being published, which is the day the
        // branch becomes a real sentence instead of insurance.
        for (model in TokenPrices.models()) {
            Assert.assertTrue(
                "every row the build's table holds is published (${model.id})",
                model.published,
            )
        }
    }

    @Test
    fun theOffPeakLineIsTheFactorInForceAsAPercentage() {
        Assert.assertEquals(
            "the shipped half is stated as 50, not 0.5",
            "50",
            LedgerProse.offPeakPercent(TokenPrices.defaults()),
        )
        Assert.assertEquals(
            "a page's stated fraction reaches the sentence, not the build's half",
            "60",
            LedgerProse.offPeakPercent(
                TokenPrices.of(null, null, null, TokenPrices.defaultModel(), 0.6)
            ),
        )
    }

    private fun flashSelection(): TokenPrices.Selection =
        TokenPrices.select(TokenPrices.defaultModel().id, listOf(TokenPrices.defaultModel().id))

    /** A stored tariff, through the same `fromJson` the settings store reads it back with. */
    private fun table(schedule: Boolean, model: String = "deepseek-flash"): TariffPage.Table {
        val peak =
            if (model == "deepseek-v4-pro") "[0.30,9,27]" else "[0.04,2,8]"
        val offPeak =
            if (model == "deepseek-v4-pro") "[0.15,4.5,13.5]" else "[0.02,1,4]"
        val hours = if (schedule) """"schedule":{"morning":[120,180],"afternoon":[360,540]},""" else ""
        val json =
            """
            {"source":"https://api-docs.deepseek.com/quick_start/pricing",
             "readOn":"$PAGE_READ_ON",$hours
             "factor":0.5,
             "models":[{"id":"$model","peak":$peak,"offPeak":$offPeak}]}
            """
                .trimIndent()
        val parsed = TariffPage.fromJson(json)
        Assert.assertNotNull("the fixture must be a tariff the settings store would keep", parsed)
        return parsed!!
    }

    private companion object {
        const val PAGE_READ_ON = "2026-10-01"
    }
}
