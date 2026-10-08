package uk.xa0.tulkki.ui

import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class ExtendedFabSizeChanger private constructor(
    private val extendedFloatingActionButton: ExtendedFloatingActionButton,
) : RecyclerView.OnScrollListener() {

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        if (RecyclerViews.findFirstVisibleItemPosition(recyclerView) > 0) {
            extendedFloatingActionButton.shrink()
        } else {
            extendedFloatingActionButton.extend()
        }
    }

    companion object {
        @JvmStatic
        fun of(fab: ExtendedFloatingActionButton): RecyclerView.OnScrollListener =
            ExtendedFabSizeChanger(fab)
    }
}
