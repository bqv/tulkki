package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.PlaceholderShape

/**
 * §6.1's own test list, as far as it is arithmetic: "bar count equals `shape.lines`; widths inside
 * `[MIN_LINE_FRACTION, 1]`; the last line shorter than the rest; the same seed yields the same bars;
 * two seeds differ; bars scale monotonically with `textSize` ...; degenerate inputs (0 lines, 0 width,
 * 0 text size) produce an empty array rather than a crash".
 *
 * <p>Two cells are more than that list. The seed's shape-wide factor is pinned against the *shape's
 * order* - no seed may invert the projection's own lines - and the band is pinned on both sides of its
 * cycle, because the wrap is where a shimmer that travels past the edge would visibly jump.
 *
 * <p>Every cell is arithmetic; the drawing itself is not here, and §7.3 keeps whether a bar reads as
 * blurred text a device question.
 */
class PlaceholderTest {

    /** The projection's own shape today: two lines, `0.75` and `0.5`, and one message's seed. */
    private fun shape(seed: Long = 7L): PlaceholderShape =
        PlaceholderShape(2, floatArrayOf(0.75f, 0.5f), seed)

    private val delta = 1e-4f

    @Test
    fun theShapeDrawsOneBarPerLine() {
        Assert.assertEquals(2, Placeholder.bars(shape(), 300f).size)
        Assert.assertEquals(
            "a shape that declares three lines draws three bars",
            3,
            Placeholder.bars(PlaceholderShape(3, FloatArray(0), 7L), 300f).size,
        )
    }

    @Test
    fun everyBarStaysBetweenTheRulesOwnFloorAndTheWidth() {
        for (seed in 1L..64L) {
            val bars = Placeholder.bars(shape(seed), 300f)
            for ((line, bar) in bars.withIndex()) {
                val fraction = bar / 300f
                Assert.assertTrue(
                    "line $line at seed $seed is below the floor: $fraction",
                    fraction >= Placeholder.MIN_LINE_FRACTION - delta,
                )
                Assert.assertTrue(
                    "line $line at seed $seed runs past the width: $fraction",
                    fraction <= 1f + delta,
                )
            }
        }
    }

    @Test
    fun theLastLineIsShorterThanTheFirstOneWhateverTheSeed() {
        for (seed in 1L..64L) {
            val bars = Placeholder.bars(shape(seed), 300f)
            Assert.assertTrue(
                "the seed inverted the shape's own order at seed $seed: ${bars[0]} then ${bars[1]}",
                bars[1] < bars[0],
            )
        }
    }

    @Test
    fun theSameSeedDrawsTheSameBars() {
        Assert.assertArrayEquals(
            "a rebind of one message is not a new pattern",
            Placeholder.bars(shape(11L), 300f),
            Placeholder.bars(shape(11L), 300f),
            delta,
        )
    }

    @Test
    fun twoSeedsDrawDifferentBars() {
        Assert.assertFalse(
            "two messages of one length are not one block",
            Placeholder.bars(shape(1L), 300f).contentEquals(Placeholder.bars(shape(2L), 300f)),
        )
    }

    @Test
    fun theSeedMovesTheWholeBlockAndNotOneLineAgainstAnother() {
        // Same envelope, different seeds: the two lines keep their ratio, so the seed is a size and
        // never a rewrap (the reason it is one factor and not a per-line wobble).
        val one = Placeholder.bars(shape(3L), 300f)
        val other = Placeholder.bars(shape(99L), 300f)
        Assert.assertEquals(
            "the shape's own proportion survives the seed",
            (one[1] / one[0]).toDouble(),
            (other[1] / other[0]).toDouble(),
            delta.toDouble(),
        )
    }

    @Test
    fun aShapeShorterThanTheFloorIsLiftedToIt() {
        val bars = Placeholder.bars(PlaceholderShape(1, floatArrayOf(0.1f), 7L), 300f)
        Assert.assertEquals(
            "the floor wins over a shape that asks for less",
            Placeholder.MIN_LINE_FRACTION * 300f,
            bars[0],
            delta,
        )
    }

    @Test
    fun aShapeWithFewerFractionsThanLinesFallsBackToTheRulesOwnNumbers() {
        val bars = Placeholder.bars(PlaceholderShape(3, floatArrayOf(0.8f), 7L), 300f)
        Assert.assertEquals(3, bars.size)
        Assert.assertTrue("a line with no fraction of its own is a full one", bars[1] > bars[0])
        Assert.assertTrue("and the last one is still the shortest", bars[2] < bars[1])
    }

    @Test
    fun nothingToDrawAnswersNoBarsRatherThanACrash() {
        Assert.assertEquals(0, Placeholder.bars(PlaceholderShape(0, FloatArray(0), 7L), 300f).size)
        Assert.assertEquals("no width, no bars", 0, Placeholder.bars(shape(), 0f).size)
        Assert.assertEquals("and a width that cannot be a width is not one", 0, Placeholder.bars(shape(), -1f).size)
    }

    @Test
    fun aBarIsNeverThickerThanTheLineItSitsIn() {
        Assert.assertEquals("a line taller than its text gives the bar the text's own size", 16f, Placeholder.barHeight(20f, 16f), delta)
        Assert.assertEquals("and a line shorter than its text is not overflowed", 14f, Placeholder.barHeight(14f, 16f), delta)
    }

    @Test
    fun theThicknessFollowsTheTextSize() {
        // §4.7's font-size setting reaches a cover: the bar grows with the text until the line box
        // itself is the limit, and never shrinks as the text grows.
        var previous = 0f
        for (step in 8..19) {
            val textSize = step.toFloat()
            val thickness = Placeholder.barHeight(20f, textSize)
            Assert.assertTrue("a bigger text is never a thinner bar", thickness >= previous)
            Assert.assertEquals("below the line box the bar is the text", textSize, thickness, delta)
            previous = thickness
        }
        Assert.assertEquals("and the line box is the ceiling", 20f, Placeholder.barHeight(20f, 30f), delta)
    }

    @Test
    fun nothingMeasurableDrawsNoThickness() {
        Assert.assertEquals(0f, Placeholder.barHeight(0f, 16f), delta)
        Assert.assertEquals(0f, Placeholder.barHeight(20f, 0f), delta)
        Assert.assertEquals(0f, Placeholder.barHeight(-1f, 16f), delta)
    }

    @Test
    fun theBandKeepsItsLengthAtEveryPhase() {
        val expected = 300f * Placeholder.BAND_OF_WIDTH
        for (step in 0..10) {
            val (from, to) = Placeholder.highlightAt(step / 10f, 300f)
            Assert.assertEquals("the band is one length at phase $step", expected, to - from, delta)
        }
    }

    @Test
    fun theBandEntersFromTheLeftAndLeavesToTheRight() {
        val start = Placeholder.highlightAt(0f, 300f)
        Assert.assertEquals("a cycle starts with nothing of the band on the bar", 0f, start.second, delta)
        Assert.assertTrue("it begins off the left edge", start.first < 0f)
        val almostDone = Placeholder.highlightAt(0.99f, 300f)
        Assert.assertTrue("and leaves past the right one", almostDone.first > 300f)
    }

    @Test
    fun theBandTravelsOneWayAsThePhaseRises() {
        var previous = Placeholder.highlightAt(0f, 300f).first
        // Up to but not including a whole cycle, which is the phase `theCycleRepeatsItself` pins.
        for (step in 1..9) {
            val now = Placeholder.highlightAt(step / 10f, 300f).first
            Assert.assertTrue("the band went back at phase $step", now > previous)
            previous = now
        }
    }

    @Test
    fun theCycleRepeatsItself() {
        Assert.assertEquals(Placeholder.highlightAt(0f, 300f), Placeholder.highlightAt(1f, 300f))
        Assert.assertEquals("a whole extra cycle is the same place", Placeholder.highlightAt(0f, 300f), Placeholder.highlightAt(3f, 300f))
        Assert.assertEquals("and so is the same phase below zero", Placeholder.highlightAt(0.25f, 300f), Placeholder.highlightAt(-0.75f, 300f))
    }

    @Test
    fun aBandThatCannotBeDrawnIsNotInvented() {
        Assert.assertEquals("no width, no band", 0f to 0f, Placeholder.highlightAt(0.5f, 0f))
        Assert.assertEquals("a phase that is not a number is no travel, not a stray band", Placeholder.highlightAt(0f, 300f), Placeholder.highlightAt(Float.NaN, 300f))
    }
}
