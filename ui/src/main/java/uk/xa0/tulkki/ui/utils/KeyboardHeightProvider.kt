package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow

class KeyboardHeightProvider(
    context: Context,
    windowManager: WindowManager,
    parentView: View,
    listener: KeyboardHeightListener?,
) : PopupWindow(context) {

    private val popupView: LinearLayout = LinearLayout(context)

    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    init {
        popupView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)

            val rect = Rect()
            popupView.getWindowVisibleDisplayFrame(rect)

            var keyboardHeight = metrics.heightPixels - (rect.bottom - rect.top)
            val resourceID = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceID > 0) {
                keyboardHeight -= context.resources.getDimensionPixelSize(resourceID)
            }
            if (keyboardHeight < 100) {
                keyboardHeight = 0
            }
            val isLandscape = metrics.widthPixels > metrics.heightPixels
            val keyboardOpen = keyboardHeight > 0
            if (listener != null) {
                listener.onKeyboardHeightChanged(keyboardHeight, keyboardOpen, isLandscape)
            }
        }
        globalLayoutListener = layoutListener
        popupView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        contentView = popupView

        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        width = 0
        height = ViewGroup.LayoutParams.MATCH_PARENT
        setBackgroundDrawable(ColorDrawable(0))

        parentView.post { showAtLocation(parentView, Gravity.NO_GRAVITY, 0, 0) }
    }

    override fun dismiss() {
        val listener = globalLayoutListener
        if (listener != null) {
            popupView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            globalLayoutListener = null
        }
        super.dismiss()
    }

    interface KeyboardHeightListener {
        fun onKeyboardHeightChanged(keyboardHeight: Int, keyboardOpen: Boolean, isLandscape: Boolean)
    }
}
