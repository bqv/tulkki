package uk.xa0.tulkki.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

import java.time.ZoneId
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

import uk.xa0.tulkki.data.translation.TranslationUsageStore
import uk.xa0.tulkki.data.translation.UsageByOrigin
import uk.xa0.tulkki.translation.BalanceSnapshot
import uk.xa0.tulkki.translation.DailyTokenCounter
import uk.xa0.tulkki.translation.DeepSeekClient
import uk.xa0.tulkki.translation.TariffPage
import uk.xa0.tulkki.translation.TariffPageClient
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TranslationActivity
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.translation.TranslationStore
import uk.xa0.tulkki.translation.UsageLedger
import uk.xa0.tulkki.ui.activity.SettingsActivity
import uk.xa0.tulkki.ui.ledger.DayBreakdown
import uk.xa0.tulkki.ui.ledger.DayDrill
import uk.xa0.tulkki.ui.ledger.LedgerAssembly
import uk.xa0.tulkki.ui.ledger.LedgerBreakdown
import uk.xa0.tulkki.ui.ledger.LedgerHost
import uk.xa0.tulkki.ui.ledger.LedgerNotices
import uk.xa0.tulkki.ui.ledger.LedgerState
import uk.xa0.tulkki.ui.ledger.LedgerStatus

/**
 * Usage: the ledger's <em>host</em>. It reads, and [LedgerScreen] draws.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.0 rewrites this screen; §7.4 says why the two
 * halves are split this way - "**Composables stay dumb**: take a `Ui*` state, emit ids. No
 * `ViewModel` that constructs an entity, no `remember` of a service, no `LaunchedEffect` that reads
 * SQLite." So this class keeps exactly the reading it always had - the balance, the model list, the
 * price page, the ledger's days, the counter, the recent failures - and every drawing call is gone: it
 * assembles a [LedgerState] through [LedgerAssembly] and hands it to the screen, which is the `:ui`
 * half `ui-3` built and screenshots.
 *
 * <p>**What it reads, and where.** The balance and the model list are read once per visit, straight
 * from DeepSeek; the balance is remembered with the time it was read, and a failed read leaves the
 * last figure on screen with its age rather than emptying the screen. The price page is read without a
 * key (it is public), so a Ledger opened on a fresh install can still say what the app will charge. The
 * ledger's own days and the day's counter are the SQLite store, and the failures list is
 * [TranslationStore]'s; all three are read **on the executor**, because they are synchronous store
 * calls and the main thread is not the place for them.
 *
 * <p>`fragment_usage.xml` and `item_usage_day.xml` are deleted with this commit: every view this class
 * used to find by id is now a Composable, and the two layouts have no other reader.
 */
class UsageFragment : Fragment() {

    private val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var appContext: Context
    private lateinit var settings: TranslationSettings
    private lateinit var store: TranslationSettingsStore
    private var compose: ComposeView? = null

    /** True while a refresh is in flight, so a second tap cannot start a second pair of requests. */
    private var fetching = false

    /** Whether the interpreter is on: §3.0's off state is the screen's, and the predicate is the engine's. */
    private var enabled = true

    // The readings the state is assembled from. Each is the last successful one, so a failed refresh
    // changes nothing on screen except the line that says so.
    private var balance: BalanceSnapshot? = null
    private var days: List<UsageLedger.Day> = emptyList()
    private var usedTokens = 0

    /**
     * The day whose split is open, or null when none is. `TranslationUsageStore.byOrigin` is a
     * synchronous store call, so it is taken on [fetchExecutor] and never on the main thread; the
     * drill is set with no split first, which is the loading state on screen.
     */
    private var drill: DayDrill? = null

    /** The key's id list, or null when it could not be read at all (§3.0.1 #4's third state). */
    private var listed: List<String>? = null

    private var failure: TranslationFailures.Failure? = null
    private var balanceError: String? = null
    private var modelsError: String? = null
    private var tariffError: String? = null
    private var status: LedgerStatus = LedgerStatus.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        settings = TranslationSettings.get(appContext)
        store = TranslationSettingsStore(appContext)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View {
        val view = ComposeView(requireContext())
        // Dispose with the fragment's view, not with the window: the settings hierarchy detaches and
        // re-attaches this screen, and the default strategy would drop the composition with it.
        view.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose = view
        return view
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tulkki_usage_title)
        enabled = settings.interpreter().enabled()
        // Whatever we knew last time, at once and with its age; refresh() then corrects it.
        balance = BalanceSnapshot.read(appContext)
        render()
        refresh(tariffIsStale())
    }

    /** Whether the tariff in force is old enough that opening this screen should re-read the page. */
    private fun tariffIsStale(): Boolean {
        val table = settings.publishedTariff()
        if (table == null || table.readOn.isEmpty()) {
            return true
        }
        val weekAgo = System.currentTimeMillis() - TARIFF_STALE_DAYS * 24L * 60L * 60L * 1000L
        val oldest = DailyTokenCounter.dayOf(weekAgo, ZoneId.systemDefault())
        // ISO dates compare as strings, which is why the ledger stores them that way in the first
        // place; a date in the future (a clock that moved back) reads as fresh and is left alone.
        return table.readOn < oldest
    }

    override fun onDestroy() {
        super.onDestroy()
        fetchExecutor.shutdownNow()
    }

    /**
     * Reads the balance, the model list and - when asked - DeepSeek's price page, then the three local
     * readings, and re-renders once.
     *
     * <p>The three remote facts are one task because they are three facts about the same account, and
     * each fails on its own: a failed price page does not touch the balance and a failed balance does
     * not empty the spend estimate. The key is used for the first two and never logged.
     *
     * @param prices whether to re-read the pricing page, or to keep the tariff already in force
     */
    private fun refresh(prices: Boolean) {
        if (fetching) {
            return
        }
        val key = settings.apiKey()
        val hasKey = key.isNotEmpty()
        fetching = true
        status = if (hasKey) LedgerStatus.LOADING else LedgerStatus.IDLE
        render()

        val activity = requireActivity()
        fetchExecutor.execute {
            var read: BalanceSnapshot? = null
            var readError: String? = null
            var models: List<String>? = null
            var modelsFailure: String? = null
            if (hasKey) {
                try {
                    read =
                            BalanceSnapshot.of(
                                    DeepSeekClient(key).fetchBalance(),
                                    System.currentTimeMillis())
                    BalanceSnapshot.write(appContext, read)
                } catch (e: DeepSeekClient.TranslationException) {
                    readError = e.message
                }
                try {
                    models = DeepSeekClient(key).fetchModels().ids
                } catch (e: DeepSeekClient.TranslationException) {
                    modelsFailure = e.message
                }
            }
            val priceError = if (prices) readTariff() else null
            // The three local readings are synchronous store calls, so they are taken here rather
            // than on the main thread.
            val recentDays = settings.usageLedger().recentDays()
            val used =
                    settings
                            .tokenCounter()
                            .used(
                                    DailyTokenCounter.dayOf(
                                            System.currentTimeMillis(),
                                            ZoneId.systemDefault()))
            val newest = newestFailure()

            val balanceRead = read
            val failureText = readError
            val listedIds = models
            val modelsText = modelsFailure
            activity.runOnUiThread {
                fetching = false
                if (!isAdded) {
                    return@runOnUiThread
                }
                if (balanceRead != null) {
                    balance = balanceRead
                }
                // Before the balance, so the figures the model row prices are on screen whichever way
                // the balance read went.
                if (listedIds != null || modelsText != null) {
                    listed = listedIds
                    modelsError = modelsText
                }
                tariffError = priceError
                days = recentDays
                usedTokens = used
                failure = newest
                balanceError = failureText
                status = if (failureText == null) LedgerStatus.IDLE else LedgerStatus.FAILED
                render()
            }
        }
    }

    /**
     * The newest failure the app still holds, or `null`. Read from the same list the failures screen
     * shows, so the two cannot disagree about one message; a read that fails leaves the line off the
     * screen rather than failing the whole refresh.
     */
    private fun newestFailure(): TranslationFailures.Failure? {
        return try {
            val rows =
                    TranslationStore(appContext)
                            .recentTranslationFailures(settings.activity())
            if (rows.isEmpty()) null else rows[0]
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Reads the pricing page and keeps what it yields.
     *
     * <p>Returns why it failed, or `null` when the tariff in force is now the page's. A page that
     * fetches but does not parse is a failure like any other: the site answers `200` with its shell for
     * a route it does not have, so "it downloaded" says nothing about whether these bytes are the price
     * table.
     */
    private fun readTariff(): String? {
        return try {
            val document = TariffPageClient(DeepSeekClient.defaultHttp()).fetch()
            val table =
                    TariffPage.parse(
                            document,
                            TariffPage.CNY_URL,
                            DailyTokenCounter.dayOf(
                                    System.currentTimeMillis(), ZoneId.systemDefault()))
            if (table == null) {
                // Nothing is written, so the prices in force are the ones that were there before -
                // the whole point of parsing before storing.
                getString(R.string.tulkki_usage_tariff_unreadable)
            } else {
                settings.setPublishedTariff(table)
                null
            }
        } catch (e: DeepSeekClient.TranslationException) {
            e.message
        } catch (e: RuntimeException) {
            e.message
        }
    }

    /** One price, as a dialog with a single decimal field. */
    private fun editPrice(field: String) {
        val dialogTitle: Int =
                when (field) {
                    LedgerAssembly.PRICE_FIELDS[0] -> R.string.tulkki_price_cache_hit_dialog
                    LedgerAssembly.PRICE_FIELDS[1] -> R.string.tulkki_price_cache_miss_dialog
                    else -> R.string.tulkki_price_output_dialog
                }
        val editor = EditText(requireContext())
        editor.inputType =
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val stored = store.getString(field, "")
        editor.setText(if (stored == null) "" else stored)
        AlertDialog.Builder(requireContext())
                .setTitle(dialogTitle)
                .setView(editor)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    store.putString(field, editable(editor).toString())
                    // The same screen redraws itself: the day rows and the total are computed from the
                    // price, so a correction has to re-price them on the spot rather than at the next
                    // visit.
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * `EditText.getText` is nullable to Kotlin and the Java dereferenced it here; the field always
     * carries an Editable, so this names the NullPointerException the Java would have raised rather
     * than asserting with `!!`.
     */
    private fun editable(input: EditText): Editable =
            input.text ?: throw NullPointerException("no editable text")

    /**
     * Opens - or, on a second tap of the same day, closes - one day's split.
     *
     * <p>The read is `TranslationUsageStore.byOrigin`, a synchronous walk of two tables on the app's
     * encrypted database, so it is submitted to [fetchExecutor] exactly as the day rows and the counter
     * are. The tapped day is remembered by [drill], and a result that arrives after the owner has
     * closed it or opened another day is dropped rather than drawn under the wrong row.
     */
    private fun openDay(day: String) {
        val open = drill
        if (open != null && day == open.day) {
            drill = null
            render()
            return
        }
        drill = DayDrill(day, null, null)
        render()
        val activity = requireActivity()
        fetchExecutor.execute {
            var read: DayBreakdown? = null
            var error: String? = null
            try {
                val byOrigin: UsageByOrigin =
                        TranslationUsageStore.get(appContext).byOrigin(day)
                read = LedgerBreakdown.of(byOrigin, settings.tokenPrices())
            } catch (e: RuntimeException) {
                error = e.message
            }
            val result = read
            val failure = error
            activity.runOnUiThread {
                val current = drill
                if (!isAdded || current == null || day != current.day) {
                    return@runOnUiThread
                }
                drill = DayDrill(day, result, failure)
                render()
            }
        }
    }

    /** Opens the screen where the two languages - and so the interpreter - are set. */
    private fun openSettings() {
        val intent = Intent(requireActivity(), SettingsActivity::class.java)
        intent.putExtra(
                SettingsActivity.EXTRA_SETTINGS_FRAGMENT,
                TulkkiSettingsFragment::class.java.name)
        startActivity(intent)
    }

    /** Assembles the state from the readings and hands it to the screen. */
    private fun render() {
        val view = compose ?: return
        val key = settings.apiKey()
        val hasKey = key.isNotEmpty()
        val prices = settings.tokenPrices()
        val state: LedgerState =
                LedgerAssembly.assemble(
                        balance,
                        usedTokens,
                        settings.dailyTokenCap(),
                        days,
                        prices,
                        DeepSeekClient.DEFAULT_MODEL,
                        listed,
                        storedPrices(),
                        settings.publishedTariff(),
                        failure,
                        status)
        LedgerHost.show(
                view,
                state,
                prices,
                enabled,
                hasKey,
                LedgerNotices(balanceError, modelsError, tariffError),
                drill,
                darkTheme(),
                Consumer { field -> editPrice(field) },
                Consumer { day -> openDay(day) },
                Runnable { refresh(true) },
                Runnable { openSettings() })
    }

    /** The three stored price fields, by the keys the preference screen writes. */
    private fun storedPrices(): Map<String, String> {
        val stored = HashMap<String, String>()
        for (field in LedgerAssembly.PRICE_FIELDS) {
            val value = store.getString(field, "")
            stored[field] = value ?: ""
        }
        return stored
    }

    /**
     * Whether the Compose tree is the dark one. The stored preference is what decides it - "a stored
     * preference always wins over the system" - and it reaches the resources through
     * `AppCompatDelegate.setDefaultNightMode`, applied at start-up and in `BaseActivity`, so the
     * Activity's own configuration is the answer and the screen consults no setting.
     */
    private fun darkTheme(): Boolean {
        val mode =
                requireContext().resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TARIFF_STALE_DAYS = 7
    }
}
