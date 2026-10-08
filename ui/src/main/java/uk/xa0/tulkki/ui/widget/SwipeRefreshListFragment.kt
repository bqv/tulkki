/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.ListFragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Subclass of [androidx.fragment.app.ListFragment] which provides automatic support for
 * providing the 'swipe-to-refresh' UX gesture by wrapping the the content view in a
 * [androidx.swiperefreshlayout.widget.SwipeRefreshLayout].
 */
open class SwipeRefreshListFragment : ListFragment() {

    private var enabled = false
    private var refreshing = false

    private var onRefreshListener: SwipeRefreshLayout.OnRefreshListener? = null

    private var mSwipeRefreshLayout: SwipeRefreshLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        // Create the list fragment's content view by calling the super method
        val listFragmentView = super.onCreateView(inflater, container, savedInstanceState)

        // Now create a SwipeRefreshLayout to wrap the fragment's content view
        val layout = ListFragmentSwipeRefreshLayout(
            (container ?: throw NullPointerException("container")).context
        )
        mSwipeRefreshLayout = layout
        layout.isEnabled = enabled
        layout.isRefreshing = refreshing

        val context = activity
        if (context != null) {
            // TODO are default colors fine here?
        }

        val listener = onRefreshListener
        if (listener != null) {
            layout.setOnRefreshListener(listener)
        }

        // Add the list fragment's content view to the SwipeRefreshLayout, making sure that it fills
        // the SwipeRefreshLayout
        layout.addView(
            listFragmentView,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Make sure that the SwipeRefreshLayout will fill the fragment
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Now return the SwipeRefreshLayout as this fragment's content view
        return layout
    }

    /**
     * Set the [SwipeRefreshLayout.OnRefreshListener] to listen for initiated refreshes.
     */
    fun setOnRefreshListener(listener: SwipeRefreshLayout.OnRefreshListener?) {
        onRefreshListener = listener
        enabled = true
        val layout = mSwipeRefreshLayout
        if (layout != null) {
            layout.isEnabled = true
            layout.setOnRefreshListener(listener)
        }
    }

    /**
     * Set whether the [SwipeRefreshLayout] should be displaying that it is refreshing or not.
     */
    fun setRefreshing(refreshing: Boolean) {
        this.refreshing = refreshing
        val layout = mSwipeRefreshLayout
        if (layout != null) {
            layout.isRefreshing = refreshing
        }
    }

    /**
     * Sub-class of [SwipeRefreshLayout] for use in this [ListFragment]. The reason that this is
     * needed is because [SwipeRefreshLayout] only supports a single child, which it expects to be
     * the one which triggers refreshes. In our case the layout's child is the content view returned
     * from [ListFragment.onCreateView] which is a [ViewGroup].
     *
     * To enable 'swipe-to-refresh' support via the [android.widget.ListView] we need to override the
     * default behavior and properly signal when a gesture is possible. This is done by overriding
     * [canChildScrollUp].
     */
    private inner class ListFragmentSwipeRefreshLayout(context: Context) :
        SwipeRefreshLayout(context) {

        /**
         * As mentioned above, we need to override this method to properly signal when a
         * 'swipe-to-refresh' is possible.
         *
         * @return true if the [android.widget.ListView] is visible and can scroll up.
         */
        public override fun canChildScrollUp(): Boolean {
            val listView = this@SwipeRefreshListFragment.listView
                ?: throw NullPointerException("listView")
            return if (listView.visibility == View.VISIBLE) {
                listView.canScrollVertically(-1)
            } else {
                false
            }
        }
    }
}
