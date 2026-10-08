package uk.xa0.tulkki.ui.ledger

import androidx.annotation.StringRes
import uk.xa0.tulkki.data.translation.UsageByOrigin
import uk.xa0.tulkki.data.translation.UsageCounts
import uk.xa0.tulkki.data.translation.UsageOriginKind
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TokenUsage
import uk.xa0.tulkki.ui.R

/**
 * One bucket of a day's drill-down, ready to draw: who the tokens went to, and what they cost.
 *
 * <p>**The bucket's own key is deliberately not a field.** `:data`'s `UsageOrigin.origin` is the
 * conversation's uuid, and the two buckets that have no conversation - the several-rooms batch and
 * the one that resolved to nothing - would then be drawn as that uuid. The read already decided what
 * each bucket *is* ([kind]) and, when there is one to show, the conversation's own [address]; a line
 * that carried the key as well would be a line the screen could print by accident.
 */
data class OriginLine(
    /** What kind of place it was: the label the line falls back to when [address] is absent. */
    val kind: UsageOriginKind,
    /** The conversation's own address, resolved at read time, or null when there is nothing to name. */
    val address: String?,
    /** The bucket's six token counts. */
    val usage: UsageCounts,
    /** [usage] priced at the prices in force - `TokenPrices.yuan`, the same arithmetic the day row uses. */
    val yuan: Double,
)

/**
 * One day, split by where its tokens went - what tapping a day on the ledger draws.
 *
 * <p>[addsUp] is the read's own verdict: `UsageByOrigin.addsUp()` compares the buckets against the
 * day's row, and a breakdown whose parts do not make the whole is a defect, not something to draw.
 * **A discrepancy therefore produces no lines at all** ([lines] is empty and [addsUp] false), so the
 * screen cannot render the numbers by forgetting to ask; the one thing it can draw is the defect
 * itself. [totalYuan] is the day's own figure, priced from the day's row and never summed from the
 * buckets, so the two statements are compared rather than conflated (`UsageByOrigin`'s own KDoc).
 */
data class DayBreakdown(
    /** The local calendar day this split is of, as the day row spells it. */
    val day: String,
    /** The buckets, grouped and ordered by kind. Empty when the read could not be made to add up. */
    val lines: List<OriginLine>,
    /** What the day cost in all, priced from the day's own row. */
    val totalYuan: Double,
    /** Whether the parts still make the whole; false is a defect the screen reports and does not draw. */
    val addsUp: Boolean,
)

/**
 * The day whose split is open, and what is known about it so far.
 *
 * <p>A screen parameter rather than a member of [LedgerState], exactly as `enabled` and `hasKey` are:
 * the split is a reading of its own - `TranslationUsageStore.byOrigin(day)`, a synchronous store call
 * the host makes off the main thread - and a tap is answered at once with [breakdown] still null, which
 * is the loading state. [notice] is a failed read's own words; one day is open at a time, so this type
 * is also what keeps one day's failure or figures off another day's row.
 */
data class DayDrill(
    /** The local calendar day the owner tapped, as the day row spells it. */
    val day: String,
    /** The split, or null while the read is in flight or when it failed. */
    val breakdown: DayBreakdown? = null,
    /** Why the split could not be read at all, or null. */
    val notice: String? = null,
)

/**
 * The drill-down's assembly, plain Kotlin with JVM tests beside it - the same split §7.4 asks of
 * `LedgerState`: "**Screen state is a pure reducer**, not a Composable".
 *
 * <p>Nothing is read here that the caller has not already read: the store call is
 * `TranslationUsageStore.get(context).byOrigin(day)`, and because it is synchronous it is the host's
 * to make off the main thread (`UsageFragment`), exactly as the ledger's own day rows are.
 */
object LedgerBreakdown {

    /**
     * The day's split, grouped and ordered by [UsageOriginKind].
     *
     * <p>**The kinds are enumerated, not hardcoded.** The outer loop is the enum's own case list, so
     * every bucket a kind can hold lands under that kind and a sixth case would need no edit here -
     * while a rule written as "empty origin means not tied to a room" would file the eight-room
     * catch-up batch under the wrong heading. `:data` is the one place that decides a bucket's kind
     * (`TranslationUsageStore.kindOf`), and this only orders by it.
     */
    fun of(read: UsageByOrigin, prices: TokenPrices): DayBreakdown {
        if (!read.addsUp()) {
            return DayBreakdown(read.day, emptyList(), yuan(prices, read.total), addsUp = false)
        }
        val lines = ArrayList<OriginLine>(read.origins.size)
        for (kind in UsageOriginKind.entries) {
            for (origin in read.origins) {
                if (origin.kind != kind) {
                    continue
                }
                lines.add(OriginLine(kind, origin.address, origin.usage, yuan(prices, origin.usage)))
            }
        }
        return DayBreakdown(read.day, lines, yuan(prices, read.total), addsUp = true)
    }

    /**
     * One bucket's money, through the tree's own arithmetic.
     *
     * <p>`TokenPrices.yuan` prices a `TokenUsage`, and `:data`'s `UsageCounts` is that type's row -
     * the six counts in the same order - so this builds the one from the other rather than asking
     * `:translation` for a bridge: the constructor is public and both types hold the same six
     * numbers, which is a conversion and not a new rule.
     */
    fun yuan(prices: TokenPrices, usage: UsageCounts): Double =
        prices.yuan(
            TokenUsage(
                usage.peakCacheHit,
                usage.peakCacheMiss,
                usage.peakOutput,
                usage.offPeakCacheHit,
                usage.offPeakCacheMiss,
                usage.offPeakOutput,
            )
        )
}

/**
 * The label a bucket falls back to when the read resolved no address: one string per kind, and the
 * `when` is exhaustive, so a new case is a compile error here rather than a silently unnamed row.
 *
 * <p>[UsageOriginKind.DELETED_CONVERSATION] labels itself - the conversation is gone, so there is no
 * address and never the key - and [UsageOriginKind.MULTI_ROOM] is its own sentence rather than the
 * not-tied one, because a catch-up batch that covered up to eight rooms is room traffic.
 */
@StringRes
fun originKindLabel(kind: UsageOriginKind): Int =
    when (kind) {
        UsageOriginKind.NOT_TIED_TO_A_ROOM -> R.string.tulkki_usage_breakdown_not_tied_to_a_room
        UsageOriginKind.GROUP_CHAT -> R.string.tulkki_usage_breakdown_group_chat
        UsageOriginKind.ONE_TO_ONE -> R.string.tulkki_usage_breakdown_one_to_one
        UsageOriginKind.MULTI_ROOM -> R.string.tulkki_usage_breakdown_multi_room
        UsageOriginKind.DELETED_CONVERSATION -> R.string.tulkki_usage_breakdown_deleted
    }
