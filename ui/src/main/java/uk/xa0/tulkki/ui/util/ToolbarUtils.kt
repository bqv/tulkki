package uk.xa0.tulkki.ui.util

import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.MaterialToolbar
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator

object ToolbarUtils {

    private val VIEW_TOP_COMPARATOR: Comparator<View> = Comparator { view1, view2 ->
        view1.getTop() - view2.getTop()
    }

    @JvmStatic
    fun resetActionBarOnClickListeners(view: MaterialToolbar) {
        val title = getTitleTextView(view)
        val subtitle = getSubtitleTextView(view)
        if (title != null) {
            title.setOnClickListener(null)
        }
        if (subtitle != null) {
            subtitle.setOnClickListener(null)
        }
    }

    @JvmStatic
    fun setActionBarOnClickListener(view: MaterialToolbar, onClickListener: View.OnClickListener) {
        val title = getTitleTextView(view)
        val subtitle = getSubtitleTextView(view)
        if (title != null) {
            title.setOnClickListener(onClickListener)
        }
        if (subtitle != null) {
            subtitle.setOnClickListener(onClickListener)
        }
    }

    @JvmStatic
    fun getTitleTextView(toolbar: Toolbar): TextView? {
        val textViews = getTextViewsWithText(toolbar, toolbar.getTitle())
        return if (textViews.isEmpty()) null else Collections.min(textViews, VIEW_TOP_COMPARATOR)
    }

    @JvmStatic
    fun getSubtitleTextView(toolbar: Toolbar): TextView? {
        val textViews = getTextViewsWithText(toolbar, toolbar.getSubtitle())
        return if (textViews.isEmpty()) null else Collections.max(textViews, VIEW_TOP_COMPARATOR)
    }

    private fun getTextViewsWithText(toolbar: Toolbar, text: CharSequence?): List<TextView> {
        val textViews = ArrayList<TextView>()
        for (i in 0 until toolbar.getChildCount()) {
            val child = toolbar.getChildAt(i)
            if (child is TextView) {
                if (TextUtils.equals(child.getText(), text)) {
                    textViews.add(child)
                }
            }
        }
        return textViews
    }

    @JvmStatic
    fun getLogoImageView(toolbar: Toolbar): ImageView? {
        return getImageView(toolbar, toolbar.getLogo())
    }

    private fun getImageView(toolbar: Toolbar, content: Drawable?): ImageView? {
        if (content == null) {
            return null
        }
        for (i in 0 until toolbar.getChildCount()) {
            val child = toolbar.getChildAt(i)
            if (child is ImageView) {
                val drawable = child.getDrawable()
                if (drawable != null &&
                    drawable.getConstantState() != null &&
                    drawable.getConstantState() == content.getConstantState()
                ) {
                    return child
                }
            }
        }
        return null
    }

    @JvmStatic
    fun getSecondaryActionMenuItemView(toolbar: Toolbar): View? {
        val actionMenuView = getActionMenuView(toolbar)
        if (actionMenuView != null) {
            // Only return the first child of the ActionMenuView if there is more than one child
            if (actionMenuView.getChildCount() > 1) {
                return actionMenuView.getChildAt(0)
            }
        }
        return null
    }

    @JvmStatic
    fun getActionMenuView(toolbar: Toolbar): ActionMenuView? {
        for (i in 0 until toolbar.getChildCount()) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView) {
                return child
            }
        }
        return null
    }

    @JvmStatic
    fun getNavigationIconButton(toolbar: Toolbar): ImageButton? {
        val navigationIcon = toolbar.getNavigationIcon()
        if (navigationIcon == null) {
            return null
        }
        for (i in 0 until toolbar.getChildCount()) {
            val child = toolbar.getChildAt(i)
            if (child is ImageButton) {
                if (child.getDrawable() === navigationIcon) {
                    return child
                }
            }
        }
        return null
    }
}
