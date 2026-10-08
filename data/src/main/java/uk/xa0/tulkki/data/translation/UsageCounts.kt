package uk.xa0.tulkki.data.translation

/**
 * One ledger row's six counts, as `:data` reads and writes them (schema 74, and schema 78's per-origin
 * rows).
 *
 * <p>**Why its own type.** `:translation`'s `TokenUsage` is that module's value - it clamps each count
 * at zero and knows how a call is split by tariff - and `:data` may not import it. This is the file's
 * row: six counts, in the order the columns are declared, with the one piece of arithmetic a reader
 * needs (the total) and no rule about what may be written.
 *
 * <p>The total is what the drill-down's summing cell compares against the day's own row, so it is a
 * method here rather than a second spelling in a test.
 */
data class UsageCounts(
    val peakCacheHit: Int,
    val peakCacheMiss: Int,
    val peakOutput: Int,
    val offPeakCacheHit: Int,
    val offPeakCacheMiss: Int,
    val offPeakOutput: Int,
) {

    /** The day's tokens, the six counts added: the figure the cap and the yuan line are made of. */
    fun total(): Int =
        peakCacheHit + peakCacheMiss + peakOutput + offPeakCacheHit + offPeakCacheMiss + offPeakOutput

    /** Whether this row holds anything at all. Six zeroes is not a day the owner spent on. */
    fun isEmpty(): Boolean = total() == 0

    /** This row plus another, count for count - what a breakdown over rows sums with. */
    operator fun plus(other: UsageCounts): UsageCounts =
        UsageCounts(
            peakCacheHit + other.peakCacheHit,
            peakCacheMiss + other.peakCacheMiss,
            peakOutput + other.peakOutput,
            offPeakCacheHit + other.offPeakCacheHit,
            offPeakCacheMiss + other.offPeakCacheMiss,
            offPeakOutput + other.offPeakOutput,
        )

    companion object {
        @JvmStatic fun zero(): UsageCounts = UsageCounts(0, 0, 0, 0, 0, 0)
    }
}

/** One row of the day's own table: the local calendar day and its tokens. */
data class UsageDay(val day: String, val usage: UsageCounts)
