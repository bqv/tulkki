package uk.xa0.tulkki.ui.util

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

object SoftKeyboardUtils {

    @JvmStatic
    fun hideSoftKeyboard(activity: Activity) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm == null) {
            return
        }
        var view = activity.currentFocus
        if (view == null) {
            view = View(activity)
        }
        imm.hideSoftInputFromWindow(view.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    @JvmStatic
    fun hideSoftKeyboard(editText: EditText) {
        val imm =
            editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm == null) {
            return
        }
        imm.hideSoftInputFromWindow(editText.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    @JvmStatic
    fun showKeyboard(editText: EditText) {
        editText.requestFocus()
        val inputMethodManager =
            editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
