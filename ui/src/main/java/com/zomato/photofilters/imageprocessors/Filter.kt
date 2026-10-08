package com.zomato.photofilters.imageprocessors

import android.graphics.Bitmap

/**
 * This Class represents a ImageFilter and includes many subfilters within, we add different subfilters to this class's
 * object and they are then processed in that particular order
 */
class Filter {
    private var subFilters: MutableList<SubFilter> = ArrayList()
    var name: String = ""

    constructor()

    constructor(name: String) {
        this.name = name
    }

    constructor(filter: Filter) {
        this.subFilters = filter.subFilters
    }

    /**
     * Adds a Subfilter to the Main Filter
     *
     * @param subFilter Subfilter like contrast, brightness, tone Curve etc. subfilter
     * @see BrightnessSubFilter
     * @see ColorOverlaySubFilter
     * @see ContrastSubFilter
     * @see ToneCurveSubFilter
     * @see VignetteSubfilter
     * @see com.zomato.photofilters.imageprocessors.subfilters.SaturationSubfilter
     */
    fun addSubFilter(subFilter: SubFilter) {
        subFilters.add(subFilter)
    }

    /**
     * Clears all the subfilters from the Parent Filter
     */
    fun clearSubFilters() {
        subFilters.clear()
    }

    /**
     * Removes the subfilter containing Tag from the Parent Filter
     */
    fun removeSubFilterWithTag(tag: String) {
        val iterator = subFilters.iterator()
        while (iterator.hasNext()) {
            val subFilter = iterator.next()
            val subFilterTag = subFilter.getTag() ?: throw NullPointerException()
            if (subFilterTag == tag) {
                iterator.remove()
            }
        }
    }

    /**
     * Returns The filter containing Tag
     */
    fun getSubFilterByTag(tag: String): SubFilter? {
        for (subFilter in subFilters) {
            val subFilterTag = subFilter.getTag() ?: throw NullPointerException()
            if (subFilterTag == tag) {
                return subFilter
            }
        }
        return null
    }

    /**
     * Give the output Bitmap by applying the defined filter
     *
     * @param inputImage Input Bitmap on which filter is to be applied
     * @return filtered Bitmap
     */
    fun processFilter(inputImage: Bitmap): Bitmap {
        var outputImage = inputImage
        for (subFilter in subFilters) {
            try {
                outputImage = subFilter.process(outputImage)
            } catch (oe: OutOfMemoryError) {
                System.gc()
                try {
                    outputImage = subFilter.process(outputImage)
                } catch (ignored: OutOfMemoryError) {
                }
            }
        }
        return outputImage
    }
}
