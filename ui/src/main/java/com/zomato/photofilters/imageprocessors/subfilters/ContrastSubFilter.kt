package com.zomato.photofilters.imageprocessors.subfilters

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.ImageProcessor
import com.zomato.photofilters.imageprocessors.SubFilter

/**
 * @author varun
 *         Class to add Contrast Subfilter
 */
class ContrastSubFilter(
    private var contrast: Float,
) : SubFilter {

    override fun process(inputImage: Bitmap): Bitmap = ImageProcessor.doContrast(contrast, inputImage)

    override fun getTag(): String? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    /**
     * Sets the contrast value by the value passed in as parameter
     */
    fun setContrast(contrast: Float) {
        this.contrast = contrast
    }

    /**
     * Changes contrast value by the value passed in as a parameter
     */
    fun changeContrast(value: Float) {
        this.contrast += value
    }

    private companion object {
        private var tag: String? = ""
    }
}
