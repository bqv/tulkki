package uk.xa0.tulkki.ui.ledger

import uk.xa0.tulkki.translation.BalanceSnapshot
import uk.xa0.tulkki.translation.DailyTokenCounter
import uk.xa0.tulkki.translation.TariffPage
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.UsageLedger
import uk.xa0.tulkki.ui.TranslationSettingsStore

/**
 * The usage screen's state, docs/MIGRATION.md "Design: the Compose UI" §3.0, with every member decided by
 * §3.0.1 "The missing definitions".
 *
 * <p>§3.0.1's pattern is §2.2.1's: "a `:ui` type reuses a `:translation` value rather than copying it,
 * and **five of the seven are reuse** - the Ledger screen's logic is already device-free and its values
 * already cross the module boundary". So four of the names below are `typealias`es and not wrappers:
 * a second spelling of a reading that already exists is a second answer to the same question.
 */
typealias UiBalance = BalanceSnapshot

typealias UiDay = UsageLedger.Day

typealias UiFailure = TranslationFailures.Failure

/** §3.0.1 #5: "the layer is `TariffPage.Table?`", and §3.0's three strings are a line, not a case. */
typealias UiTariff = TariffPage.Table?

/**
 * §3.0.1 #2: the two raw ints and the no-cap sentinel - "there is no percentage in the tree", so the
 * bar's ratio is the widget's and never a stored field.
 *
 * <p>The readers are `:translation`'s own ([DailyTokenCounter.exhausted] / [DailyTokenCounter.remaining])
 * rather than a second arithmetic in `:ui`.
 */
data class UiCapProgress(val used: Int, val cap: Int) {

    /** A cap at or below the sentinel means no cap: the screen hides the bar and says so. */
    val unlimited: Boolean get() = cap <= UNLIMITED

    val exhausted: Boolean get() = DailyTokenCounter.exhausted(used, cap)

    val remaining: Int get() = DailyTokenCounter.remaining(used, cap)

    companion object {
        /** `DailyTokenCounter.UNLIMITED`, which is 0 - a cap of 0 is "no cap", never "no tokens". */
        const val UNLIMITED: Int = DailyTokenCounter.UNLIMITED
    }
}

/**
 * §3.0.1 #4: reuse `TokenPrices.Selection` plus the key's own id list.
 *
 * <p>The selection's three-valued `listed` is what separates §3.0's two error sentences: `false` is
 * "the app's model is missing from the list" (loud), `null` is "the list could not be read".
 */
data class UiModels(val selection: TokenPrices.Selection, val ids: List<String>)

/**
 * §3.0.1 #6: one editable price field, carrying both numbers the screen needs.
 *
 * <p>[field] is the field's identity and it is its key - `TranslationSettingsStore.KEY_PRICE_*` - which
 * "the Compose screen must keep writing": the raw preference key is the contract, and a Compose screen
 * that wrote a different spelling would be invisible to every reader of the setting.
 *
 * <p>[inForce] is the number drawn on the row (`TokenPrices.format` is the screen's); [stored] is the
 * raw stored string, which is what the dialog opens holding, so blank is the way back to the page's
 * number and blank, unparseable or non-positive falls back to the row in force - **never to zero**
 * (`TokenPrices.resolve`, the tree's own convention, which this type keeps rather than reinterprets).
 */
data class UiPrice(val field: String, val inForce: Double, val stored: String)

/** §3.0's `status: Loading|Idle|Failed`; the fourth string, `_failed`, is a refresh attempt's. */
enum class LedgerStatus {
    LOADING,
    IDLE,
    FAILED,
}

/**
 * §3.0's declaration, field for field.
 *
 * <p>One deviation §3.0.1 #1 explicitly leaves to this row: [balance] is **nullable**. The tree's
 * `BalanceSnapshot.read()` returns `null` for "no reading at all" and the screen "draws nothing" for it;
 * §3.0's non-nullable `balance` is the section's own contradiction with the tree, and §3.0.1 defers
 * where "never fetched" lives to the reducer rather than inventing a member on the snapshot. It lives
 * here.
 *
 * <p>[tariff]'s three layer strings are the screen's line, picked by two nullness tests (§3.0.1 #5);
 * [failure] is the one failure the app keeps, not a list (§3.2).
 */
data class LedgerState(
    val balance: UiBalance?,
    val tokens: UiCapProgress,
    val days: List<UiDay>,
    val total: Double,
    val models: UiModels,
    val tariff: UiTariff,
    val prices: List<UiPrice>,
    val failure: UiFailure?,
    val status: LedgerStatus,
)

/**
 * The state's assembly, plain Kotlin with JVM tests beside it - §7.4: "**Screen state is a pure
 * reducer**, not a Composable: ... and the `LedgerState` assembly all live in plain Kotlin with JVM
 * tests next to them."
 *
 * <p>Everything that is a derivation happens here and nowhere else: the total is
 * [UsageLedger.totalYuan] over the very rows the list draws (one computation over one input - §3.0.1
 * #3), the model in force is [TokenPrices.select] over the key's ids, and each price row's in-force
 * number is the [TokenPrices] the screen was handed. The readings themselves are the caller's: this
 * class reads no settings, no database and no clock.
 */
object LedgerAssembly {

    /** The three fields, in the screen's order, each named by the preference key it writes. */
    @JvmField
    val PRICE_FIELDS: List<String> =
        listOf(
            TranslationSettingsStore.KEY_PRICE_CACHE_HIT,
            TranslationSettingsStore.KEY_PRICE_CACHE_MISS,
            TranslationSettingsStore.KEY_PRICE_OUTPUT,
        )

    fun assemble(
        balance: BalanceSnapshot?,
        used: Int,
        cap: Int,
        days: List<UsageLedger.Day>?,
        prices: TokenPrices,
        appModel: String?,
        listedIds: List<String>?,
        storedPrices: Map<String, String>,
        tariff: TariffPage.Table?,
        failure: TranslationFailures.Failure?,
        status: LedgerStatus,
    ): LedgerState {
        val rows = days ?: emptyList()
        return LedgerState(
            balance = balance,
            tokens = UiCapProgress(used = used, cap = cap),
            days = rows,
            total = UsageLedger.totalYuan(rows, prices),
            models = UiModels(TokenPrices.select(appModel, listedIds), listedIds ?: emptyList()),
            tariff = tariff,
            prices = priceRows(prices, storedPrices),
            failure = failure,
            status = status,
        )
    }

    private fun priceRows(prices: TokenPrices, stored: Map<String, String>): List<UiPrice> =
        listOf(
            UiPrice(PRICE_FIELDS[0], prices.peakCacheHit, stored[PRICE_FIELDS[0]].orEmpty()),
            UiPrice(PRICE_FIELDS[1], prices.peakCacheMiss, stored[PRICE_FIELDS[1]].orEmpty()),
            UiPrice(PRICE_FIELDS[2], prices.peakOutput, stored[PRICE_FIELDS[2]].orEmpty()),
        )
}
