package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import java.util.regex.Pattern

/**
 * DeepSeek's published tariff, read from the page that publishes it.
 *
 * <p><strong>The app's numbers are an estimate, and this is where they can stop being a stale one.</strong>
 * Nothing in the API reports a price - `GET /models` names the models and says nothing about what
 * they cost - so a price table has to come from the pricing page, and a table copied into the build is
 * only as good as the day it was copied ([TokenPrices.PRICES_TAKEN_ON]). This class reads that
 * page into the same numbers [TokenPrices] already knows how to use, so a refresh is a fetch
 * instead of a release.
 *
 * <p><strong>The Chinese page, because the ledger is in yuan.</strong> The same table is published
 * twice: [CNY_URL] in CNY and the English page in USD. Converting one into the other would put
 * a guessed exchange rate underneath every figure on the Ledger screen, so only the CNY page is read
 * and there is deliberately no USD fallback - a failed read keeps the numbers already in force and
 * says so, which is the same rule `GET /models` failing follows.
 *
 * <p><strong>Every rule here exists to return `null` rather than a wrong number.</strong> A
 * screen that shows yesterday's prices is a screen the owner can argue with; a screen that shows a
 * mis-parsed column is not. So the parse is anchored on the labels the page uses rather than on
 * positions, it requires all six figures for a model, it refuses a table whose off-peak numbers are
 * not the stated fraction of its peak ones, and it refuses anything outside a wide plausibility band
 * around the row the build ships. Only then is it stored.
 *
 * <p>Pure Kotlin and no Android types: the fetch is somebody else's, and every branch here is pinned by
 * a JVM test against a verbatim slice of the real page (see `src/test/resources/pricepage`).
 */
object TariffPage {

    /**
     * The page a refresh reads: DeepSeek's CNY table, in the currency the ledger reports.
     *
     * <p>With the trailing slash, which is the URL the site serves directly: without it the page
     * answers a redirect, and one request is better than two. Note that a wrong path here does not
     * fail - the site answers `200` with its shell for routes it does not have - so a
     * successful fetch means nothing on its own and [parse] is what decides whether the bytes
     * are the table.
     */
    const val CNY_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"

    /**
     * How far a fetched peak price may sit from the row this build ships before it is refused.
     *
     * <p>Wide on purpose: a real price change is the thing this feature exists to catch, and a
     * hundredfold one is not a price change. What the band actually catches is a parse that picked up
     * something that is not a price at all - a context length, a concurrency limit, a date.
     */
    private const val MAX_PRICE_DRIFT = 100.0

    /** How far the off-peak rows may sit from peak × the factor before the whole table is refused. */
    private const val FACTOR_TOLERANCE = 0.02

    /** The labels the page's own table uses, in both halves of each pair. */
    private const val LABEL_MODEL = "模型"
    private const val LABEL_PRICE = "价格"
    private const val LABEL_HIT = "缓存命中"
    private const val LABEL_MISS = "缓存未命中"
    private const val LABEL_OUTPUT = "tokens输出"
    private const val LABEL_PEAK = "高峰时段"
    private const val LABEL_OFF_PEAK = "空闲时段"
    private const val YUAN = "元"
    private const val BEIJING = "北京时间"
    private const val WEEKDAYS = "周一至周五"
    private const val PUBLIC_HOLIDAYS = "法定节假日"
    private const val HALF = "一半"

    /** A model id as the table's header spells it: lowercase, letters and digits, joined. */
    private val MODEL_ID: Pattern = Pattern.compile("[a-z0-9][a-z0-9]*(?:[-._][a-z0-9]+)*")

    /**
     * One price, as the CNY page writes it: `0.04元`.
     *
     * <p>The unit is required, and it is required to be the yuan. The English page publishes the same
     * table in dollars, so a pattern that took a bare number - or that took either unit - would price
     * a yuan ledger from dollars the moment anything pointed a fetch at the wrong URL, which is a
     * silent factor of seven on every figure. A page whose figures are not in yuan yields no table.
     */
    private val MONEY: Pattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元")

    /** A clock range as the footnote writes it: `9:00 - 12:00`. */
    private val CLOCK: Pattern =
            Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[-–—~至]\\s*(\\d{1,2}):(\\d{2})")

    /** The footnote's fraction, when it is not the word for a half: `的60%`. */
    private val PERCENT: Pattern = Pattern.compile("的\\s*(\\d{1,3})\\s*%")

    /** Beijing is UTC+8 with no daylight saving, so the footnote's times are a fixed shift. */
    private const val BEIJING_OFFSET_MINUTES = 8 * 60

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * One model's published row: the three peak prices and the three off-peak ones, in CNY per 1M
     * tokens.
     *
     * <p>The off-peak numbers are kept rather than recomputed because they are what the page said,
     * and the check that they are the stated fraction of the peak ones has to be made against the
     * page rather than against the factor we just read.
     */
    class Row internal constructor(
            @JvmField val id: String,
            @JvmField val peakCacheHit: Double,
            @JvmField val peakCacheMiss: Double,
            @JvmField val peakOutput: Double,
            @JvmField val offPeakCacheHit: Double,
            @JvmField val offPeakCacheMiss: Double,
            @JvmField val offPeakOutput: Double) {

        /** The peak triple as the price table wants it. */
        fun model(): TokenPrices.Model =
                TokenPrices.Model.published(id, peakCacheHit, peakCacheMiss, peakOutput)

        override fun toString(): String =
                id +
                        " peak " +
                        TokenPrices.format(peakCacheHit) +
                        "/" +
                        TokenPrices.format(peakCacheMiss) +
                        "/" +
                        TokenPrices.format(peakOutput) +
                        ", off-peak " +
                        TokenPrices.format(offPeakCacheHit) +
                        "/" +
                        TokenPrices.format(offPeakCacheMiss) +
                        "/" +
                        TokenPrices.format(offPeakOutput) +
                        " CNY per 1M"
    }

    /**
     * A tariff read from the page: the rows, the fraction off-peak is worth, the windows the page
     * says are peak, and when and where it was read.
     *
     * <p>`schedule` is `null` when the page's numbers were usable but its sentence about
     * the hours was not: prices and hours are separate facts, and half a page is worth more than
     * none. The hours then stay the ones the build ships, which is stated on the screen rather than
     * assumed here.
     */
    class Table internal constructor(
            rows: List<Row>?,
            offPeakFactor: Double,
            schedule: PeakTariff.Schedule?,
            readOn: String?,
            source: String?) {

        @JvmField val rows: List<Row> = rows ?: emptyList()

        @JvmField val offPeakFactor: Double = offPeakFactor

        @JvmField val schedule: PeakTariff.Schedule? = schedule

        /** The local day the page was read, as [DailyTokenCounter.dayOf] spells one. */
        @JvmField val readOn: String = readOn ?: ""

        /** Where it was read from, kept so the screen can say which page these numbers came off. */
        @JvmField val source: String = source ?: ""

        fun isEmpty(): Boolean = rows.isEmpty()

        /** The published row for a model id, or `null` when the page did not list it. */
        fun row(id: String?): Row? {
            if (id == null) {
                return null
            }
            for (candidate in rows) {
                if (candidate.id.equals(id.javaTrim(), ignoreCase = true)) {
                    return candidate
                }
            }
            return null
        }

        /**
         * The row to price `embedded` from: the page's own numbers when it lists that model,
         * and the build's row when it does not - a model the page added is not a model the app can
         * price, and inventing a row for it would be the one thing this class refuses to do.
         */
        fun effective(embedded: TokenPrices.Model?): TokenPrices.Model? {
            if (embedded == null) {
                return null
            }
            val published = row(embedded.id)
            return if (published == null) embedded else published.model()
        }

        override fun toString(): String {
            val hours = schedule
            return "tariff of " +
                    readOn +
                    " from " +
                    source +
                    ": " +
                    rows +
                    ", off-peak " +
                    TokenPrices.format(offPeakFactor * 100) +
                    "%, peak " +
                    (if (hours == null) "as shipped" else hours.toString())
        }
    }

    /**
     * Reads a page into a table, or returns `null` when it is not the page we know.
     *
     * @param document the fetched page, HTML and all
     * @param source the URL it came from, for the screen
     * @param readOn the local day it was read
     */
    @JvmStatic
    fun parse(document: String?, source: String?, readOn: String?): Table? {
        if (document == null || document.isEmpty()) {
            return null
        }
        val rows = tableRows(document)
        if (rows.isEmpty()) {
            return null
        }
        val ids = modelIds(rows)
        if (ids.isEmpty()) {
            return null
        }
        val parsed = prices(rows, ids)
        if (parsed == null || parsed.isEmpty()) {
            return null
        }
        val stated = statedFactor(document)
        val derived = derivedFactor(parsed)
        if (derived == null) {
            // The off-peak rows are not a single fraction of the peak ones, so the app's model of the
            // tariff - three peak prices and a factor - is not this page's model. Refusing is the
            // only honest answer; halving anyway would be a number the owner cannot check.
            return null
        }
        if (stated != null && Math.abs(stated - derived) > FACTOR_TOLERANCE) {
            // The sentence and the table disagree, so one of the two was misread.
            return null
        }
        if (!plausible(parsed)) {
            return null
        }
        return Table(parsed, derived, schedule(document), readOn, source)
    }

    // ---- the table ---------------------------------------------------------------------------

    /**
     * The model ids the table's header names, in column order.
     *
     * <p>Anchored on the table's own rows rather than on position: the page has more than one row
     * beginning with the word for "model" - `模型版本` ("model version") is one, and its cells
     * read exactly like ids - so the row whose ids the price table already knows wins, and a page
     * that renamed every model still parses from the first row that carries id-shaped cells.
     */
    private fun modelIds(rows: List<List<String>>): List<String> {
        var fallback: List<String>? = null
        for (row in rows) {
            if (row.isEmpty() || !row[0].startsWith(LABEL_MODEL)) {
                continue
            }
            val ids = ArrayList<String>()
            var known = 0
            for (i in 1 until row.size) {
                val candidate = idOf(row[i])
                if (candidate == null) {
                    continue
                }
                ids.add(candidate)
                if (TokenPrices.model(candidate) != null) {
                    known++
                }
            }
            if (ids.isEmpty()) {
                continue
            }
            if (known == ids.size) {
                return ids
            }
            if (fallback == null) {
                fallback = ids
            }
        }
        return fallback ?: emptyList()
    }

    /** A header cell as a model id: `deepseek-flash(1)` is `deepseek-flash`. */
    private fun idOf(cell: String?): String? {
        if (cell == null) {
            return null
        }
        val trimmed = cell.javaTrim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) {
            return null
        }
        val matcher = MODEL_ID.matcher(trimmed)
        // `find` rather than `matches`: the footnote marker rides along as "(1)" and is stripped by
        // taking the first id-shaped run, which is also what makes a name with a version in it work.
        if (!matcher.find() || matcher.start() != 0) {
            return null
        }
        val id = matcher.group()
        // A bare word is not a model id: the header's own label and the table's row labels are words.
        return if (id.indexOf('-') > 0 || id.indexOf('.') > 0 || id.length >= 8) id else null
    }

    /**
     * The six figures per model, anchored on the labels rather than on cell positions.
     *
     * <p>The page splits each metric over two rows and puts the metric's own cell on the first of
     * them with a `rowspan`, so the label is carried forward until the next one appears - which
     * is why this walks the rows in order and remembers what it last saw instead of indexing cells.
     */
    private fun prices(rows: List<List<String>>, ids: List<String>): List<Row>? {
        val peak = arrayOfNulls<DoubleArray>(3)
        val offPeak = arrayOfNulls<DoubleArray>(3)
        var metric = -1
        var inPriceBlock = false
        for (row in rows) {
            if (!inPriceBlock) {
                if (row.isEmpty() || !row[0].startsWith(LABEL_PRICE)) {
                    continue
                }
                // The row that opens the price block also *is* its first data row: the page puts the
                // "价格" cell in the same <tr> as the first metric and its numbers, so this row is
                // processed rather than skipped - skipping it loses the cached-input off-peak figures
                // and the whole table with them.
                inPriceBlock = true
            }
            for (cell in row) {
                val seen = metricOf(cell)
                if (seen >= 0) {
                    metric = seen
                }
            }
            if (metric < 0) {
                continue
            }
            var isPeak: Boolean? = null
            for (cell in row) {
                if (cell == LABEL_PEAK) {
                    isPeak = true
                } else if (cell == LABEL_OFF_PEAK) {
                    isPeak = false
                }
            }
            if (isPeak == null) {
                continue
            }
            val money = money(row)
            if (money.isEmpty()) {
                continue
            }
            if (money.size != ids.size) {
                // A row whose columns do not line up with the header cannot be attributed to a model
                // without guessing which column moved.
                return null
            }
            val into = DoubleArray(money.size)
            for (i in money.indices) {
                into[i] = money[i]
            }
            if (isPeak) {
                peak[metric] = into
            } else {
                offPeak[metric] = into
            }
        }
        val peakHit = peak[0]
        val peakMiss = peak[1]
        val peakOut = peak[2]
        val offHit = offPeak[0]
        val offMiss = offPeak[1]
        val offOut = offPeak[2]
        if (peakHit == null ||
                peakMiss == null ||
                peakOut == null ||
                offHit == null ||
                offMiss == null ||
                offOut == null) {
            return null
        }
        val parsed = ArrayList<Row>()
        for (column in ids.indices) {
            parsed.add(
                    Row(
                            ids[column],
                            peakHit[column],
                            peakMiss[column],
                            peakOut[column],
                            offHit[column],
                            offMiss[column],
                            offOut[column]))
        }
        return parsed
    }

    /** Which of the three figures a cell names, or `-1`: 0 cached input, 1 uncached, 2 output. */
    private fun metricOf(cell: String?): Int {
        if (cell == null) {
            return -1
        }
        if (cell.contains(LABEL_MISS)) {
            return 1
        }
        if (cell.contains(LABEL_HIT)) {
            return 0
        }
        // Anchored on the unit rather than on the word: the same table has a row labelled "输出长度"
        // (the maximum output length), and a bare "输出" would read that row as a price.
        if (cell.endsWith(LABEL_OUTPUT)) {
            return 2
        }
        return -1
    }

    /** Every yuan figure in a row, in the order the row has them. */
    private fun money(row: List<String>): List<Double> {
        val found = ArrayList<Double>()
        for (cell in row) {
            val matcher = MONEY.matcher(cell)
            while (matcher.find()) {
                try {
                    found.add(matcher.group(1).toDouble())
                } catch (e: NumberFormatException) {
                    return ArrayList()
                }
            }
        }
        return found
    }

    // ---- the footnote ------------------------------------------------------------------------

    /**
     * The fraction off-peak is worth, from the page's own sentence, or `null` when it does not
     * state one in a form this understands.
     */
    private fun statedFactor(document: String?): Double? {
        val text = text(document)
        val at = text.indexOf(LABEL_OFF_PEAK + "价格为" + LABEL_PEAK + "价格的")
        if (at < 0) {
            return null
        }
        val tail = text.substring(at, Math.min(text.length, at + 60))
        val percent = PERCENT.matcher(tail)
        if (percent.find()) {
            val value = percent.group(1).toDouble() / 100.0
            return if (value > 0 && value < 1) value else null
        }
        return if (tail.contains(HALF)) TokenPrices.OFF_PEAK_FACTOR else null
    }

    /**
     * The fraction the table's own numbers imply: the first off-peak/peak ratio, kept only if every
     * other one agrees with it.
     */
    private fun derivedFactor(rows: List<Row>): Double? {
        var factor: Double? = null
        for (row in rows) {
            val pairs =
                    arrayOf(
                            doubleArrayOf(row.offPeakCacheHit, row.peakCacheHit),
                            doubleArrayOf(row.offPeakCacheMiss, row.peakCacheMiss),
                            doubleArrayOf(row.offPeakOutput, row.peakOutput))
            for (pair in pairs) {
                if (!(pair[1] > 0) || !(pair[0] > 0)) {
                    return null
                }
                val ratio = pair[0] / pair[1]
                val current = factor
                if (current == null) {
                    factor = ratio
                } else if (Math.abs(ratio - current) > FACTOR_TOLERANCE) {
                    return null
                }
            }
        }
        val found = factor
        return if (found != null && found > 0 && found < 1) found else null
    }

    /**
     * The peak windows, from the page's own sentence about the hours.
     *
     * <p>Only the times are read. The sentence is also required to still say "Monday to Friday" and
     * "public holidays", because those are the two shapes of the rule the code implements: a page
     * that moved to weekends-only, or that dropped the holiday exclusion, is not something a pair of
     * times can express and is left to be handled deliberately rather than half-applied.
     */
    private fun schedule(document: String?): PeakTariff.Schedule? {
        if (document == null) {
            return null
        }
        val text = text(document)
        // `Pattern.split(input, 0)` is Java's own split: Kotlin's literal `split("。")` keeps the
        // trailing empty field Java drops, and this sentence walk depends on Java's text.
        for (sentence in Pattern.compile("。").split(text, 0)) {
            if (!sentence.contains(BEIJING) ||
                    !sentence.contains(LABEL_PEAK) ||
                    !sentence.contains(WEEKDAYS) ||
                    !sentence.contains(PUBLIC_HOLIDAYS)) {
                continue
            }
            val windows = ArrayList<IntArray>()
            val clock = CLOCK.matcher(sentence)
            while (clock.find()) {
                val start = minutes(clock.group(1), clock.group(2))
                val end = minutes(clock.group(3), clock.group(4))
                if (start < 0 || end < 0) {
                    return null
                }
                windows.add(intArrayOf(start - BEIJING_OFFSET_MINUTES, end - BEIJING_OFFSET_MINUTES))
            }
            if (windows.size != 2) {
                return null
            }
            val morning = windows[0]
            val afternoon = windows[1]
            if (!usable(morning) || !usable(afternoon) || morning[1] > afternoon[0]) {
                return null
            }
            return PeakTariff.Schedule(morning[0], morning[1], afternoon[0], afternoon[1])
        }
        return null
    }

    /** Whether a window, already shifted to UTC, is one the schedule can hold. */
    private fun usable(window: IntArray): Boolean =
            window[0] >= 0 && window[0] < window[1] && window[1] <= MINUTES_PER_DAY

    /** `9:00` as minutes from midnight, or `-1` when it is not a time of day. */
    private fun minutes(hour: String, minute: String): Int {
        val h = hour.toInt()
        val m = minute.toInt()
        if (h > 23 || m > 59) {
            return -1
        }
        return h * 60 + m
    }

    // ---- refusing what cannot be right --------------------------------------------------------

    /**
     * Whether every fetched price is a price: positive, finite, cheaper when cached, and not wildly
     * far from the row this build ships for the same model.
     */
    private fun plausible(rows: List<Row>): Boolean {
        for (row in rows) {
            val prices =
                    doubleArrayOf(
                            row.peakCacheHit,
                            row.peakCacheMiss,
                            row.peakOutput,
                            row.offPeakCacheHit,
                            row.offPeakCacheMiss,
                            row.offPeakOutput)
            for (price in prices) {
                if (!price.isFinite() || price <= 0) {
                    return false
                }
            }
            if (row.peakCacheHit >= row.peakCacheMiss) {
                // A cached token is never the dearer one; if it reads that way, a column moved.
                return false
            }
            val embedded = TokenPrices.model(row.id)
            if (embedded == null) {
                continue
            }
            val compared =
                    arrayOf(
                            doubleArrayOf(row.peakCacheHit, embedded.peakCacheHit),
                            doubleArrayOf(row.peakCacheMiss, embedded.peakCacheMiss),
                            doubleArrayOf(row.peakOutput, embedded.peakOutput))
            for (pair in compared) {
                val ratio = pair[0] / pair[1]
                if (!(ratio > 1.0 / MAX_PRICE_DRIFT) || !(ratio < MAX_PRICE_DRIFT)) {
                    return false
                }
            }
        }
        return true
    }

    // ---- storage -----------------------------------------------------------------------------

    /** The table as the settings keep it: one line of JSON, readable back exactly. */
    @JvmStatic
    fun toJson(table: Table?): String {
        if (table == null) {
            return ""
        }
        val root = JsonObject()
        root.addProperty("source", table.source)
        root.addProperty("readOn", table.readOn)
        root.addProperty("factor", table.offPeakFactor)
        val schedule = table.schedule
        if (schedule != null) {
            val scheduleJson = JsonObject()
            val morning = JsonArray()
            morning.add(schedule.morningStartMinutes)
            morning.add(schedule.morningEndMinutes)
            val afternoon = JsonArray()
            afternoon.add(schedule.afternoonStartMinutes)
            afternoon.add(schedule.afternoonEndMinutes)
            scheduleJson.add("morning", morning)
            scheduleJson.add("afternoon", afternoon)
            root.add("schedule", scheduleJson)
        }
        val models = JsonArray()
        for (row in table.rows) {
            val model = JsonObject()
            model.addProperty("id", row.id)
            model.add("peak", array(row.peakCacheHit, row.peakCacheMiss, row.peakOutput))
            model.add(
                    "offPeak",
                    array(row.offPeakCacheHit, row.offPeakCacheMiss, row.offPeakOutput))
            models.add(model)
        }
        root.add("models", models)
        return root.toString()
    }

    private fun array(a: Double, b: Double, c: Double): JsonArray {
        val array = JsonArray()
        array.add(a)
        array.add(b)
        array.add(c)
        return array
    }

    /**
     * The table back out of storage, or `null` when the stored text is not a table.
     *
     * <p>A stored table is not re-checked against the page it came from - it is a record of what the
     * page said on its day - but it is still checked for being a tariff at all: three figures per
     * model, a factor between nothing and everything, and the plausibility rules above, so a corrupted
     * or half-written value falls back to the build's own numbers instead of pricing the ledger from
     * a hole.
     */
    @JvmStatic
    fun fromJson(json: String?): Table? {
        if (json == null || json.javaTrim().isEmpty()) {
            return null
        }
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) {
                return null
            }
            val root = rootElement.asJsonObject
            val factor = if (root.has("factor")) root.get("factor").asDouble else 0.0
            if (!(factor > 0) || !(factor < 1)) {
                return null
            }
            val rows = ArrayList<Row>()
            if (root.has("models") && root.get("models").isJsonArray) {
                for (element in root.getAsJsonArray("models")) {
                    val row = row(element)
                    if (row == null) {
                        return null
                    }
                    rows.add(row)
                }
            }
            if (rows.isEmpty() || !plausible(rows)) {
                return null
            }
            // The same agreement the parse demands, demanded again on the way out: a stored table is
            // untrusted input like any other, and one whose off-peak rows stopped being the stated
            // fraction of its peak ones is not a tariff this app can price from.
            val implied = derivedFactor(rows)
            if (implied == null || Math.abs(implied - factor) > FACTOR_TOLERANCE) {
                return null
            }
            val schedule = scheduleOf(root)
            return Table(
                    rows,
                    factor,
                    schedule,
                    if (root.has("readOn")) root.get("readOn").asString else "",
                    if (root.has("source")) root.get("source").asString else "")
        } catch (e: RuntimeException) {
            // Gson throws on shapes it does not expect, and a stored value is untrusted input like
            // any other: an unusable one means the embedded defaults, not a crash on a screen.
            return null
        }
    }

    private fun row(element: JsonElement?): Row? {
        if (element == null || !element.isJsonObject) {
            return null
        }
        val model = element.asJsonObject
        if (!model.has("id") || !model.has("peak") || !model.has("offPeak")) {
            return null
        }
        val peak = triple(model.getAsJsonArray("peak"))
        val offPeak = triple(model.getAsJsonArray("offPeak"))
        if (peak == null || offPeak == null) {
            return null
        }
        return Row(
                model.get("id").asString,
                peak[0],
                peak[1],
                peak[2],
                offPeak[0],
                offPeak[1],
                offPeak[2])
    }

    private fun triple(array: JsonArray?): DoubleArray? {
        if (array == null || array.size() != 3) {
            return null
        }
        val values = DoubleArray(3)
        for (i in 0 until 3) {
            values[i] = array.get(i).asDouble
        }
        return values
    }

    private fun scheduleOf(root: JsonObject): PeakTariff.Schedule? {
        if (!root.has("schedule") || !root.get("schedule").isJsonObject) {
            return null
        }
        val schedule = root.getAsJsonObject("schedule")
        if (!schedule.has("morning") || !schedule.has("afternoon")) {
            return null
        }
        val morning = schedule.getAsJsonArray("morning")
        val afternoon = schedule.getAsJsonArray("afternoon")
        if (morning == null ||
                afternoon == null ||
                morning.size() != 2 ||
                afternoon.size() != 2) {
            return null
        }
        val first = intArrayOf(morning.get(0).asInt, morning.get(1).asInt)
        val second = intArrayOf(afternoon.get(0).asInt, afternoon.get(1).asInt)
        if (!usable(first) || !usable(second) || first[1] > second[0]) {
            return null
        }
        return PeakTariff.Schedule(first[0], first[1], second[0], second[1])
    }

    // ---- HTML, minimally ---------------------------------------------------------------------

    /** The document's rows, each a list of cell texts. */
    @JvmStatic
    fun tableRows(html: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var at = 0
        while (true) {
            val start = indexOfIgnoreCase(html, "<tr", at)
            if (start < 0) {
                break
            }
            val end = indexOfIgnoreCase(html, "</tr>", start)
            if (end < 0) {
                break
            }
            val body = html.substring(start, end)
            val cells = ArrayList<String>()
            var cell = 0
            while (true) {
                val open =
                        firstOf(
                                indexOfIgnoreCase(body, "<td", cell),
                                indexOfIgnoreCase(body, "<th", cell))
                if (open < 0) {
                    break
                }
                val close = indexOfIgnoreCase(body, ">", open)
                if (close < 0) {
                    break
                }
                val shut =
                        firstOf(
                                indexOfIgnoreCase(body, "</td>", close),
                                indexOfIgnoreCase(body, "</th>", close))
                if (shut < 0) {
                    break
                }
                cells.add(text(body.substring(close + 1, shut)))
                cell = shut + 1
            }
            if (!cells.isEmpty()) {
                rows.add(cells)
            }
            at = end + 1
        }
        return rows
    }

    /** A fragment of HTML as the words a person would read off the page, entities decoded. */
    @JvmStatic
    fun text(html: String?): String {
        if (html == null) {
            return ""
        }
        // `Pattern.matcher(...).replaceAll(...)` rather than `String.replaceAll`, so the Java
        // regex-and-replacement semantics are the ones in force, call for call.
        val stripped = Pattern.compile("(?s)<[^>]*>").matcher(html).replaceAll(" ")
        return Pattern.compile("\\s+").matcher(decode(stripped)).replaceAll(" ").javaTrim()
    }

    private fun decode(text: String): String =
            text.replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&ldquo;", "\u201c")
                    .replace("&rdquo;", "\u201d")

    private fun firstOf(a: Int, b: Int): Int {
        if (a < 0) {
            return b
        }
        if (b < 0) {
            return a
        }
        return Math.min(a, b)
    }

    private fun indexOfIgnoreCase(haystack: String?, needle: String?, from: Int): Int {
        if (haystack == null || needle == null || from < 0 || from > haystack.length) {
            return -1
        }
        val limit = haystack.length - needle.length
        for (i in from..limit) {
            // Kotlin's own `regionMatches` (thisOffset, other, otherOffset, length, ignoreCase)
            // rather than Java's boolean-first overload.
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) {
                return i
            }
        }
        return -1
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace, which is a different set: the Java this
 * replaces strips the control characters below U+0009 that Kotlin keeps and keeps the non-breaking
 * space that Kotlin strips. A model id or a stored JSON line is Java-trimmed text, so this is too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
