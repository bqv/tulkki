package uk.xa0.tulkki.data.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan

class InlineImageSpan(d: Drawable, private val source: String?) :
    ImageSpan(d.constantState?.newDrawable() ?: d, source ?: "") {

    // ImageSpan's own `source` parameter is @NonNull in android.jar while the Java class this port
    // replaces passed a possibly-null source straight through; carrying the field and answering
    // getSource() keeps the null the Java callers handed over.
    override fun getSource(): String? = source

    private val mTmpFontMetrics = Paint.FontMetricsInt()
    private val dHeight: Float = d.intrinsicHeight.toFloat()
    private val dWidth: Float = d.intrinsicWidth.toFloat()

    init {
        if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
            (drawable as AnimatedImageDrawable).start()
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        paint.getFontMetricsInt(mTmpFontMetrics)
        val fontHeight = Math.abs(mTmpFontMetrics.descent - mTmpFontMetrics.ascent)
        val mRatio = fontHeight * 1.0f / dHeight
        val mWidth = (dWidth * mRatio).toInt()
        drawable.setBounds(0, 0, dWidth.toInt(), dHeight.toInt())
        if (fm != null) {
            fm.ascent = mTmpFontMetrics.ascent
            fm.descent = mTmpFontMetrics.descent
            fm.top = mTmpFontMetrics.top
            fm.bottom = mTmpFontMetrics.bottom
        }
        return mWidth
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        paint.getFontMetricsInt(mTmpFontMetrics)
        val fontHeight = Math.abs(mTmpFontMetrics.descent - mTmpFontMetrics.ascent)
        val mRatio = fontHeight * 1.0f / dHeight

        val b = drawable
        canvas.save()

        var transY = bottom - (dHeight * mRatio).toInt()
        if (mVerticalAlignment == DynamicDrawableSpan.ALIGN_BASELINE) {
            transY -= paint.fontMetricsInt.descent
        }

        canvas.translate(x, transY.toFloat())
        canvas.scale(mRatio, mRatio)
        b.draw(canvas)
        canvas.restore()
    }
}
