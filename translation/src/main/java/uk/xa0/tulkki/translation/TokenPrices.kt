package uk.xa0.tulkki.translation

import java.util.Locale

/**
 * What DeepSeek charges, and the one place the app turns tokens into yuan.
 *
 * <p><strong>This is an estimate, and it is the only kind DeepSeek makes possible.</strong> The API
 * exposes no spend and no usage history at all - only `GET /user/balance`, which is the
 * account's current balance and is shared by every key on the account, and `GET /models`, which
 * names the models a key may use but reports no prices. So the app can only do the arithmetic itself,
 * from the `usage` object every completion already returns, against a price table. That is why
 * the per-day figures are labelled as an estimate on the screen, and why the numbers are editable
 * rather than believed.
 *
 * <p>The prices are <strong>peak</strong> prices in CNY per 1,000,000 tokens, taken from DeepSeek's
 * pricing page on [PRICES_TAKEN_ON]:
 *
 * <ul>
 *   <li>input tokens served from the prompt cache: [DEFAULT_PEAK_CACHE_HIT]
 *   <li>input tokens that missed the cache: [DEFAULT_PEAK_CACHE_MISS]
 *   <li>output tokens: [DEFAULT_PEAK_OUTPUT]
 * </ul>
 *
 * <p><strong>Off-peak is exactly half</strong> ([OFF_PEAK_FACTOR]), which is DeepSeek's own
 * published rule and is therefore a constant here rather than a fourth, fifth and sixth setting: the
 * owner edits the peak numbers and the halving follows.
 *
 * <p><strong>The table has one row per model</strong> ([models]). A row exists for the model
 * this app sends and for the more expensive one the same page lists, and the row in force is chosen
 * from the model ids the key itself reports ([select]) rather than assumed, because choosing
 * the wrong row is worth roughly a factor of three and nothing else in the app could notice. A row
 * the pricing page did not spell out for this build is marked [Model.published] `false`,
 * so the screen can say which numbers are read and which are derived.
 *
 * <p>Pure Kotlin and no Android types: the arithmetic, the model selection and the fallbacks are pinned
 * by JVM tests.
 */
class TokenPrices private constructor(
        model: Model,
        peakCacheHit: Double,
        peakCacheMiss: Double,
        peakOutput: Double,
        offPeakFactor: Double) {

    /** CNY per 1M tokens, at peak. Off-peak is half of each. */
    @JvmField val peakCacheHit: Double = peakCacheHit

    @JvmField val peakCacheMiss: Double = peakCacheMiss

    @JvmField val peakOutput: Double = peakOutput

    /** The row these numbers came from, so the screen can name the model they are for. */
    @JvmField val model: Model = model

    /**
     * What off-peak is worth against peak: the page's own factor, which is a half today.
     *
     * <p>It is a value rather than the constant alone because the published sentence that states it is
     * one of the things a refresh reads - "off-peak is half of peak" is a price, and a page that
     * changed it to, say, sixty percent would otherwise be halved by an app that never looked.
     */
    @JvmField val offPeakFactor: Double = if (offPeakFactor > 0) offPeakFactor else OFF_PEAK_FACTOR

    /**
     * One model's peak price row, CNY per 1M tokens.
     *
     * <p>The model names are DeepSeek's, not the app's: the app sends exactly one
     * ([DeepSeekClient.DEFAULT_MODEL]) and the others are here so that the row in force is a
     * lookup rather than a constant.
     *
     * <p><strong>Equality is Kotlin's, and that is a decision rather than an inheritance.</strong>
     * The Java this replaces was a plain `final class` with no `equals`/`hashCode`, so two rows
     * holding the same numbers were two different objects to it; a `data class` compares and hashes
     * by value. Nothing compares two rows for equality - [TariffPage.Table.effective] answers the
     * row it was handed, and every other use reads `id`, the three rates or `published` - so the
     * difference is not observable, and the value reading is the one an immutable price row wants.
     * `PeakTariff.Schedule` is the same decision with the reason reversed: its Java hand-wrote
     * `equals`, so `data class` reproduces it rather than introducing it.
     */
    @ConsistentCopyVisibility
    data class Model internal constructor(
            /** The model id as `GET /models` reports it. */
            @JvmField val id: String,
            @JvmField val peakCacheHit: Double,
            @JvmField val peakCacheMiss: Double,
            @JvmField val peakOutput: Double,
            /**
             * True when these are the page's own numbers for this id, false when the page's Pro row was
             * not captured and the numbers were derived. A derived row is shown as an assumption on the
             * screen; it is never quietly presented as a published price.
             */
            @JvmField val published: Boolean) {

        /** The most expensive of the three rates, for ordering rows without inventing a currency. */
        internal fun weight(): Double = peakCacheMiss + peakOutput

        override fun toString(): String =
                id +
                        " " +
                        TokenPrices.format(peakCacheHit) +
                        "/" +
                        TokenPrices.format(peakCacheMiss) +
                        "/" +
                        TokenPrices.format(peakOutput) +
                        " CNY per 1M peak" +
                        (if (published) "" else " (derived)")

        companion object {

            /**
             * A row built from numbers a refresh read off DeepSeek's own pricing page.
             *
             * <p>It is marked [published] because that is exactly what the flag means - these are
             * the page's numbers for this id rather than numbers this build derived - so everything that
             * already treats a published row as the trustworthy one keeps working without a second flag
             * for "and it was read recently". When it was read is the table's business, not the row's.
             */
            @JvmStatic
            fun published(
                    id: String,
                    peakCacheHit: Double,
                    peakCacheMiss: Double,
                    peakOutput: Double): Model =
                    Model(id, peakCacheHit, peakCacheMiss, peakOutput, true)
        }
    }

    /**
     * Which row prices this app, and what the key's own list says about it.
     *
     * <p>`listed` is deliberately three-valued: `null` means the list could not be read
     * at all, which is a different sentence on screen from "the key does not offer the model the app
     * sends". Neither empties the estimate.
     *
     * <p>Equality is [Model]'s decision applied to the same shape: the Java had identity equality and
     * this `data class` has value equality, and nothing compares two of these in a branch - a caller
     * reads [model], [known] or [listed] - so the difference is not observable.
     */
    @ConsistentCopyVisibility
    data class Selection internal constructor(
            /** The row whose numbers price the app, before the owner's edits. */
            @JvmField val model: Model,
            /** True when the app's own model id is one the table knows. */
            @JvmField val known: Boolean,
            /** Whether the key lists the app's own model, or `null` when the list is unknown. */
            @JvmField val listed: Boolean?) {

        /** True when the app sends a model the key does not offer: a retired or misspelled name. */
        fun appModelMissingFromKey(): Boolean = listed == false
    }

    /**
     * What one ledger row cost, in yuan: the six token counts against the three peak prices, with the
     * off-peak triple at half.
     *
     * <p>Nothing negative can come out of it, because [TokenUsage] clamps its counts at zero.
     * A day whose tokens are all zero costs nothing, which is the answer a fresh install's row must
     * give rather than a null or a default.
     */
    fun yuan(usage: TokenUsage?): Double {
        if (usage == null) {
            return 0.0
        }
        val peak =
                usage.peakCacheHit * peakCacheHit +
                        usage.peakCacheMiss * peakCacheMiss +
                        usage.peakOutput * peakOutput
        val offPeak =
                (usage.offPeakCacheHit * peakCacheHit +
                        usage.offPeakCacheMiss * peakCacheMiss +
                        usage.offPeakOutput * peakOutput) * offPeakFactor
        return (peak + offPeak) / TOKENS_PER_PRICE_UNIT
    }

    override fun toString(): String =
            model.id +
                    " peak " +
                    format(peakCacheHit) +
                    "/" +
                    format(peakCacheMiss) +
                    "/" +
                    format(peakOutput) +
                    " CNY per 1M, off-peak " +
                    format(offPeakFactor * 100) +
                    "%"

    companion object {

        /** The day the published defaults were read off DeepSeek's pricing page. Shown on the screen. */
        const val PRICES_TAKEN_ON = "2026-09-27"

        /** CNY per 1M cached input tokens, at peak: the pricing page's Flash row. */
        const val DEFAULT_PEAK_CACHE_HIT = 0.04

        /** CNY per 1M uncached input tokens, at peak. */
        const val DEFAULT_PEAK_CACHE_MISS = 2.00

        /** CNY per 1M output tokens, at peak. */
        const val DEFAULT_PEAK_OUTPUT = 8.00

        /** DeepSeek's published rule: off-peak is half of peak, always. */
        const val OFF_PEAK_FACTOR = 0.5

        /** What the prices are denominated in. The figure on screen is yuan. */
        const val CURRENCY_SYMBOL = "\u00a5"

        private const val TOKENS_PER_PRICE_UNIT = 1_000_000.0

        /** The pricing page's cheap general model, whose row was read off the page. */
        @JvmField
        val FLASH: Model =
                Model(
                        "deepseek-flash",
                        DEFAULT_PEAK_CACHE_HIT,
                        DEFAULT_PEAK_CACHE_MISS,
                        DEFAULT_PEAK_OUTPUT,
                        true)

        /**
         * The page's larger model, at the page's own numbers: peak CNY 0.30 for cached input, 9.00 for
         * uncached input and 27.00 for output, per 1M tokens.
         *
         * <p>This row was written first as a derived guess - a flat three times Flash - and then
         * corrected against the page, which is worth recording rather than quietly fixing: an invented
         * price is the one kind of wrong number this feature cannot detect for itself. It is about seven
         * and a half times Flash on cached input and three and a half on output. The app does not send
         * this model today, so the row is reachable only if that changes or if a key offers nothing
         * cheaper - and the key's model list is what would say so.
         */
        @JvmField val V4_PRO: Model = Model("deepseek-v4-pro", 0.30, 9.00, 27.00, true)

        private val MODELS: List<Model> = listOf(FLASH, V4_PRO)

        /** Every model the table knows, cheapest first. */
        @JvmStatic
        fun models(): List<Model> = MODELS

        /** The row for a model id, or `null` when the table does not know it. */
        @JvmStatic
        fun model(id: String?): Model? {
            if (id == null) {
                return null
            }
            val trimmed = id.javaTrim()
            for (candidate in MODELS) {
                if (candidate.id.equals(trimmed, ignoreCase = true)) {
                    return candidate
                }
            }
            return null
        }

        /** The cheap row, which is the one this app's own model is priced from. */
        @JvmStatic
        fun defaultModel(): Model = FLASH

        /**
         * The most expensive row. Used when nothing is known about the model in force: over-estimating a
         * model priced from the wrong row is a number the owner can argue with, while pricing an unknown
         * model at the cheapest row would quietly report a spend smaller than the bill.
         */
        @JvmStatic
        fun mostExpensiveModel(): Model {
            var dearest = MODELS[0]
            for (candidate in MODELS) {
                if (candidate.weight() > dearest.weight()) {
                    dearest = candidate
                }
            }
            return dearest
        }

        /** The row in force for the model the app sends: its own row, or the expensive fallback. */
        @JvmStatic
        fun inForceFor(appModel: String?): Model {
            val own = model(appModel)
            return own ?: mostExpensiveModel()
        }

        /**
         * Which row prices this app, given the model ids the key reports.
         *
         * <p>This is the whole use of `GET /models` for pricing, and it is deliberately pure so
         * that the rule is pinned by a test rather than living in a screen:
         *
         * <ol>
         *   <li>The app's own model, when the table knows it. The list is only asked whether it confirms
         *       the id, which is the loud warning ([Selection.listed]).
         *   <li>Otherwise, the cheapest <em>known</em> row among the ids the key reports. That is the rule
         *       that makes a legacy or misspelled name price correctly: a name the page does not list is
         *       served by whichever model the key actually offers, and the cheapest offered one is the
         *       cheap general model such a legacy name is billed as.
         *   <li>Otherwise the most expensive row, so an unreadable list never makes the estimate cheaper
         *       than the bill.
         * </ol>
         */
        @JvmStatic
        fun select(appModel: String?, listedIds: List<String?>?): Selection {
            val own = model(appModel)
            if (own != null) {
                return Selection(
                        own, true, if (listedIds == null) null else contains(listedIds, own.id))
            }
            val cheapestListed = cheapestKnown(listedIds)
            if (cheapestListed != null) {
                return Selection(cheapestListed, false, false)
            }
            return Selection(mostExpensiveModel(), false, if (listedIds == null) null else false)
        }

        private fun contains(ids: List<String?>, id: String): Boolean {
            for (candidate in ids) {
                if (candidate != null && candidate.javaTrim().equals(id, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        private fun cheapestKnown(ids: List<String?>?): Model? {
            if (ids == null) {
                return null
            }
            var cheapest: Model? = null
            for (id in ids) {
                val candidate = model(id) ?: continue
                val current = cheapest
                if (current == null || candidate.weight() < current.weight()) {
                    cheapest = candidate
                }
            }
            return cheapest
        }

        /** The embedded defaults for the app's own model. */
        @JvmStatic
        fun defaults(): TokenPrices =
                TokenPrices(
                        FLASH,
                        DEFAULT_PEAK_CACHE_HIT,
                        DEFAULT_PEAK_CACHE_MISS,
                        DEFAULT_PEAK_OUTPUT,
                        OFF_PEAK_FACTOR)

        /** The same, for the app's own model. */
        @JvmStatic
        fun of(cacheHit: String?, cacheMiss: String?, output: String?): TokenPrices =
                of(cacheHit, cacheMiss, output, defaultModel())

        /**
         * The prices in force, from the three stored values, falling back to `row`'s numbers.
         *
         * <p><strong>A blank or unparseable value falls back to the embedded default rather than to
         * zero.</strong> A zero price is not a neutral reading: it would silently claim every call this
         * app makes is free, which is the one wrong answer nobody would notice on a screen that exists to
         * report spend. Falling back over-estimates when the owner meant to clear a field, and an
         * over-estimate is a number a person can argue with.
         */
        @JvmStatic
        fun of(
                cacheHit: String?,
                cacheMiss: String?,
                output: String?,
                row: Model?): TokenPrices = of(cacheHit, cacheMiss, output, row, OFF_PEAK_FACTOR)

        /** The same, with the off-peak factor the published page states. */
        @JvmStatic
        fun of(
                cacheHit: String?,
                cacheMiss: String?,
                output: String?,
                row: Model?,
                offPeakFactor: Double): TokenPrices {
            val fallback = row ?: defaultModel()
            return TokenPrices(
                    fallback,
                    resolve(cacheHit, fallback.peakCacheHit),
                    resolve(cacheMiss, fallback.peakCacheMiss),
                    resolve(output, fallback.peakOutput),
                    offPeakFactor)
        }

        /** One stored price, or `fallback` when it is blank, not a number, or not positive. */
        private fun resolve(stored: String?, fallback: Double): Double {
            if (stored == null) {
                return fallback
            }
            val trimmed = stored.javaTrim()
            if (trimmed.isEmpty()) {
                return fallback
            }
            try {
                val value = trimmed.toDouble()
                // `isFinite` also rejects the strings Double parses as Infinity and NaN, and a
                // non-positive price is the "the app is free" case the class comment is about.
                return if (value.isFinite() && value > 0) value else fallback
            } catch (e: NumberFormatException) {
                return fallback
            }
        }

        /** A price as a settings field should show it: "0.04", "2", "8" - no trailing noise. */
        @JvmStatic
        fun format(price: Double): String {
            if (!price.isFinite()) {
                return ""
            }
            if (price == Math.rint(price) && Math.abs(price) < 1e15) {
                return price.toLong().toString()
            }
            return String.format(Locale.ROOT, "%s", price)
        }

        /**
         * The yuan figure as the phone should show it. Four decimals because a single message at these
         * prices is a fraction of a fen, so two would round an ordinary day to "¥0.00" and read as free.
         */
        @JvmStatic
        fun formatYuan(yuan: Double): String =
                String.format(Locale.ROOT, "%s%.4f", CURRENCY_SYMBOL, yuan)

        /** A list of ids as one readable line, for the screen that has to show what the key offers. */
        @JvmStatic
        fun joinIds(ids: List<String?>?): String {
            if (ids.isNullOrEmpty()) {
                return ""
            }
            val joined = StringBuilder()
            for (id in ids) {
                if (id == null || id.javaTrim().isEmpty()) {
                    continue
                }
                if (joined.isNotEmpty()) {
                    joined.append(", ")
                }
                joined.append(id.javaTrim())
            }
            return joined.toString()
        }

        /**
         * A copy of `ids` with blanks and duplicates removed, in the order the API listed them.
         *
         * <p>The id is trimmed <em>before</em> the duplicate test, not after it. The Java compared
         * the list's own trimmed id against the incoming **untrimmed** one, so `" a "` and `"a"` were
         * two different ids and both survived. That is preserved behaviour this row deliberately
         * repairs (docs/MIGRATION.md, "Policy from the pilot: Kotlin's stdlib is not Java's"), so a
         * padded duplicate now folds into its first sighting and its test keeps it repaired.
         */
        @JvmStatic
        fun distinctIds(ids: List<String?>?): List<String> {
            val distinct = ArrayList<String>()
            if (ids == null) {
                return distinct
            }
            for (id in ids) {
                if (id == null) {
                    continue
                }
                val trimmed = id.javaTrim()
                if (trimmed.isEmpty() || contains(distinct, trimmed)) {
                    continue
                }
                distinct.add(trimmed)
            }
            return distinct
        }
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace, and that is not academic here: the
 * non-breaking space a price copy-pasted off DeepSeek's page can carry would be stripped by it and
 * kept by the Java this replaced, which is the difference between [TokenPrices.resolve]'s documented
 * fallback and a parsed number. The Java it replaces used `String.trim()`, so this does too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
