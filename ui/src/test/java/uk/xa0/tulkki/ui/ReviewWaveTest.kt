package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test

/**
 * The wave's shape, pinned as arithmetic: how big it is next to its text, that it is a wave rather
 * than a line, that it runs from one end of a flagged run to the other, and - the part a device would
 * otherwise be trusted for - that it stays under its own baseline and inside its own line.
 *
 * The numbers here are the bubble's own: a 44 px text size is this app's 16sp at the emulator's
 * density, and a descent of 11 px is what that text size has.
 */
class ReviewWaveTest {

    private fun centre(): Float =
        ReviewWave.centreY(BASELINE, DESCENT, LINE_BOTTOM, TEXT_SIZE, DENSITY)

    // -- size next to its text ----------------------------------------------------------------------

    @Test
    fun theAmplitudeIsAFractionOfTheTextSize() {
        Assert.assertEquals(44f * 0.09f, ReviewWave.amplitude(TEXT_SIZE), 0.0001f)
        Assert.assertEquals(22f * 0.09f, ReviewWave.amplitude(22f), 0.0001f)
    }

    @Test
    fun theWavelengthIsAFractionOfTheTextSize() {
        Assert.assertEquals(44f * 0.55f, ReviewWave.wavelength(TEXT_SIZE), 0.0001f)
        Assert.assertEquals(22f * 0.55f, ReviewWave.wavelength(22f), 0.0001f)
    }

    @Test
    fun aBiggerTextGetsAProportionallyBiggerWave() {
        val small = ReviewWave.amplitude(16f)
        val large = ReviewWave.amplitude(32f)

        Assert.assertEquals(2f, large / small, 0.0001f)
        Assert.assertEquals(2f, ReviewWave.wavelength(32f) / ReviewWave.wavelength(16f), 0.0001f)
    }

    @Test
    fun theStrokeHasADensityFloorSoASmallTextSizeIsStillVisible() {
        // 0.07 * 10 = 0.7px from the text, but 0.9 * 2.75 = 2.475px from the density: the floor wins.
        Assert.assertEquals(DENSITY * 0.9f, ReviewWave.thickness(10f, DENSITY), 0.0001f)
    }

    @Test
    fun theStrokeFollowsTheTextSizeOnceThatIsLargerThanTheFloor() {
        // 0.07 * 44 = 3.08px against the same 2.475px floor.
        Assert.assertEquals(TEXT_SIZE * 0.07f, ReviewWave.thickness(TEXT_SIZE, DENSITY), 0.0001f)
    }

    @Test
    fun aTextSizeThatIsNotThereCostsNothingRatherThanDividingByZero() {
        Assert.assertEquals(0f, ReviewWave.amplitude(0f), 0f)
        Assert.assertEquals(0f, ReviewWave.amplitude(-1f), 0f)
        Assert.assertEquals(0f, ReviewWave.wavelength(0f), 0f)
        Assert.assertEquals(0f, ReviewWave.thickness(0f, 0f), 0f)
    }

    // -- where it sits in the line ------------------------------------------------------------------

    @Test
    fun theWaveSitsBelowTheBaseline() {
        // Its top edge is the centre minus the amplitude; the whole wave is below the baseline, so
        // none of it can reach the glyphs of its own line or, above them, the descenders of the one
        // before - which is the collision the mark has to avoid on a two-line stretch.
        Assert.assertTrue(centre() - ReviewWave.amplitude(TEXT_SIZE) >= BASELINE)
    }

    @Test
    fun theWaveStaysInsideItsOwnLine() {
        // Its bottom edge is the centre plus the amplitude and half the stroke's width: the next
        // line's ascenders start at the line's bottom, and this must not cross it.
        Assert.assertTrue(
            centre() + ReviewWave.amplitude(TEXT_SIZE) + ReviewWave.thickness(TEXT_SIZE, DENSITY) / 2f <=
                LINE_BOTTOM + 0.0001f,
        )
    }

    @Test
    fun aTightLineIsNotAllowedToPushTheWaveAboveTheBaseline() {
        // A line bottom only just below the baseline leaves no room; the baseline rule wins, so the
        // wave grows towards the next line rather than up into the glyphs it marks.
        val tight = ReviewWave.centreY(BASELINE, DESCENT, BASELINE + 1f, TEXT_SIZE, DENSITY)

        Assert.assertTrue(tight - ReviewWave.amplitude(TEXT_SIZE) >= BASELINE)
    }

    @Test
    fun aGenerousLineLetsTheDescentPlaceTheWave() {
        // Room to spare: the centre is the descent's own fraction below the baseline, not the
        // clamped value, so the mark hugs the descenders instead of the line's bottom.
        val generous = ReviewWave.centreY(BASELINE, DESCENT, BASELINE + 200f, TEXT_SIZE, DENSITY)

        Assert.assertEquals(BASELINE + DESCENT * 0.72f, generous, 0.0001f)
    }

    // -- the wave itself ----------------------------------------------------------------------------

    @Test
    fun aRunWithNoWidthDrawsNothing() {
        Assert.assertEquals(0, ReviewWave.points(10f, 10f, centre(), TEXT_SIZE).size)
        Assert.assertEquals(0, ReviewWave.points(10f, 9f, centre(), TEXT_SIZE).size)
    }

    @Test
    fun theWaveRunsFromOneEndOfTheRunToTheOther() {
        val points = ReviewWave.points(10f, 120f, centre(), TEXT_SIZE)

        Assert.assertEquals(10f, points[0], 0.0001f)
        Assert.assertEquals(110f, points[points.size - 2] - points[0], 0.0001f)
        // Monotonic in x: one point per step, never doubling back.
        for (i in 2 until points.size step 2) {
            Assert.assertTrue("x must rise", points[i] > points[i - 2])
        }
    }

    @Test
    fun theWaveStartsOnItsCentreLine() {
        val points = ReviewWave.points(10f, 120f, centre(), TEXT_SIZE)

        Assert.assertEquals(centre(), points[1], 0.0001f)
    }

    @Test
    fun theWaveNeverLeavesItsAmplitudeBand() {
        val amplitude = ReviewWave.amplitude(TEXT_SIZE)
        val points = ReviewWave.points(10f, 200f, centre(), TEXT_SIZE)

        for (i in 1 until points.size step 2) {
            Assert.assertTrue(
                "above the band: ${points[i]}",
                points[i] <= centre() + amplitude + 0.0001f,
            )
            Assert.assertTrue(
                "below the band: ${points[i]}",
                points[i] >= centre() - amplitude - 0.0001f,
            )
        }
    }

    @Test
    fun onePeriodReachesBothTheCrestAndTheTrough() {
        // A whole wavelength is sampled at its crest and its trough, so the mark's full height is
        // really drawn and not an under-sampled fraction of it.
        val wavelength = ReviewWave.wavelength(TEXT_SIZE)
        val amplitude = ReviewWave.amplitude(TEXT_SIZE)
        val points = ReviewWave.points(0f, wavelength, centre(), TEXT_SIZE)
        var high = Float.NEGATIVE_INFINITY
        var low = Float.POSITIVE_INFINITY
        for (i in 1 until points.size step 2) {
            high = Math.max(high, points[i])
            low = Math.min(low, points[i])
        }

        Assert.assertEquals(centre() + amplitude, high, 0.0001f)
        Assert.assertEquals(centre() - amplitude, low, 0.0001f)
    }

    @Test
    fun theWaveIsSampledEightTimesAPeriod() {
        val wavelength = ReviewWave.wavelength(TEXT_SIZE)
        // Two whole periods: sixteen steps, seventeen points.
        Assert.assertEquals(17 * 2, ReviewWave.points(0f, 2f * wavelength, centre(), TEXT_SIZE).size)
    }

    @Test
    fun aLongerRunIsSampledMoreFinely() {
        val shortRun = ReviewWave.points(0f, 20f, centre(), TEXT_SIZE)
        val longRun = ReviewWave.points(0f, 200f, centre(), TEXT_SIZE)

        Assert.assertTrue(longRun.size > shortRun.size)
    }

    @Test
    fun aVeryShortRunStillGetsAPointPairRatherThanNothing() {
        // A single glyph's width is far below the sample spacing; the floor of two steps keeps the
        // mark a mark instead of an empty path.
        val points = ReviewWave.points(5f, 6f, centre(), TEXT_SIZE)

        Assert.assertEquals(6, points.size)
        Assert.assertEquals(5f, points[0], 0.0001f)
        Assert.assertEquals(6f, points[4], 0.0001f)
    }

    @Test
    fun aDegenerateTextSizeFallsBackToAStraightLineBetweenTheEnds() {
        // No wavelength to oscillate over: the run's ends are joined rather than left with a hole or
        // a division by zero.
        val points = ReviewWave.points(5f, 25f, 9f, 0f)

        Assert.assertArrayEquals(floatArrayOf(5f, 9f, 25f, 9f), points, 0.0001f)
    }

    private companion object {
        const val TEXT_SIZE = 44f

        const val DENSITY = 2.75f

        const val BASELINE = 100f

        const val DESCENT = 11f

        const val LINE_BOTTOM = 113f
    }
}
