package com.zomato.photofilters.imageprocessors.subfilters

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.ImageProcessor
import com.zomato.photofilters.imageprocessors.SubFilter

/**
 * @author varun
 * subfilter used to tweak brightness of the Bitmap
 */
class BrightnessSubFilter(
    private var brightness: Int,
) : SubFilter {

    override fun process(inputImage: Bitmap): Bitmap = ImageProcessor.doBrightness(brightness, inputImage)

    override fun getTag(): String? = tag

    override fun setTag(tag: Any?) {
        Companion.tag = tag as String?
    }

    fun setBrightness(brightness: Int) {
        this.brightness = brightness
    }

    /**
     * Changes the brightness by the value passed as parameter
     */
    fun changeBrightness(value: Int) {
        this.brightness += value
    }

    private companion object {
        private var tag: String? = ""
    }
}
