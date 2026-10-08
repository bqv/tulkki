package uk.xa0.tulkki.data.extras

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

// https://blog.jayway.com/2012/10/04/how-to-make-the-height-of-a-gridview-wrap-its-content/
class GridView : android.widget.GridView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSpec: Int

        if (layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            // The great Android "hackatlon", the love, the magic. The two leftmost bits in the height
            // measure spec have a special meaning, hence we can't use them to describe height.
            heightSpec =
                View.MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, View.MeasureSpec.AT_MOST)
        } else {
            // Any other height should be respected as is.
            heightSpec = heightMeasureSpec
        }

        super.onMeasure(widthMeasureSpec, heightSpec)
    }
}
