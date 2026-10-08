package uk.xa0.tulkki.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import uk.xa0.tulkki.data.translation.UsageCounts
import uk.xa0.tulkki.translation.DeepSeekClient
import uk.xa0.tulkki.translation.PeakTariff
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.UsageLedger
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsFragment
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ledger, docs/MIGRATION.md "Design: the Compose UI" §3.0 - the first screen the Compose migration
 * rewrites, "because nothing device-facing is verified today" (§7).
 *
 * <p>Every value is drawn from [LedgerState] and nothing is read here: §7.4's "**Composables stay
 * dumb**: take a `Ui*` state, emit ids. No `ViewModel` that constructs an entity, no `remember` of a
 * service, no `LaunchedEffect` that reads SQLite." The three facts §3.0's state type has no member for
 * are parameters, exactly as the theme is: [enabled] (the interpreter's off switch, §3.0's off state),
 * [hasKey] (`settings.apiKey().isEmpty()` - §3.0's "no key" state, which the tree decides at
 * `UsageFragment.java:250-262` and which §3.0's declaration carries nowhere), and [notice] (a failed
 * refresh attempt's own words - §3.0.1 #5: the fourth string "is about a **refresh attempt**, not the
 * layer, and is deliberately not persisted: it belongs to `LedgerState.status`").
 *
 * <p>The copy is the screen's, from the string table the XML screen already used; the state's shapes are
 * `:translation`'s values, which is §3.0.1's reuse. The two long explanatory paragraphs §3.0 owes - the
 * spend note and the tariff's hours and holidays - are drawn here, over [LedgerProse]'s readings of the
 * same values, and were the first cut's one deliberate omission.
 *
 * <p>Tapping a day opens its split. The split is a reading of its own - `:data`'s
 * `TranslationUsageStore.byOrigin(day)`, a synchronous store call - so [drill] is a parameter like
 * [enabled] and [hasKey] rather than a member of [LedgerState]: the host makes the read off the main
 * thread and hands the assembled [DayDrill] back to the same screen.
 */
@Composable
fun LedgerScreen(
    state: LedgerState,
    prices: TokenPrices,
    enabled: Boolean,
    hasKey: Boolean,
    notices: LedgerNotices,
    drill: DayDrill?,
    onOpenDay: (String) -> Unit,
    onEditPrice: (UiPrice) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen paints the theme's own background: without a Surface the content colour falls back to
    // black on whatever the host drew behind it, which is what the first screenshot of this screen
    // caught - in dark mode. A screen is one surface, and this is it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        if (!enabled) {
            InterpreterOffCard(onOpenSettings)
            return@Surface
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.lg),
        ) {
            Balance(state, hasKey, notices.balance)
            Cap(state.tokens)
            Spend(state, prices, notices.models, drill, onOpenDay)
            Tariff(state, prices, notices.tariff, onEditPrice, onRefresh)
            Failure(state.failure)
        }
    }
}

/** §3.0's off state: "it draws one card - 'Interpretation is off' - with a control that opens the settings screen". */
@Composable
private fun InterpreterOffCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.lg)) {
        Column(
            modifier = Modifier.padding(TulkkiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
        ) {
            Text(stringResource(R.string.tulkki_interpreter_off_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tulkki_interpreter_off_line), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.tulkki_usage_open_settings))
            }
        }
    }
}

/**
 * §3.0.1 #1's three "there is none" readings, each its own sentence: "the account cannot pay"
 * (`!isAvailable`), "a reading with no yuan amount" (`!hasAmount()`), and "no reading at all"
 * (`read()` returns null, which "draws nothing").
 */
@Composable
private fun Balance(state: LedgerState, hasKey: Boolean, notice: String?) {
    val balance = state.balance
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
        Text(stringResource(R.string.tulkki_usage_balance_label), style = MaterialTheme.typography.titleSmall)
        when {
            balance != null -> {
                Text(
                    balance.displayAmount() ?: stringResource(R.string.tulkki_usage_no_balance),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.tulkki_usage_fetched, fetchedAt(balance.fetchedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
                val line =
                    when {
                        !balance.isAvailable -> R.string.tulkki_usage_cannot_pay
                        !balance.hasAmount() -> R.string.tulkki_usage_no_yuan
                        else -> null
                    }
                if (line != null) {
                    Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // No reading at all: nothing is drawn for the reading itself, but the two facts that
            // explain it are the screen's to state.
            !hasKey -> Text(stringResource(R.string.tulkki_usage_no_key), style = MaterialTheme.typography.bodyMedium)
            state.status == LedgerStatus.LOADING -> Text(stringResource(R.string.tulkki_usage_checking), style = MaterialTheme.typography.bodyMedium)
        }
        if (notice != null) {
            Text(stringResource(R.string.tulkki_usage_failed, notice), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** §3.0.1 #2: the bar's two numbers are the widget's, and no cap means the bar is not drawn at all. */
@Composable
private fun Cap(tokens: UiCapProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xs)) {
        Text(stringResource(R.string.tulkki_usage_tokens_title), style = MaterialTheme.typography.titleSmall)
        if (tokens.unlimited) {
            Text(stringResource(R.string.tulkki_usage_tokens_none), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LinearProgressIndicator(
            progress = { tokens.used.toFloat() / tokens.cap.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                R.string.tulkki_usage_tokens_of_cap,
                TulkkiSettingsFragment.formatTokens(tokens.used),
                TulkkiSettingsFragment.formatTokens(tokens.cap),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (tokens.exhausted) {
            Text(stringResource(R.string.tulkki_usage_cap_reached), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** §3.0's spend block: the note that explains the estimate, the rows, the total, and the models that price it. */
@Composable
private fun Spend(
    state: LedgerState,
    prices: TokenPrices,
    modelsNotice: String?,
    drill: DayDrill?,
    onOpenDay: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xs)) {
        Text(stringResource(R.string.tulkki_usage_spend_title), style = MaterialTheme.typography.titleSmall)
        SpendNote(state, prices)
        if (state.days.isEmpty()) {
            Text(stringResource(R.string.tulkki_usage_spend_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.tulkki_usage_spend_day_hint), style = MaterialTheme.typography.bodySmall)
            for ((index, day) in state.days.withIndex()) {
                if (index > 0) HorizontalDivider()
                DayRow(day, prices, drill?.takeIf { it.day == day.day }, onOpenDay)
            }
            Text(
                stringResource(
                    R.string.tulkki_usage_spend_total,
                    state.days.size,
                    TokenPrices.formatYuan(state.total),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Models(state.models, modelsNotice)
    }
}

/**
 * §3.0's first explanatory paragraph: what the estimate is made of, which model's published rates it is
 * priced at, the day those numbers were read, and the caveat that the tariff is decided from each call's
 * own instant while the day it is filed under is the phone's own.
 *
 * <p>Two sentences, as the XML screen drew them: `tulkki_usage_spend_note` when the row that prices the
 * app is a published one, and `_derived` - which says the number is an approximation - when it is not.
 * Everything the sentence needs is a reading of the state; [LedgerProse] holds the three that are
 * decisions, so the choice of sentence and the Beijing conversion are pinned by JVM tests rather than by
 * this composable.
 */
@Composable
private fun SpendNote(state: LedgerState, prices: TokenPrices) {
    val selection = state.models.selection
    Text(
        if (LedgerProse.spendNoteDerived(selection)) {
            stringResource(
                R.string.tulkki_usage_spend_note_derived,
                DeepSeekClient.DEFAULT_MODEL,
                selection.model.id,
                TokenPrices.format(prices.peakCacheHit),
                TokenPrices.format(prices.peakCacheMiss),
                TokenPrices.format(prices.peakOutput),
                LedgerProse.offPeakPercent(prices),
            )
        } else {
            stringResource(
                R.string.tulkki_usage_spend_note,
                DeepSeekClient.DEFAULT_MODEL,
                selection.model.id,
                LedgerProse.pricesTakenOn(selection, state.tariff),
                TokenPrices.format(prices.peakCacheHit),
                TokenPrices.format(prices.peakCacheMiss),
                TokenPrices.format(prices.peakOutput),
                LedgerProse.offPeakPercent(prices),
            )
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.tulkki_usage_spend_caveat),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * One day's row: the date, the six counts as the table spells them, and the figure - which is the same
 * `TokenPrices.yuan` the total uses, so an edited price re-prices the row and the sum together
 * (§3.0.1 #3).
 *
 * <p>The row is the tap target for the owner's drill-down, and the open day's split is drawn under it.
 * [drill] is non-null only for the day it is of, and it carries both the split and a failed read's own
 * words, so one day's figures or failure cannot appear under another day's row.
 */
@Composable
private fun DayRow(
    day: UsageLedger.Day,
    prices: TokenPrices,
    drill: DayDrill?,
    onOpenDay: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onOpenDay(day.day) },
        verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(day.day, style = MaterialTheme.typography.bodyMedium)
            Text(
                TokenPrices.formatYuan(prices.yuan(day.usage)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            stringResource(
                R.string.tulkki_usage_spend_day_tokens,
                TulkkiSettingsFragment.formatTokens(day.usage.totalTokens()),
                TulkkiSettingsFragment.formatTokens(day.usage.cacheHitTokens()),
                TulkkiSettingsFragment.formatTokens(day.usage.cacheMissTokens()),
                TulkkiSettingsFragment.formatTokens(day.usage.completionTokens()),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (drill?.breakdown != null) {
            Breakdown(drill.breakdown)
        }
        if (drill?.notice != null) {
            Text(stringResource(R.string.tulkki_usage_failed, drill.notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The open day's split, one line per bucket: the conversation's own address when the read resolved
 * one, and the kind's own label otherwise. The bucket's stored key is not on [OriginLine] at all, so
 * a deleted conversation cannot be printed as its uuid and the several-rooms batch cannot borrow the
 * not-tied wording.
 *
 * <p>A split that does not add up is a defect: [DayBreakdown.lines] is empty then and the one thing
 * drawn is the sentence that says so, because a breakdown whose parts do not make the whole is worse
 * than none.
 */
@Composable
private fun Breakdown(breakdown: DayBreakdown) {
    if (!breakdown.addsUp) {
        Text(stringResource(R.string.tulkki_usage_breakdown_broken), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (breakdown.lines.isEmpty()) {
        Text(stringResource(R.string.tulkki_usage_breakdown_empty), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
        for (line in breakdown.lines) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    line.address ?: stringResource(originKindLabel(line.kind)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(TokenPrices.formatYuan(line.yuan), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(
                    R.string.tulkki_usage_spend_day_tokens,
                    TulkkiSettingsFragment.formatTokens(line.usage.total()),
                    TulkkiSettingsFragment.formatTokens(hits(line.usage)),
                    TulkkiSettingsFragment.formatTokens(misses(line.usage)),
                    TulkkiSettingsFragment.formatTokens(outputs(line.usage)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The split's own three readings of a bucket's six counts, the same shape the day row prints. */
private fun hits(usage: UsageCounts): Int = usage.peakCacheHit + usage.offPeakCacheHit

private fun misses(usage: UsageCounts): Int = usage.peakCacheMiss + usage.offPeakCacheMiss

private fun outputs(usage: UsageCounts): Int = usage.peakOutput + usage.offPeakOutput

/**
 * §3.0.1 #4: the two sentences the selection's three-valued `listed` separates, and the key's own ids.
 */
@Composable
private fun Models(models: UiModels, notice: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
        Text(stringResource(R.string.tulkki_usage_models_title), style = MaterialTheme.typography.titleSmall)
        if (models.selection.listed == null) {
            Text(stringResource(R.string.tulkki_usage_models_unreadable, notice.orEmpty()), style = MaterialTheme.typography.bodyMedium)
        } else if (models.selection.appModelMissingFromKey()) {
            Text(
                stringResource(R.string.tulkki_usage_models_missing, models.selection.model.id),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
        } else if (models.ids.isEmpty()) {
            Text(stringResource(R.string.tulkki_usage_models_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(models.ids.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * §3.0.1 #5's layer line, picked by two nullness tests - `table == null` is the build's own numbers,
 * `schedule == null` is a page whose sentence about the peak hours could not be read - then §3.0's hours
 * and holidays paragraph, and the three fields, each opening the host's editor holding what is stored.
 *
 * <p>[prices] is the off-peak factor the hours line states, and it is the same value the rows above are
 * priced at: `TranslationSettings.tokenPrices` resolves it from the page when one has been read and the
 * build's own half otherwise, so the sentence and the arithmetic cannot disagree.
 */
@Composable
private fun Tariff(
    state: LedgerState,
    prices: TokenPrices,
    notice: String?,
    onEditPrice: (UiPrice) -> Unit,
    onRefresh: () -> Unit,
) {
    val table = state.tariff
    Column(verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xs)) {
        Text(stringResource(R.string.tulkki_usage_tariff_title), style = MaterialTheme.typography.titleSmall)
        val layer =
            when {
                table == null -> R.string.tulkki_usage_tariff_built_in
                table.schedule == null -> R.string.tulkki_usage_tariff_partial
                else -> R.string.tulkki_usage_tariff_fetched
            }
        Text(
            stringResource(layer, table?.readOn ?: TokenPrices.PRICES_TAKEN_ON),
            style = MaterialTheme.typography.bodySmall,
        )
        if (notice != null) {
            Text(stringResource(R.string.tulkki_usage_tariff_failed, notice), style = MaterialTheme.typography.bodyMedium)
        }
        // §3.0's second explanatory paragraph: the peak windows in the frame the page states them
        // (Beijing time), and which year's holiday ranges are the ones excluded from them. Both are
        // drawn whether or not a refresh failed, because the interesting fact after a failed read is
        // that the figures above are unchanged - which is what these two lines describe.
        Text(
            stringResource(
                R.string.tulkki_usage_tariff_hours,
                LedgerProse.beijingHours(LedgerProse.schedule(table)),
                LedgerProse.offPeakPercent(prices),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.tulkki_usage_tariff_holidays,
                PeakTariff.ChineseHolidays.LAST_LISTED_YEAR,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.tulkki_usage_prices_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.tulkki_usage_prices_hint), style = MaterialTheme.typography.bodySmall)
        for (price in state.prices) {
            Text(
                stringResource(R.string.tulkki_usage_price_row, priceLabel(price.field), TokenPrices.format(price.inForce)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { onEditPrice(price) },
            )
        }
        Button(onClick = onRefresh) { Text(stringResource(R.string.tulkki_usage_refresh)) }
    }
}

/**
 * §3.0's "Must never show: ... a failure `detail` that could contain one". `Failure.detail` has no
 * provenance in the tree (§3.0.1 #7 defers it), so the one thing this line may draw is the reason's own
 * vocabulary, through `TranslationText` - never the stored string.
 */
@Composable
private fun Failure(failure: UiFailure?) {
    if (failure == null) {
        return
    }
    Text(stringResource(TranslationText.failureOrNotKept(failure.reason)), style = MaterialTheme.typography.bodyMedium)
}

/**
 * The row's label for one price field, by the key that identifies it: "the field's identity is its key"
 * (section 3.0.1 #6), and the label is the string table's own - the same three the XML screen passed to
 * `tulkki_usage_price_row`, so the rewrite cannot rename a field.
 */
@Composable
private fun priceLabel(field: String): String =
    when (field) {
        LedgerAssembly.PRICE_FIELDS[0] -> stringResource(R.string.tulkki_price_cache_hit)
        LedgerAssembly.PRICE_FIELDS[1] -> stringResource(R.string.tulkki_price_cache_miss)
        LedgerAssembly.PRICE_FIELDS[2] -> stringResource(R.string.tulkki_price_output)
        else -> field
    }

/** The reading's own instant, in the phone's clock, as the XML screen drew it. */
private fun fetchedAt(millis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

/**
 * Section 3.0's per-source error reasons: "**error**: per source, not one banner". Section 3.0's
 * `LedgerState` carries none of the three - each is a read's own words, taken from the exception or the
 * response - so the host hands them in rather than the state inventing a member for each source.
 */
data class LedgerNotices(
    val balance: String? = null,
    val models: String? = null,
    val tariff: String? = null,
)
