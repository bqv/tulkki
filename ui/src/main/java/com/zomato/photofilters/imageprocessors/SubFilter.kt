package com.zomato.photofilters.imageprocessors

import android.graphics.Bitmap

/**
 * @author varun on 27/07/15.
 */
interface SubFilter {
    fun process(inputImage: Bitmap): Bitmap

    fun getTag(): Any?

    fun setTag(tag: Any?)
}
