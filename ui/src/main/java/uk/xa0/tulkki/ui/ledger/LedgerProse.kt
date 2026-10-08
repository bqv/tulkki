package uk.xa0.tulkki.ui.ledger

import java.util.Locale
import uk.xa0.tulkki.translation.PeakTariff
import uk.xa0.tulkki.translation.TariffPage
import uk.xa0.tulkki.translation.TokenPrices

/**
 * §3.0's two explanatory paragraphs, as the readings behind them: the **spend note** (what the estimate
 * is made of, and which day the numbers it is priced at were read on) and the **tariff's hours and
 * holidays**.
 *
 * <p>Both were drawn by the XML screen this rewrite replaced - `UsageFragment.showSpendNote` and
 * `UsageFragment.showTariff` - and the first cut of [LedgerScreen] deliberately left them out, saying
 * they "land with the host that replaces `UsageFragment`". The host has landed, so they land here.
 *
 * <p>**Why plain Kotlin and not the Composable.** §7.4: "Screen state is a pure reducer, not a
 * Composable ... the `LedgerState` assembly all live in plain Kotlin with JVM tests next to them." The
 * choice of sentence and the hours' conversion are decisions, and one of them - the windows are held as
 * minutes of a **UTC** day while the sentence the owner reads is **Beijing** time - is the kind that is
 * easy to get subtly wrong and cheap to pin.
 *
 * <p>Nothing here reads a setting, a database or the clock: the state the screen was handed and the
 * prices in force are the whole input.
 */
object LedgerProse {

    /**
     * Whether the note takes §3.0's derived wording, because the row that prices this app is not a row
     * the page published.
     *
     * <p>The tree states the rule where the flag lives: "A derived row is shown as an assumption on the
     * screen; it is never quietly presented as a published price" (`TokenPrices.Model.published`).
     *
     * <p>**A seam, named rather than hidden:** every row the build's table holds is `published = true`
     * today - the Pro row was a derived guess and was then corrected against the page - and
     * `TokenPrices.select` only ever picks from that table, so this is **false for every selection the
     * tree can currently produce**. It is kept because the flag is the tree's, not this screen's, and
     * with it the derived sentence the string table still carries; deleting the branch would be
     * deleting the only reader of `Model.published`.
     */
    fun spendNoteDerived(selection: TokenPrices.Selection): Boolean = !selection.model.published

    /**
     * The day the numbers in force were read off: the page's own day when the page lists the row that
     * prices this app, and the build's day otherwise - so a row the page does not list cannot borrow the
     * page's date, which is the same rule `showSpendNote` applied.
     */
    fun pricesTakenOn(selection: TokenPrices.Selection, tariff: TariffPage.Table?): String =
        if (tariff != null && tariff.row(selection.model.id) != null) {
            tariff.readOn
        } else {
            TokenPrices.PRICES_TAKEN_ON
        }

    /**
     * The peak windows in force: the page's, when its sentence about the hours could be read, and the
     * build's otherwise. A page whose prices were usable but whose hours sentence was not keeps the
     * shipped hours, which the layer line already says.
     */
    fun schedule(tariff: TariffPage.Table?): PeakTariff.Schedule =
        tariff?.schedule ?: PeakTariff.Schedule.SHIPPED

    /**
     * The windows as the owner reads them - **Beijing time** - joined the way the sentence expects:
     * "Peak: %1$s Beijing time, Monday to Friday ...".
     *
     * <p>The conversion is the page's own frame, not the caller's: [PeakTariff.Schedule] holds minutes
     * of a UTC day, and the sentence they were read from is written in Beijing time (UTC+8, no daylight
     * saving), so this is the inverse of `TariffPage.schedule`'s shift and must move together with it.
     */
    fun beijingHours(schedule: PeakTariff.Schedule): String =
        window(schedule.morningStartMinutes, schedule.morningEndMinutes) +
            " and " +
            window(schedule.afternoonStartMinutes, schedule.afternoonEndMinutes)

    /** The off-peak price as a percentage of the peak one, in the screen's own number formatting. */
    fun offPeakPercent(prices: TokenPrices): String = TokenPrices.format(prices.offPeakFactor * 100)

    private fun window(startUtcMinutes: Int, endUtcMinutes: Int): String =
        hhmm(startUtcMinutes) + "-" + hhmm(endUtcMinutes)

    /**
     * UTC minutes from midnight as Beijing time. `floorMod` rather than `%` because a window whose UTC
     * start is late in the day belongs to the next Beijing morning, and a negative remainder would
     * spell it as a time before midnight.
     */
    private fun hhmm(utcMinutes: Int): String {
        val beijing = Math.floorMod(utcMinutes + BEIJING_OFFSET_MINUTES, MINUTES_PER_DAY)
        return String.format(Locale.ROOT, "%02d:%02d", beijing / 60, beijing % 60)
    }

    /** Beijing is UTC+8 with no daylight saving, so a fixed offset is the honest reading. */
    private const val BEIJING_OFFSET_MINUTES = 8 * 60

    private const val MINUTES_PER_DAY = 24 * 60
}
