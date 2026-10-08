package uk.xa0.tulkki.ui.ledger

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.BalanceSnapshot
import uk.xa0.tulkki.translation.DailyTokenCounter
import uk.xa0.tulkki.translation.TariffPage
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TokenUsage
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.UsageLedger
import uk.xa0.tulkki.ui.TranslationSettingsStore

/**
 * The ledger's state cells, the JVM half docs/MIGRATION.md "Design: the Compose UI" §7.4 asks for:
 * "**Screen state is a pure reducer**, not a Composable ... and the `LedgerState` assembly all live in
 * plain Kotlin with JVM tests next to them."
 *
 * <p>What they hold is §3.0.1's central claim: five of the seven state types are **reuse**, so a cell
 * that finds a wrapper here is a cell that caught a second spelling of a reading `:translation` already
 * answers.
 */
class LedgerStateTest {

    @Test
    fun theStateCarriesTheTreesOwnValuesAndWrapsNothing() {
        val types =
            LedgerState::class.java.declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") }
                .associate { it.name to it.type }
        Assert.assertEquals("balance is the snapshot itself, not a formatted pair (§3.0.1 #1)", BalanceSnapshot::class.java, types.getValue("balance"))
        Assert.assertEquals(java.util.List::class.java, types.getValue("days"))
        Assert.assertEquals("tariff is TariffPage.Table? (§3.0.1 #5)", TariffPage.Table::class.java, types.getValue("tariff"))
        Assert.assertEquals(
            "failure reuses TranslationFailures.Failure wholesale (§3.0.1 #7)",
            TranslationFailures.Failure::class.java,
            types.getValue("failure"),
        )
        Assert.assertEquals(UiCapProgress::class.java, types.getValue("tokens"))
        Assert.assertEquals(UiModels::class.java, types.getValue("models"))
        Assert.assertEquals(java.util.List::class.java, types.getValue("prices"))
        Assert.assertEquals(java.lang.Double.TYPE, types.getValue("total"))
        Assert.assertEquals(LedgerStatus::class.java, types.getValue("status"))
    }

    @Test
    fun theCapProgressIsTheCountersOwnReading() {
        val cases = listOf(5 to 10, 10 to 10, 12 to 10, 5 to 0, 0 to 0)
        for ((used, cap) in cases) {
            val progress = UiCapProgress(used, cap)
            Assert.assertEquals("exhausted($used, $cap)", DailyTokenCounter.exhausted(used, cap), progress.exhausted)
            Assert.assertEquals("remaining($used, $cap)", DailyTokenCounter.remaining(used, cap), progress.remaining)
            Assert.assertEquals("unlimited($used, $cap)", cap <= DailyTokenCounter.UNLIMITED, progress.unlimited)
        }
        Assert.assertEquals(
            "the sentinel is the counter's own, so a cap of 0 means no cap and never no tokens",
            DailyTokenCounter.UNLIMITED,
            UiCapProgress.UNLIMITED,
        )
    }

    @Test
    fun theThreePriceRowsAreTheThreeStoredKeys() {
        val state = assemble(prices = TokenPrices.defaults(), stored = mapOf(PRICE_MISS to "0.5"))
        Assert.assertEquals(
            "the field's identity is the preference key the screen must keep writing (§3.0.1 #6)",
            listOf(
                TranslationSettingsStore.KEY_PRICE_CACHE_HIT,
                TranslationSettingsStore.KEY_PRICE_CACHE_MISS,
                TranslationSettingsStore.KEY_PRICE_OUTPUT,
            ),
            state.prices.map { it.field },
        )
        val inForce = TokenPrices.defaults()
        Assert.assertEquals(inForce.peakCacheHit, state.prices[0].inForce, 0.0)
        Assert.assertEquals(inForce.peakCacheMiss, state.prices[1].inForce, 0.0)
        Assert.assertEquals(inForce.peakOutput, state.prices[2].inForce, 0.0)
        Assert.assertEquals("a stored value is carried raw, for the dialog to open holding it", "0.5", state.prices[1].stored)
        Assert.assertEquals("a field nobody has set is the empty string, not a number", "", state.prices[0].stored)
    }

    @Test
    fun theTotalIsTheLedgersOwnSumOverTheSameRows() {
        val days =
            listOf(
                UsageLedger.Day("2026-09-29", TokenUsage.forCall(100, 200, 300, true)),
                UsageLedger.Day("2026-09-28", TokenUsage.forCall(400, 500, 600, false)),
            )
        val prices = TokenPrices.defaults()
        val state = assemble(days = days, prices = prices)
        Assert.assertEquals(UsageLedger.totalYuan(days, prices), state.total, 0.0)
        Assert.assertSame("the rows the total sums are the rows the list draws", days, state.days)
        Assert.assertEquals("no days is no spend, not a null", 0.0, assemble(days = null).total, 0.0)
    }

    @Test
    fun theModelsCarryTheSelectionsThreeValuedListed() {
        val appModel = TokenPrices.defaultModel().id
        val listed = assemble(appModel = appModel, listedIds = listOf(appModel, "deepseek-other"))
        Assert.assertEquals(true, listed.models.selection.listed)
        Assert.assertFalse(listed.models.selection.appModelMissingFromKey())
        Assert.assertEquals("the key's own ids are carried, so the screen can name them", 2, listed.models.ids.size)

        val missing = assemble(appModel = appModel, listedIds = listOf("deepseek-other"))
        Assert.assertEquals(false, missing.models.selection.listed)
        Assert.assertTrue("'the app's model is missing' is loud, and this is that state", missing.models.selection.appModelMissingFromKey())

        val unreadable = assemble(appModel = appModel, listedIds = null)
        Assert.assertNull("null is 'the list could not be read', a different sentence", unreadable.models.selection.listed)
        Assert.assertEquals(emptyList<String>(), unreadable.models.ids)
    }

    private fun assemble(
        days: List<UsageLedger.Day>? = null,
        prices: TokenPrices = TokenPrices.defaults(),
        appModel: String? = null,
        listedIds: List<String>? = emptyList(),
        stored: Map<String, String> = emptyMap(),
    ): LedgerState =
        LedgerAssembly.assemble(
            balance = null,
            used = 0,
            cap = 0,
            days = days,
            prices = prices,
            appModel = appModel,
            listedIds = listedIds,
            storedPrices = stored,
            tariff = null,
            failure = null,
            status = LedgerStatus.IDLE,
        )

    private companion object {
        val PRICE_MISS = TranslationSettingsStore.KEY_PRICE_CACHE_MISS
    }
}
