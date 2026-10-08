package uk.xa0.tulkki.data.utils

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView

object Util {

    @JvmStatic
    fun justifyListViewHeightBasedOnChildren(listView: ListView) {
        justifyListViewHeightBasedOnChildren(listView, 0, false)
    }

    @JvmStatic
    fun justifyListViewHeightBasedOnChildren(listView: ListView, offset: Int, andWidth: Boolean) {
        val adapter: ListAdapter = listView.adapter ?: return
        val vg: ViewGroup = listView
        var totalHeight = 0
        var maxWidth = 0
        val displayWidth = listView.context.resources.displayMetrics.widthPixels
        val width = if (!andWidth && listView.width > 0) listView.width else (displayWidth - offset)
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        for (i in 0 until adapter.count) {
            val listItem: View = adapter.getView(i, null, vg)
            listItem.measure(widthSpec, 0)
            totalHeight += listItem.measuredHeight
            maxWidth = Math.max(maxWidth, listItem.measuredWidth)
        }

        val par: ViewGroup.LayoutParams = listView.layoutParams
        par.height = totalHeight + (listView.dividerHeight * Math.max(0, adapter.count - 1))
        if (andWidth) {
            if (maxWidth <= (displayWidth - offset) && maxWidth > offset * 2) {
                par.width = maxWidth
            } else {
                par.width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        listView.layoutParams = par
        listView.requestLayout()
    }
}
