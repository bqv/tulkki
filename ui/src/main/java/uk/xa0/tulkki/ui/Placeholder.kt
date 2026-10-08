package uk.xa0.tulkki.ui

import uk.xa0.tulkki.ui.projection.PlaceholderShape

/**
 * The covered body's shape as a rule, docs/MIGRATION.md "Design: the Compose UI" §6.1: "an
 * untranslated in-app message is a **blurred shimmer**, not a 'not translated yet' line", and the
 * redesign "replaces the covered body with a placeholder that **has no text and no pixels of the
 * original**, in every cover state".
 *
 * <p>No Android type appears here - it is written like [ReviewWave], so a placeholder's arithmetic
 * joins the JVM suite and a cell measures a bar without a device. The pixels are §6.2's
 * `Modifier.shimmer` in `Shimmer.kt`; this object decides only what is drawn and how long it is.
 *
 * <p>**One reading of §6.1's sketch is flagged rather than assumed.** The design writes
 * `bars(shape, width, lineHeight, textSize)`. Two of those four are not inputs to a bar's *length*:
 * `lineHeight` and `textSize` are the row's own line box, and the only thing they decide is a bar's
 * *thickness* - §6.1's "bars scale monotonically with `textSize`, which is what makes the §4.7
 * font-size setting apply to covers too". So the sketch's one call is this file's two: [bars] takes
 * the shape and the width and answers pixel lengths, [barHeight] takes the line box and answers the
 * thickness. Keeping the sketch's signature would have left two parameters dead in a function whose
 * whole contract is what it returns, which is the kind of reading this lane records rather than
 * guesses at.
 *
 * <p>**The seed moves the whole block, never one line against another.** §6.1 says the seed "is a
 * 64-bit hash of the message id, so the same message shimmers the same shape on every bind", and its
 * test list asks that two seeds differ. A per-line wobble would do that, but it could also invert the
 * two facts the shape carries: [MIN_LINE_FRACTION] is a floor - "a line is never shorter than this" -
 * and the last line must stay shorter than the rest. One shape-wide factor, derived from the seed and
 * never below [MIN_SEED_SCALE], keeps every line's order and its floor, so a different message is a
 * different size of the same text-like block rather than a rewrapped one.
 */
object Placeholder {

    /** A line is never shorter than this fraction of the width (§6.1). */
    const val MIN_LINE_FRACTION = 0.35f

    /**
     * The last line's fraction when a shape declares more lines than it has fractions (§6.1). The
     * projection supplies every line's own envelope - `0.75` and `0.5` today - so this is the rule's
     * fallback for a shape that does not, and §6.1's wording for it is "the last line reads as a text
     * line, not a full bar".
     */
    const val LAST_LINE_FRACTION = 0.65f

    /**
     * The shortest a seed may make a block, as a fraction of the shape's own lengths. `0.85` is far
     * enough from [MIN_LINE_FRACTION] that a shape at the wide end is still measurably different from
     * the same shape at the narrow end, and close enough to 1 that two messages of one length read as
     * the same kind of block.
     */
    const val MIN_SEED_SCALE = 0.85f

    /** The travelling highlight's length as a fraction of the width (§6.2's band). */
    const val BAND_OF_WIDTH = 0.35f

    /**
     * One dp, so two adjacent bars do not fuse into one rectangle (§6.1). It is a *dp* number: the
     * drawing converts it with the display's own density before it subtracts it from a bar's
     * thickness, so it lives here, beside the rule, and never as a pixel literal in the composable.
     */
    const val BAR_INSET = 1f

    /**
     * The pixel length of each bar, one per line of the shape, in drawing order.
     *
     * <p>`shape.widths` is the projection's per-line envelope (§2.2's `PlaceholderShape`); each length
     * is that envelope clamped into `[MIN_LINE_FRACTION, 1]` and scaled by the seed's shape-wide
     * factor. A shape that declares more lines than it has fractions falls back to a full line, and to
     * [LAST_LINE_FRACTION] for its own last one.
     *
     * <p>Nothing to draw answers an empty array rather than a crash (§6.1's degenerate inputs): no
     * lines, or no width.
     */
    @JvmStatic
    fun bars(shape: PlaceholderShape, width: Float): FloatArray {
        if (shape.lines <= 0 || width <= 0f) {
            return FloatArray(0)
        }
        val scale = MIN_SEED_SCALE + (1f - MIN_SEED_SCALE) * unit(shape.seed)
        val bars = FloatArray(shape.lines)
        for (line in 0 until shape.lines) {
            val envelope = shape.widths.getOrElse(line) {
                if (line == shape.lines - 1) LAST_LINE_FRACTION else 1f
            }
            val fraction = (envelope.coerceIn(MIN_LINE_FRACTION, 1f) * scale).coerceIn(MIN_LINE_FRACTION, 1f)
            bars[line] = fraction * width
        }
        return bars
    }

    /**
     * A bar's thickness: the text size, never more than the line it sits in. A line box is normally a
     * little taller than its text, so the text size is what carries the §4.7 font-size setting into
     * the cover, and a line shorter than its own text - which no theme here draws, but a caller may
     * hand over - is not overflowed.
     *
     * <p>Nothing measurable answers `0`, so a degenerate line box draws no bar rather than one of
     * negative height.
     */
    @JvmStatic
    fun barHeight(lineHeight: Float, textSize: Float): Float =
        if (lineHeight <= 0f || textSize <= 0f) {
            0f
        } else {
            if (lineHeight < textSize) lineHeight else textSize
        }

    /**
     * The travelling band's two x-offsets at this phase, for §6.2's one three-stop
     * `Brush.linearGradient`.
     *
     * <p>The band is [BAND_OF_WIDTH] of the width long and travels from just off the left edge to just
     * off the right one as the phase goes `0` to `1`, so the wrap at the end of a cycle is out of sight
     * on both sides and no bar shows a hard edge; outside the band's own segment the gradient clamps to
     * the bar's base colour. The phase wraps, so a caller may hand over any number the frame clock
     * produced - including a negative one, and `NaN` means "no travel" rather than a band that never
     * lands.
     */
    @JvmStatic
    fun highlightAt(phase: Float, width: Float): Pair<Float, Float> {
        if (width <= 0f || width.isNaN()) {
            return 0f to 0f
        }
        val band = width * BAND_OF_WIDTH
        val left = wrap(phase) * (width + 2f * band) - band
        return left to (left + band)
    }

    /** A phase inside `[0, 1)`, and `0` for one that is not a number at all. */
    private fun wrap(phase: Float): Float {
        val wrapped = phase % 1f
        return when {
            wrapped.isNaN() -> 0f
            wrapped < 0f -> wrapped + 1f
            else -> wrapped
        }
    }

    /**
     * A seed as a number in `[0, 1)`.
     *
     * <p>The top 24 bits are taken after a mix, because §6.1's seed is an FNV-1a hash of an id, and two
     * ids that differ in one character share most of their bits: the low bits of that hash are the ones
     * that move, and this multiply-xor chain spreads them over the top of the word. The chain is
     * splitmix64's finalizer, the same two constants in the same order.
     */
    private fun unit(seed: Long): Float {
        var x = seed
        x = (x xor (x ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        x = (x xor (x ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        x = x xor (x ushr 31)
        return ((x ushr 40) and 0xFFFFFFL).toFloat() / 16777216f
    }
}
