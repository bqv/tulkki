package com.zomato.photofilters.imageprocessors.subfilters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.zomato.photofilters.imageprocessors.SubFilter
import uk.xa0.tulkki.ui.R

/**
 * @author varun
 * Subfilter to add Vignette effect on an image
 */
class VignetteSubfilter(
    private val context: Context,
    // value of alpha is between 0-255
    private var alpha: Int,
) : SubFilter {

    override fun process(inputImage: Bitmap): Bitmap {
        var vignette = BitmapFactory.decodeResource(context.resources, R.drawable.vignette)

        vignette = Bitmap.createScaledBitmap(vignette, inputImage.width, inputImage.height, true)
        val paint = Paint()
        paint.isAntiAlias = true
        paint.alpha = alpha

        val comboImage = Canvas(inputImage)
        comboImage.drawBitmap(vignette, 0f, 0f, paint)

        return inputImage
    }

    override fun getTag(): Any? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    /**
     * Change alpha value to new value
     */
    fun setAlpha(alpha: Int) {
        this.alpha = alpha
    }

    /**
     * Changes alpha value by that number
     */
    fun changeAlpha(value: Int) {
        this.alpha += value
        if (alpha > 255) {
            alpha = 255
        } else if (alpha < 0) {
            alpha = 0
        }
    }

    private companion object {
        private var tag: String? = ""
    }
}
