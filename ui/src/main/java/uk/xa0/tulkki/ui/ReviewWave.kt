package uk.xa0.tulkki.ui

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * The shape of the squiggly mark under one of a note's stretches - a pure rule, so the wave can be
 * measured without a device.
 *
 * The mark a note gets is the word-processor spellcheck wave, and the platform draws no such thing:
 * a `TextPaint` knows an underline's colour and thickness and nothing about its shape, and a
 * `PathEffect` was never measured to shape it here. So the wave is drawn by hand, and everything that
 * decides what it looks like lives in this class - the amplitude, the wavelength, the stroke and
 * where the centre line sits - with no Android type in it at all. The span that draws it is then only
 * a conversion of these numbers into a `Path` on the canvas it is handed.
 *
 * Everything scales with the text size rather than being a constant number of pixels, so the mark
 * reads the same at the bubble's large font as at its normal one, and the stroke keeps a floor in
 * density so a small text size cannot shrink it to nothing. The size was not invented: it is what a
 * spellchecker's own wave is next to its text - roughly a tenth of the text size in height, about
 * half of it from crest to crest - which at the bubble's 16sp is a mark the eye reads as a mark
 * rather than as a second line of text.
 *
 * The centre line is placed from the font's own descent and then clamped into the line: never above
 * the baseline plus the whole amplitude, so nothing of the wave reaches up into the glyphs or, above
 * them, into the descenders of the line before; and never below the line's bottom, so a tight line
 * spacing cannot push it onto the next line's ascenders.
 */
object ReviewWave {

    /** Half the crest-to-trough height, as a fraction of the text size. */
    const val AMPLITUDE_OF_TEXT = 0.09f

    /** One full crest-to-crest period, as a fraction of the text size. */
    const val WAVELENGTH_OF_TEXT = 0.55f

    /** The stroke's width as a fraction of the text size. */
    const val THICKNESS_OF_TEXT = 0.07f

    /** A floor for the stroke in dp, so a small text size still gets a visible mark. */
    const val THICKNESS_OF_DENSITY = 0.9f

    /** Where the centre line sits between the baseline and the descent's bottom. */
    const val CENTRE_OF_DESCENT = 0.72f

    /**
     * Samples per period. Eight puts the polyline within a third of a pixel of the sine at the
     * bubble's text size, so the wave is smooth without a comparable number of path segments.
     */
    const val SAMPLES_PER_WAVELENGTH = 8

    /** Half the wave's crest-to-trough height at this text size. */
    @JvmStatic
    fun amplitude(textSize: Float): Float =
        if (textSize > 0f) textSize * AMPLITUDE_OF_TEXT else 0f

    /** The crest-to-crest distance at this text size. */
    @JvmStatic
    fun wavelength(textSize: Float): Float =
        if (textSize > 0f) textSize * WAVELENGTH_OF_TEXT else 0f

    /** The stroke's width: a fraction of the text size, never thinner than a density floor. */
    @JvmStatic
    fun thickness(textSize: Float, density: Float): Float {
        val fromText = if (textSize > 0f) textSize * THICKNESS_OF_TEXT else 0f
        val fromDensity = if (density > 0f) density * THICKNESS_OF_DENSITY else 0f
        return max(fromText, fromDensity)
    }

    /**
     * Where the wave's centre line sits for one line of text.
     *
     * @param baseline the line's baseline, in the canvas's coordinates
     * @param descent the font's descent at this text size, positive
     * @param lineBottom the line's bottom, which is what the next line starts from
     * @param textSize the paint's text size
     * @param density the display's density, for the stroke's floor
     * @return the y the wave oscillates around; the wave's own extent adds the amplitude and half
     *     the stroke's width either side of it
     */
    @JvmStatic
    fun centreY(
        baseline: Float,
        descent: Float,
        lineBottom: Float,
        textSize: Float,
        density: Float,
    ): Float {
        val amp = amplitude(textSize)
        // Half the stroke, because the line is centred on the path: that is what the drawn edge adds
        // to the geometric extent either side.
        val half = thickness(textSize, density) / 2f
        // Sit low in the line, just under the descenders, the way a spellchecker's wave does.
        var centre = baseline + max(descent, 0f) * CENTRE_OF_DESCENT
        // ...but never so high that any of the wave is above the baseline: that is the whole of the
        // distance to the line before, whose descenders hang into the top of this line's band.
        val highest = baseline + amp + half
        if (centre < highest) {
            centre = highest
        }
        // ...and never so low that it leaves the line.
        val lowest = lineBottom - amp - half
        if (centre > lowest) {
            centre = max(highest, lowest)
        }
        return centre
    }

    /**
     * The wave itself, as a flat `[x, y, x, y, ...]` array from `x0` to `x1`.
     *
     * The phase is fixed to the run - every run starts on its centre line and rises - so the same
     * flagged stretch always draws the same wave, and a run that was split across two lines starts
     * its own wave on each of them rather than one wave continuing off the line's edge.
     *
     * @return an empty array when the run has no width; two points when the text is degenerate
     */
    @JvmStatic
    fun points(x0: Float, x1: Float, centreY: Float, textSize: Float): FloatArray {
        val width = x1 - x0
        if (!(width > 0f)) {
            return FloatArray(0)
        }
        val wavelength = wavelength(textSize)
        if (!(wavelength > 0f)) {
            return floatArrayOf(x0, centreY, x1, centreY)
        }
        val steps = max(2, ceil((width / (wavelength / SAMPLES_PER_WAVELENGTH)).toDouble()).toInt())
        val amp = amplitude(textSize)
        val points = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val ratio = i.toFloat() / steps.toFloat()
            val x = x0 + width * ratio
            points[i * 2] = x
            points[i * 2 + 1] =
                centreY + amp * sin(2.0 * Math.PI * (x - x0) / wavelength).toFloat()
        }
        return points
    }
}
