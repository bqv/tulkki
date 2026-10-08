package com.zomato.photofilters

import android.content.Context
import com.zomato.photofilters.geometry.Point
import com.zomato.photofilters.imageprocessors.Filter
import com.zomato.photofilters.imageprocessors.subfilters.BrightnessSubFilter
import com.zomato.photofilters.imageprocessors.subfilters.ColorOverlaySubFilter
import com.zomato.photofilters.imageprocessors.subfilters.ContrastSubFilter
import com.zomato.photofilters.imageprocessors.subfilters.SaturationSubfilter
import com.zomato.photofilters.imageprocessors.subfilters.ToneCurveSubFilter
import com.zomato.photofilters.imageprocessors.subfilters.VignetteSubfilter
import uk.xa0.tulkki.ui.R

/**
 * Originally created by @author Varun on 01/07/15.
 *
 * Added filters by @author Ravi Tamada on 29/11/17.
 * Added multiple filters, the filter names were inspired from
 * various image filters apps
 */
object FilterPack {

    /**
     * the filter pack,
     * @param context
     * @return list of filters
     */
    @JvmStatic
    fun getFilterPack(context: Context): List<Filter> {
        val filters = ArrayList<Filter>()
        filters.add(getAweStruckVibeFilter(context))
        filters.add(getClarendon(context))
        filters.add(getOldManFilter(context))
        filters.add(getMarsFilter(context))
        filters.add(getRiseFilter(context))
        filters.add(getAprilFilter(context))
        filters.add(getAmazonFilter(context))
        filters.add(getStarLitFilter(context))
        filters.add(getNightWhisperFilter(context))
        filters.add(getLimeStutterFilter(context))
        filters.add(getHaanFilter(context))
        filters.add(getBlueMessFilter(context))
        filters.add(getAdeleFilter(context))
        filters.add(getCruzFilter(context))
        filters.add(getMetropolis(context))
        filters.add(getAudreyFilter(context))
        return filters
    }

    @JvmStatic
    fun getStarLitFilter(context: Context): Filter {
        val rgbKnots = arrayOf(
            Point(0f, 0f),
            Point(34f, 6f),
            Point(69f, 23f),
            Point(100f, 58f),
            Point(150f, 154f),
            Point(176f, 196f),
            Point(207f, 233f),
            Point(255f, 255f),
        )
        val filter = Filter()
        filter.name = context.getString(R.string.starlit)
        filter.addSubFilter(ToneCurveSubFilter(rgbKnots, null, null, null))
        return filter
    }

    @JvmStatic
    fun getBlueMessFilter(context: Context): Filter {
        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(86f, 34f),
            Point(117f, 41f),
            Point(146f, 80f),
            Point(170f, 151f),
            Point(200f, 214f),
            Point(225f, 242f),
            Point(255f, 255f),
        )
        val filter = Filter()
        filter.name = context.getString(R.string.bluemess)
        filter.addSubFilter(ToneCurveSubFilter(null, redKnots, null, null))
        filter.addSubFilter(BrightnessSubFilter(30))
        filter.addSubFilter(ContrastSubFilter(1f))
        return filter
    }

    @JvmStatic
    fun getAweStruckVibeFilter(context: Context): Filter {
        val rgbKnots = arrayOf(
            Point(0f, 0f),
            Point(80f, 43f),
            Point(149f, 102f),
            Point(201f, 173f),
            Point(255f, 255f),
        )

        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(125f, 147f),
            Point(177f, 199f),
            Point(213f, 228f),
            Point(255f, 255f),
        )

        val greenKnots = arrayOf(
            Point(0f, 0f),
            Point(57f, 76f),
            Point(103f, 130f),
            Point(167f, 192f),
            Point(211f, 229f),
            Point(255f, 255f),
        )

        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(38f, 62f),
            Point(75f, 112f),
            Point(116f, 158f),
            Point(171f, 204f),
            Point(212f, 233f),
            Point(255f, 255f),
        )

        val filter = Filter()
        filter.name = context.getString(R.string.struck)
        filter.addSubFilter(ToneCurveSubFilter(rgbKnots, redKnots, greenKnots, blueKnots))
        return filter
    }

    @JvmStatic
    fun getLimeStutterFilter(context: Context): Filter {
        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(165f, 114f),
            Point(255f, 255f),
        )
        val filter = Filter()
        filter.name = context.getString(R.string.lime)
        filter.addSubFilter(ToneCurveSubFilter(null, null, null, blueKnots))
        return filter
    }

    @JvmStatic
    fun getNightWhisperFilter(context: Context): Filter {
        val rgbKnots = arrayOf(
            Point(0f, 0f),
            Point(174f, 109f),
            Point(255f, 255f),
        )

        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(70f, 114f),
            Point(157f, 145f),
            Point(255f, 255f),
        )

        val greenKnots = arrayOf(
            Point(0f, 0f),
            Point(109f, 138f),
            Point(255f, 255f),
        )

        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(113f, 152f),
            Point(255f, 255f),
        )

        val filter = Filter()
        filter.name = context.getString(R.string.whisper)
        filter.addSubFilter(ContrastSubFilter(1.5f))
        filter.addSubFilter(ToneCurveSubFilter(rgbKnots, redKnots, greenKnots, blueKnots))
        return filter
    }

    @JvmStatic
    fun getAmazonFilter(context: Context): Filter {
        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(11f, 40f),
            Point(36f, 99f),
            Point(86f, 151f),
            Point(167f, 209f),
            Point(255f, 255f),
        )
        val filter = Filter(context.getString(R.string.amazon))
        filter.addSubFilter(ContrastSubFilter(1.2f))
        filter.addSubFilter(ToneCurveSubFilter(null, null, null, blueKnots))
        return filter
    }

    @JvmStatic
    fun getAdeleFilter(context: Context): Filter {
        val filter = Filter(context.getString(R.string.adele))
        filter.addSubFilter(SaturationSubfilter(-100f))
        return filter
    }

    @JvmStatic
    fun getCruzFilter(context: Context): Filter {
        val filter = Filter(context.getString(R.string.cruz))
        filter.addSubFilter(SaturationSubfilter(-100f))
        filter.addSubFilter(ContrastSubFilter(1.3f))
        filter.addSubFilter(BrightnessSubFilter(20))
        return filter
    }

    @JvmStatic
    fun getMetropolis(context: Context): Filter {
        val filter = Filter(context.getString(R.string.metropolis))
        filter.addSubFilter(SaturationSubfilter(-1f))
        filter.addSubFilter(ContrastSubFilter(1.7f))
        filter.addSubFilter(BrightnessSubFilter(70))
        return filter
    }

    @JvmStatic
    fun getAudreyFilter(context: Context): Filter {
        val filter = Filter(context.getString(R.string.audrey))

        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(124f, 138f),
            Point(255f, 255f),
        )

        filter.addSubFilter(SaturationSubfilter(-100f))
        filter.addSubFilter(ContrastSubFilter(1.3f))
        filter.addSubFilter(BrightnessSubFilter(20))
        filter.addSubFilter(ToneCurveSubFilter(null, redKnots, null, null))
        return filter
    }

    @JvmStatic
    fun getRiseFilter(context: Context): Filter {
        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(39f, 70f),
            Point(150f, 200f),
            Point(255f, 255f),
        )

        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(45f, 64f),
            Point(170f, 190f),
            Point(255f, 255f),
        )

        val filter = Filter(context.getString(R.string.rise))
        filter.addSubFilter(ContrastSubFilter(1.9f))
        filter.addSubFilter(BrightnessSubFilter(60))
        filter.addSubFilter(VignetteSubfilter(context, 200))
        filter.addSubFilter(ToneCurveSubFilter(null, redKnots, null, blueKnots))
        return filter
    }

    @JvmStatic
    fun getMarsFilter(context: Context): Filter {
        val filter = Filter(context.getString(R.string.mars))
        filter.addSubFilter(ContrastSubFilter(1.5f))
        filter.addSubFilter(BrightnessSubFilter(10))
        return filter
    }

    @JvmStatic
    fun getAprilFilter(context: Context): Filter {
        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(39f, 70f),
            Point(150f, 200f),
            Point(255f, 255f),
        )

        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(45f, 64f),
            Point(170f, 190f),
            Point(255f, 255f),
        )

        val filter = Filter(context.getString(R.string.april))
        filter.addSubFilter(ContrastSubFilter(1.5f))
        filter.addSubFilter(BrightnessSubFilter(5))
        filter.addSubFilter(VignetteSubfilter(context, 150))
        filter.addSubFilter(ToneCurveSubFilter(null, redKnots, null, blueKnots))
        return filter
    }

    @JvmStatic
    fun getHaanFilter(context: Context): Filter {
        val greenKnots = arrayOf(
            Point(0f, 0f),
            Point(113f, 142f),
            Point(255f, 255f),
        )

        val filter = Filter(context.getString(R.string.haan))
        filter.addSubFilter(ContrastSubFilter(1.3f))
        filter.addSubFilter(BrightnessSubFilter(60))
        filter.addSubFilter(VignetteSubfilter(context, 200))
        filter.addSubFilter(ToneCurveSubFilter(null, null, greenKnots, null))
        return filter
    }

    @JvmStatic
    fun getOldManFilter(context: Context): Filter {
        val filter = Filter(context.getString(R.string.oldman))
        filter.addSubFilter(BrightnessSubFilter(30))
        filter.addSubFilter(SaturationSubfilter(0.8f))
        filter.addSubFilter(ContrastSubFilter(1.3f))
        filter.addSubFilter(VignetteSubfilter(context, 100))
        filter.addSubFilter(ColorOverlaySubFilter(100, 0.2f, 0.2f, 0.1f))
        return filter
    }

    @JvmStatic
    fun getClarendon(context: Context): Filter {
        val redKnots = arrayOf(
            Point(0f, 0f),
            Point(56f, 68f),
            Point(196f, 206f),
            Point(255f, 255f),
        )

        val greenKnots = arrayOf(
            Point(0f, 0f),
            Point(46f, 77f),
            Point(160f, 200f),
            Point(255f, 255f),
        )

        val blueKnots = arrayOf(
            Point(0f, 0f),
            Point(33f, 86f),
            Point(126f, 220f),
            Point(255f, 255f),
        )

        val filter = Filter(context.getString(R.string.clarendon))
        filter.addSubFilter(ContrastSubFilter(1.5f))
        filter.addSubFilter(BrightnessSubFilter(-10))
        filter.addSubFilter(ToneCurveSubFilter(null, redKnots, greenKnots, blueKnots))
        return filter
    }
}
