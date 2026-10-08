/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import uk.xa0.tulkki.ui.R

/**
 * @author Andreas Schildbach
 */
class ScannerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val maskPaint = Paint()
    private val laserPaint = Paint()
    private val dotPaint = Paint()
    private var isResult = false
    private val maskColor: Int
    private val maskResultColor: Int
    private val laserColor: Int
    private val dotColor: Int
    private val dotResultColor: Int
    private val dots = HashMap<FloatArray, Long>(16)
    private var frame: Rect? = null
    private val matrix = Matrix()

    init {
        val resources = context.resources
        maskColor = ContextCompat.getColor(context, R.color.black54)
        maskResultColor = ContextCompat.getColor(context, uk.xa0.tulkki.data.R.color.black87)
        laserColor = ContextCompat.getColor(context, R.color.red_500)
        dotColor = ContextCompat.getColor(context, R.color.orange_500)
        dotResultColor = ContextCompat.getColor(context, R.color.green_500)

        maskPaint.style = Paint.Style.FILL

        laserPaint.strokeWidth = resources.getDimensionPixelSize(R.dimen.scan_laser_width).toFloat()
        laserPaint.style = Paint.Style.STROKE

        dotPaint.alpha = DOT_OPACITY
        dotPaint.style = Paint.Style.STROKE
        dotPaint.strokeWidth = resources.getDimension(R.dimen.scan_dot_size)
        dotPaint.isAntiAlias = true
    }

    fun setFraming(
        frame: Rect,
        framePreview: RectF,
        displayRotation: Int,
        cameraRotation: Int,
        cameraFlip: Boolean
    ) {
        this.frame = frame
        matrix.setRectToRect(framePreview, RectF(frame), Matrix.ScaleToFit.FILL)
        matrix.postRotate(
            (-displayRotation).toFloat(), frame.exactCenterX(), frame.exactCenterY()
        )
        matrix.postScale(
            if (cameraFlip) -1f else 1f, 1f, frame.exactCenterX(), frame.exactCenterY()
        )
        matrix.postRotate(cameraRotation.toFloat(), frame.exactCenterX(), frame.exactCenterY())

        invalidate()
    }

    fun setIsResult(isResult: Boolean) {
        this.isResult = isResult

        invalidate()
    }

    fun addDot(dot: ResultPoint) {
        dots[floatArrayOf(dot.x, dot.y)] = System.currentTimeMillis()

        invalidate()
    }

    public override fun onDraw(canvas: Canvas) {
        val f = frame ?: return

        val now = System.currentTimeMillis()

        val width = canvas.width
        val height = canvas.height

        val point = FloatArray(2)

        // draw mask darkened
        maskPaint.color = if (isResult) maskResultColor else maskColor
        canvas.drawRect(0f, 0f, width.toFloat(), f.top.toFloat(), maskPaint)
        canvas.drawRect(
            0f, f.top.toFloat(), f.left.toFloat(), (f.bottom + 1).toFloat(), maskPaint
        )
        canvas.drawRect(
            (f.right + 1).toFloat(), f.top.toFloat(), width.toFloat(),
            (f.bottom + 1).toFloat(), maskPaint
        )
        canvas.drawRect(
            0f, (f.bottom + 1).toFloat(), width.toFloat(), height.toFloat(), maskPaint
        )

        if (isResult) {
            laserPaint.color = dotResultColor
            laserPaint.alpha = 160

            dotPaint.color = dotResultColor
        } else {
            laserPaint.color = laserColor
            val laserPhase = (now / 600) % 2 == 0L
            laserPaint.alpha = if (laserPhase) 160 else 255

            dotPaint.color = dotColor

            // schedule redraw
            postInvalidateDelayed(LASER_ANIMATION_DELAY_MS)
        }

        canvas.drawRect(f, laserPaint)

        // draw points
        val i = dots.entries.iterator()
        while (i.hasNext()) {
            val entry = i.next()
            val age = now - entry.value
            if (age < DOT_TTL_MS) {
                dotPaint.alpha = ((DOT_TTL_MS - age) * 256 / DOT_TTL_MS).toInt()

                matrix.mapPoints(point, entry.key)
                canvas.drawPoint(point[0], point[1], dotPaint)
            } else {
                i.remove()
            }
        }
    }

    companion object {
        private const val LASER_ANIMATION_DELAY_MS = 100L
        private const val DOT_OPACITY = 0xa0
        private const val DOT_TTL_MS = 500
    }
}
