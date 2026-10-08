package com.zomato.photofilters.geometry

import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.view.animation.PathInterpolator

/**
 * @author Varun on 29/06/15.
 */
object BezierSpline {

    /**
     * Generates Curve {in a plane ranging from 0-255} using the knots provided
     */
    @JvmStatic
    fun curveGenerator(knots: Array<Point>?): IntArray {
        if (knots == null) {
            throw NullPointerException("Knots cannot be null")
        }

        val n = knots.size - 1
        if (n < 1) {
            throw IllegalArgumentException("Atleast two points are required")
        }

        return if (Build.VERSION.SDK_INT >= 21) {
            getOutputPointsForNewerDevices(knots)
        } else {
            getOutputPointsForOlderDevices(knots)
        }
    }

    // This is for lollipop and newer devices
    private fun getOutputPointsForNewerDevices(knots: Array<Point>): IntArray {

        val controlPoints = calculateControlPoints(knots)
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(knots[0].x / 255.0f, knots[0].y / 255.0f)
        path.moveTo(knots[0].x / 255.0f, knots[0].y / 255.0f)

        for (index in 1 until knots.size) {
            path.quadTo(
                controlPoints[index - 1].x / 255.0f,
                controlPoints[index - 1].y / 255.0f,
                knots[index].x / 255.0f,
                knots[index].y / 255.0f,
            )
            path.moveTo(knots[index].x / 255.0f, knots[index].y / 255.0f)
        }

        path.lineTo(1f, 1f)
        path.moveTo(1f, 1f)

        val allPoints = FloatArray(256)

        for (x in 0 until 256) {
            val pathInterpolator = PathInterpolator(path)
            allPoints[x] = 255.0f * pathInterpolator.getInterpolation(x.toFloat() / 255.0f)
        }

        allPoints[0] = knots[0].y
        allPoints[255] = knots[knots.size - 1].y
        return validateCurve(allPoints)
    }

    //This is for devices older than lollipop
    private fun getOutputPointsForOlderDevices(knots: Array<Point>): IntArray {
        val controlPoints = calculateControlPoints(knots)
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(knots[0].x, knots[0].y)
        path.moveTo(knots[0].x, knots[0].y)

        for (index in 1 until knots.size) {
            path.quadTo(controlPoints[index - 1].x, controlPoints[index - 1].y, knots[index].x, knots[index].y)
            path.moveTo(knots[index].x, knots[index].y)
        }

        path.lineTo(255f, 255f)
        path.moveTo(255f, 255f)

        val allPoints = FloatArray(256)

        val pm = PathMeasure(path, false)
        for (i in 0 until 256) {
            allPoints[i] = -1f
        }

        var x = 0
        val acoordinates = floatArrayOf(0f, 0f)

        do {
            val pathLength = pm.length
            var i = 0f
            while (i <= pathLength) {
                pm.getPosTan(i, acoordinates, null)
                if (acoordinates[0].toInt() > x && x < 256) {
                    allPoints[x] = acoordinates[1]
                    x++
                }
                if (x > 255) {
                    break
                }
                i += 0.08f
            }
        } while (pm.nextContour())

        allPoints[0] = knots[0].y
        for (i in 0 until 256) {
            if (allPoints[i] == -1f) {
                allPoints[i] = allPoints[i - 1]
            }
        }
        allPoints[255] = knots[knots.size - 1].y
        return validateCurve(allPoints)
    }

    private fun validateCurve(allPoints: FloatArray): IntArray {
        val curvedPath = IntArray(256)
        for (x in 0 until 256) {
            curvedPath[x] = when {
                allPoints[x] > 255.0f -> 255
                allPoints[x] < 0.0f -> 0
                else -> Math.round(allPoints[x])
            }
        }
        return curvedPath
    }

    // Calculates the control points for the specified knots
    private fun calculateControlPoints(knots: Array<Point>): Array<Point> {
        val n = knots.size - 1

        if (n == 1) { // Special case: Bezier curve should be a straight line.
            // 3P1 = 2P0 + P3
            return arrayOf(
                Point((2 * knots[0].x + knots[1].x) / 3, (2 * knots[0].y + knots[1].y) / 3),
            )
        }

        // Calculate first Bezier control points
        // Right hand side vector
        val rhs = FloatArray(n)

        // Set right hand side x values
        for (i in 1 until n - 1) {
            rhs[i] = 4 * knots[i].x + 2 * knots[i + 1].x
        }
        rhs[0] = knots[0].x + 2 * knots[1].x
        rhs[n - 1] = (8 * knots[n - 1].x + knots[n].x) / 2.0f
        // Get first control points x-values
        val x = getFirstControlPoints(rhs)

        // Set right hand side y values
        for (i in 1 until n - 1) {
            rhs[i] = 4 * knots[i].y + 2 * knots[i + 1].y
        }
        rhs[0] = knots[0].y + 2 * knots[1].y
        rhs[n - 1] = (8 * knots[n - 1].y + knots[n].y) / 2.0f
        // Get first control points y-values
        val y = getFirstControlPoints(rhs)

        return Array(n) { i -> Point(x[i], y[i]) }
    }

    private fun getFirstControlPoints(rhs: FloatArray): FloatArray {
        val n = rhs.size
        val x = FloatArray(n) // Solution vector.
        val tmp = FloatArray(n) // Temp workspace.

        var b = 1.0f // Control Point Factor
        x[0] = rhs[0] / b
        for (i in 1 until n) { // Decomposition and forward substitution.
            tmp[i] = 1 / b
            b = (if (i < n - 1) 4.0f else 3.5f) - tmp[i]
            x[i] = (rhs[i] - x[i - 1]) / b
        }
        for (i in 1 until n) {
            x[n - i - 1] -= tmp[n - i] * x[n - i] // Backsubstitution.
        }
        return x
    }
}
