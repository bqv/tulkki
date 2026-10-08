package uk.xa0.tulkki.ui.util

import android.view.View
import android.widget.ListView

object ListViewUtils {

    @JvmStatic
    fun scrollToBottom(listView: ListView) {
        val count = listView.adapter.count
        if (count > 0) {
            setSelection(listView, count - 1, true)
        }
    }

    @JvmStatic
    fun setSelection(listView: ListView, pos: Int, jumpToBottom: Boolean) {
        if (jumpToBottom) {
            val lastChild = listView.getChildAt(listView.childCount - 1)
            if (lastChild != null) {
                listView.setSelectionFromTop(pos, -lastChild.height)
                return
            }
        }
        listView.setSelection(pos)
    }

    @JvmStatic
    fun getViewByPosition(pos: Int, listView: ListView): View? {
        val firstListItemPosition = listView.firstVisiblePosition
        val lastListItemPosition = firstListItemPosition + listView.childCount - 1

        return if (pos < firstListItemPosition || pos > lastListItemPosition) {
            listView.adapter.getView(pos, null, listView)
        } else {
            val childIndex = pos - firstListItemPosition
            listView.getChildAt(childIndex)
        }
    }
}
