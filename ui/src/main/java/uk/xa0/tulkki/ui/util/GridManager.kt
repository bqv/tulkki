package uk.xa0.tulkki.ui.util

import android.content.Context
import android.util.Log
import android.view.ViewTreeObserver
import androidx.annotation.DimenRes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uk.xa0.tulkki.ui.adapter.MediaAdapter
import uk.xa0.tulkki.xmpp.Config

object GridManager {

    @JvmStatic
    fun setupLayoutManager(context: Context, recyclerView: RecyclerView, @DimenRes desiredSize: Int) {
        val maxWidth = context.resources.displayMetrics.widthPixels
        val columnInfo = calculateColumnCount(context, maxWidth, desiredSize)
        Log.d(Config.LOGTAG, "preliminary count=" + columnInfo.count)
        MediaAdapter.setMediaSize(recyclerView, columnInfo.width)
        recyclerView.layoutManager = GridLayoutManager(context, columnInfo.count)
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val availableWidth = recyclerView.measuredWidth
                if (availableWidth == 0) {
                    Log.e(Config.LOGTAG, "GridManager: available width was 0; probably because layout was hidden")
                    return
                }
                val columnInfo = calculateColumnCount(context, recyclerView.measuredWidth, desiredSize)
                Log.d(Config.LOGTAG, "final count " + columnInfo.count)
                val adapter = recyclerView.adapter
                if (adapter != null && adapter.itemCount != 0) {
                    Log.e(Config.LOGTAG, "adapter already has items; just go with it now")
                    return
                }
                setupLayoutManagerInternal(recyclerView, columnInfo)
                MediaAdapter.setMediaSize(recyclerView, columnInfo.width)
            }
        })
    }

    private fun setupLayoutManagerInternal(recyclerView: RecyclerView, columnInfo: ColumnInfo) {
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is GridLayoutManager) {
            layoutManager.spanCount = columnInfo.count
        }
    }

    private fun calculateColumnCount(context: Context, availableWidth: Int, @DimenRes desiredSize: Int): ColumnInfo {
        val desiredWidth = context.resources.getDimension(desiredSize)
        val columns = Math.round(availableWidth / desiredWidth)
        val realWidth = availableWidth / columns
        Log.d(Config.LOGTAG, "desired=" + desiredWidth + " real=" + realWidth)
        return ColumnInfo(columns, realWidth)
    }

    @JvmStatic
    fun getCurrentColumnCount(recyclerView: RecyclerView): Int {
        val layoutManager = recyclerView.layoutManager
        return if (layoutManager is GridLayoutManager) {
            layoutManager.spanCount
        } else {
            0
        }
    }

    private class ColumnInfo(val count: Int, val width: Int)
}
