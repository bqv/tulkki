package com.zomato.photofilters.imageprocessors

import android.graphics.Bitmap
import uk.xa0.tulkki.ui.imaging.ImageFilters

/**
 * @author Varun on 29/06/15.
 */
object ImageProcessor {

    @JvmStatic
    fun applyCurves(rgb: IntArray?, red: IntArray?, green: IntArray?, blue: IntArray?, inputImage: Bitmap): Bitmap {
        // create output bitmap
        val outputImage = inputImage

        // get image size
        val width = inputImage.width
        val height = inputImage.height

        val pixels = IntArray(width * height)
        outputImage.getPixels(pixels, 0, width, 0, 0, width, height)

        if (rgb != null) {
            ImageFilters.applyRGBCurve(pixels, rgb, width, height)
        }

        if (!(red == null && green == null && blue == null)) {
            ImageFilters.applyChannelCurves(pixels, red, green, blue, width, height)
        }

        try {
            outputImage.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (ise: IllegalStateException) {
        }
        return outputImage
    }

    @JvmStatic
    fun doBrightness(value: Int, inputImage: Bitmap): Bitmap {
        val width = inputImage.width
        val height = inputImage.height
        val pixels = IntArray(width * height)

        inputImage.getPixels(pixels, 0, width, 0, 0, width, height)
        ImageFilters.doBrightness(pixels, value, width, height)
        inputImage.setPixels(pixels, 0, width, 0, 0, width, height)

        return inputImage
    }

    @JvmStatic
    fun doContrast(value: Float, inputImage: Bitmap): Bitmap {
        val width = inputImage.width
        val height = inputImage.height
        val pixels = IntArray(width * height)

        inputImage.getPixels(pixels, 0, width, 0, 0, width, height)
        ImageFilters.doContrast(pixels, value, width, height)
        inputImage.setPixels(pixels, 0, width, 0, 0, width, height)

        return inputImage
    }

    @JvmStatic
    fun doColorOverlay(depth: Int, red: Float, green: Float, blue: Float, inputImage: Bitmap): Bitmap {
        val width = inputImage.width
        val height = inputImage.height
        val pixels = IntArray(width * height)

        inputImage.getPixels(pixels, 0, width, 0, 0, width, height)
        ImageFilters.doColorOverlay(pixels, depth, red, green, blue, width, height)
        inputImage.setPixels(pixels, 0, width, 0, 0, width, height)

        return inputImage
    }

    @JvmStatic
    fun doSaturation(inputImage: Bitmap, level: Float): Bitmap {
        val width = inputImage.width
        val height = inputImage.height
        val pixels = IntArray(width * height)

        inputImage.getPixels(pixels, 0, width, 0, 0, width, height)
        ImageFilters.doSaturation(pixels, level, width, height)
        inputImage.setPixels(pixels, 0, width, 0, 0, width, height)
        return inputImage
    }
}
