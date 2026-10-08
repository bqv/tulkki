package com.zomato.photofilters.imageprocessors.subfilters

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.ImageProcessor
import com.zomato.photofilters.imageprocessors.SubFilter

/**
 * @author varun
 * Subfilter used to overlay bitmap with the color defined
 */
class ColorOverlaySubFilter(
    // the color overlay depth is between 0-255
    private val colorOverlayDepth: Int,
    // these values are between 0-1
    private val colorOverlayRed: Float,
    private val colorOverlayGreen: Float,
    private val colorOverlayBlue: Float,
) : SubFilter {

    override fun process(inputImage: Bitmap): Bitmap = ImageProcessor.doColorOverlay(
        colorOverlayDepth, colorOverlayRed, colorOverlayGreen, colorOverlayBlue, inputImage,
    )

    override fun getTag(): String? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    private companion object {
        private var tag: String? = ""
    }
}
