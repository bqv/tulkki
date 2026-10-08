package com.zomato.photofilters.imageprocessors.subfilters

import android.graphics.Bitmap
import com.zomato.photofilters.geometry.BezierSpline
import com.zomato.photofilters.geometry.Point
import com.zomato.photofilters.imageprocessors.ImageProcessor
import com.zomato.photofilters.imageprocessors.SubFilter

/**
 * @author varun
 *         Subfilter to tweak rgb channels of an image
 */
class ToneCurveSubFilter(
    rgbKnots: Array<Point>?,
    redKnots: Array<Point>?,
    greenKnots: Array<Point>?,
    blueKnots: Array<Point>?,
) : SubFilter {

    // These are knots which contains the plot points
    private var rgbKnots: Array<Point>?
    private var greenKnots: Array<Point>?
    private var redKnots: Array<Point>?
    private var blueKnots: Array<Point>?
    private var rgb: IntArray? = null
    private var r: IntArray? = null
    private var g: IntArray? = null
    private var b: IntArray? = null

    init {
        val straightKnots = arrayOf(Point(0f, 0f), Point(255f, 255f))
        this.rgbKnots = rgbKnots ?: straightKnots
        this.redKnots = redKnots ?: straightKnots
        this.greenKnots = greenKnots ?: straightKnots
        this.blueKnots = blueKnots ?: straightKnots
    }

    override fun process(inputImage: Bitmap): Bitmap {
        rgbKnots = sortPointsOnXAxis(rgbKnots)
        redKnots = sortPointsOnXAxis(redKnots)
        greenKnots = sortPointsOnXAxis(greenKnots)
        blueKnots = sortPointsOnXAxis(blueKnots)
        if (rgb == null) {
            rgb = BezierSpline.curveGenerator(rgbKnots)
        }

        if (r == null) {
            r = BezierSpline.curveGenerator(redKnots)
        }

        if (g == null) {
            g = BezierSpline.curveGenerator(greenKnots)
        }

        if (b == null) {
            b = BezierSpline.curveGenerator(blueKnots)
        }

        return ImageProcessor.applyCurves(rgb, r, g, b, inputImage)
    }

    fun sortPointsOnXAxis(points: Array<Point>?): Array<Point>? {
        if (points == null) {
            return null
        }
        for (s in 1 until points.size - 1) {
            for (k in 0..points.size - 2) {
                if (points[k].x > points[k + 1].x) {
                    val temp = points[k].x
                    points[k].x = points[k + 1].x //swapping values
                    points[k + 1].x = temp
                }
            }
        }
        return points
    }

    override fun getTag(): String? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    private companion object {
        private var tag: String? = ""
    }
}
