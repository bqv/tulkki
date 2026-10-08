package com.zomato.photofilters.utils

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.Filter

/**
 * @author Varun on 01/07/15.
 * Updated on 30/10/2017 @author Ravi Tamada
 */
class ThumbnailItem {
    @JvmField
    var filterName: String? = null

    @JvmField
    var image: Bitmap? = null

    @JvmField
    var filter: Filter = Filter()
}
