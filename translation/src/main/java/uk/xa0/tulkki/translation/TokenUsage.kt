package uk.xa0.tulkki.translation

/**
 * One ledger row's worth of DeepSeek tokens, split by tariff and by what they were.
 *
 * <p>Six counts and nothing else: input tokens that were a cache <em>hit</em>, input tokens that were
 * a cache <em>miss</em> and output tokens, each under the peak tariff and under the off-peak one.
 * They are tokens rather than money on purpose - the prices are editable, so a row that stored a
 * figure would be stuck at the price in force when the call was made, while a row that stores tokens
 * can be re-priced by an edit ([TokenPrices.yuan]).
 *
 * <p>This is the shape of the `translation_usage` table's six columns, and it is pure Kotlin so the
 * arithmetic and its storage-facing behaviour are exercised by JVM tests.
 *
 * <p>Counts are clamped at zero: a negative one cannot come from DeepSeek, and a subtractive write
 * into the ledger is the one way a day's total could be made to under-report the spend.
 */
class TokenUsage(
        peakCacheHit: Int,
        peakCacheMiss: Int,
        peakOutput: Int,
        offPeakCacheHit: Int,
        offPeakCacheMiss: Int,
        offPeakOutput: Int) {

    /** Input tokens DeepSeek billed at the peak cache-hit price. */
    @JvmField val peakCacheHit: Int = peakCacheHit.coerceAtLeast(0)

    /** Input tokens DeepSeek billed at the peak cache-miss price. */
    @JvmField val peakCacheMiss: Int = peakCacheMiss.coerceAtLeast(0)

    /** Completion tokens DeepSeek billed at the peak output price. */
    @JvmField val peakOutput: Int = peakOutput.coerceAtLeast(0)

    /** The same three, for a call made in the off-peak window. */
    @JvmField val offPeakCacheHit: Int = offPeakCacheHit.coerceAtLeast(0)

    @JvmField val offPeakCacheMiss: Int = offPeakCacheMiss.coerceAtLeast(0)

    @JvmField val offPeakOutput: Int = offPeakOutput.coerceAtLeast(0)

    companion object {

        @JvmStatic
        fun zero(): TokenUsage = TokenUsage(0, 0, 0, 0, 0, 0)

        /**
         * One call's split, filed under the tariff the call's own instant falls in. This is the only
         * way a call reaches the ledger: the tariff is decided once, in UTC, at the moment of the
         * call ([PeakTariff.isPeak]), and never again from the device's timezone.
         */
        @JvmStatic
        fun forCall(cacheHit: Int, cacheMiss: Int, output: Int, peak: Boolean): TokenUsage =
                if (peak) {
                    TokenUsage(cacheHit, cacheMiss, output, 0, 0, 0)
                } else {
                    TokenUsage(0, 0, 0, cacheHit, cacheMiss, output)
                }
    }

    /** The two rows added together, count by count. */
    fun plus(other: TokenUsage?): TokenUsage {
        if (other == null) {
            return this
        }
        return TokenUsage(
                peakCacheHit + other.peakCacheHit,
                peakCacheMiss + other.peakCacheMiss,
                peakOutput + other.peakOutput,
                offPeakCacheHit + other.offPeakCacheHit,
                offPeakCacheMiss + other.offPeakCacheMiss,
                offPeakOutput + other.offPeakOutput)
    }

    /** True when every count is zero, so there is nothing to write. */
    fun isEmpty(): Boolean = totalTokens() == 0

    fun peakTokens(): Int = peakCacheHit + peakCacheMiss + peakOutput

    fun offPeakTokens(): Int = offPeakCacheHit + offPeakCacheMiss + offPeakOutput

    /** Every token in the row, under either tariff: what the evidence line counts. */
    fun totalTokens(): Int = peakTokens() + offPeakTokens()

    /** Input tokens that were served from DeepSeek's prompt cache, under either tariff. */
    fun cacheHitTokens(): Int = peakCacheHit + offPeakCacheHit

    /** Input tokens that missed the cache - the expensive kind - under either tariff. */
    fun cacheMissTokens(): Int = peakCacheMiss + offPeakCacheMiss

    fun promptTokens(): Int = cacheHitTokens() + cacheMissTokens()

    fun completionTokens(): Int = peakOutput + offPeakOutput

    override fun toString(): String =
            "tokens[peak hit=$peakCacheHit miss=$peakCacheMiss out=$peakOutput, " +
                    "off-peak hit=$offPeakCacheHit miss=$offPeakCacheMiss out=$offPeakOutput]"
}
