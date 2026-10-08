package com.zomato.photofilters.imageprocessors.subfilters

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.ImageProcessor
import com.zomato.photofilters.imageprocessors.SubFilter

/**
 * @author varun on 28/07/15.
 */
class SaturationSubfilter(
    private var level: Float,
) : SubFilter {

    // The Level value is float, where level = 1 has no effect on the image
    override fun process(inputImage: Bitmap): Bitmap = ImageProcessor.doSaturation(inputImage, level)

    override fun getTag(): Any? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    fun setLevel(level: Float) {
        this.level = level
    }

    private companion object {
        private var tag: String? = ""
    }
}
