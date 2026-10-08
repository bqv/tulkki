package uk.xa0.tulkki.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

object RecyclerViews {

    @JvmStatic
    fun scrolledToTop(recyclerView: RecyclerView): Boolean {
        val layoutManager = recyclerView.layoutManager
        return if (layoutManager is LinearLayoutManager) {
            layoutManager.findFirstCompletelyVisibleItemPosition() == 0
        } else {
            false
        }
    }

    @JvmStatic
    fun findFirstVisibleItemPosition(recyclerView: RecyclerView): Int {
        val layoutManager = recyclerView.layoutManager
        return if (layoutManager is LinearLayoutManager) {
            layoutManager.findFirstVisibleItemPosition()
        } else {
            RecyclerView.NO_POSITION
        }
    }
}
