package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat

import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EditMessage
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatEditText(context, attrs) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mTypingHandler = Handler()
    private var keyboardListener: KeyboardListener? = null
    private var mCommitContentListener: OnCommitContentListener? = null
    private var mimeTypes: Array<String>? = null
    private var isUserTyping = false

    private val mTypingTimeout =
        Runnable {
            val listener = keyboardListener
            if (isUserTyping && listener != null) {
                listener.onTypingStopped()
                isUserTyping = false
            }
        }
    private var lastInputWasTab = false

    init {
        addTextChangedListener(Watcher())
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val isCtrlPressed = e.isCtrlPressed
        if (keyCode == KeyEvent.KEYCODE_ENTER && !e.isShiftPressed) {
            lastInputWasTab = false
            val listener = keyboardListener
            if (listener != null && listener.onEnterPressed(isCtrlPressed)) {
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_TAB && !e.isAltPressed && !isCtrlPressed) {
            val listener = keyboardListener
            if (listener != null && listener.onTabPressed(this.lastInputWasTab)) {
                lastInputWasTab = true
                return true
            }
        } else {
            lastInputWasTab = false
        }
        return super.onKeyDown(keyCode, e)
    }

    override fun getAutofillType(): Int {
        return View.AUTOFILL_TYPE_NONE
    }

    public override fun onTextChanged(
        text: CharSequence,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        lastInputWasTab = false
        if (keyboardListener != null) {
            executor.execute { triggerKeyboardEvents(text.length) }
        }
    }

    private fun triggerKeyboardEvents(length: Int) {
        val listener = keyboardListener ?: return
        mTypingHandler.removeCallbacks(mTypingTimeout)
        mTypingHandler.postDelayed(mTypingTimeout, Config.TYPING_TIMEOUT * 1000L)
        if (!isUserTyping && length > 0) {
            isUserTyping = true
            listener.onTypingStarted()
        } else if (length == 0) {
            isUserTyping = false
            listener.onTextDeleted()
        }
        listener.onTextChanged()
    }

    fun setKeyboardListener(listener: KeyboardListener?) {
        this.keyboardListener = listener
        if (listener != null) {
            this.isUserTyping = false
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            } else {
                val editable = getEditableText()
                val filters: Array<InputFilter>? = editable.getFilters()
                val tempFilters: Array<InputFilter> =
                    if (filters == null) {
                        arrayOf(SPAN_FILTER)
                    } else {
                        Array(filters.size + 1) { index ->
                            if (index == 0) SPAN_FILTER else filters[index - 1]
                        }
                    }
                editable.setFilters(tempFilters)
                try {
                    return super.onTextContextMenuItem(id)
                } finally {
                    editable.setFilters(filters)
                }
            }
        } else {
            return super.onTextContextMenuItem(id)
        }
    }

    fun setRichContentListener(mimeTypes: Array<String>?, listener: OnCommitContentListener?) {
        this.mimeTypes = mimeTypes
        this.mCommitContentListener = listener
    }

    fun insertAsQuote(text: String) {
        val quoted = QuoteHelper.quote(text)
        val editable = getEditableText()
        var position = getSelectionEnd()
        if (position == -1) {
            position = editable.length
        }
        if (position > 0 && editable[position - 1] != '\n') {
            editable.insert(position, "\n")
            position++
        }
        editable.insert(position, quoted)
        position += quoted.length
        editable.insert(position, "\n")
        position++
        if (position < editable.length && editable[position] != '\n') {
            editable.insert(position, "\n")
        }
        setSelection(position)
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo)
        val types = mimeTypes
        val commit = mCommitContentListener
        if (types != null && commit != null && ic != null) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, types)
            return InputConnectionCompat.createWrapper(ic, editorInfo) {
                inputContentInfo, flags, opts ->
                commit.onCommitContent(inputContentInfo, flags, opts, types)
            }
        }
        return ic
    }

    fun refreshIme() {
        val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val usingEnterKey =
            p.getBoolean(
                "display_enter_key", getResources().getBoolean(R.bool.display_enter_key))
        val enterIsSend =
            p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send))

        if (usingEnterKey && enterIsSend) {
            setInputType(getInputType() and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv())
            setInputType(getInputType() and InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE.inv())
        } else if (usingEnterKey) {
            setInputType(getInputType() or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setInputType(getInputType() and InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE.inv())
        } else {
            setInputType(getInputType() or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setInputType(getInputType() or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)
        }
    }

    interface OnCommitContentListener {
        fun onCommitContent(
            inputContentInfo: InputContentInfoCompat,
            flags: Int,
            opts: Bundle?,
            mimeTypes: Array<String>?,
        ): Boolean
    }

    interface KeyboardListener {
        fun onEnterPressed(isCtrlPressed: Boolean): Boolean

        fun onTypingStarted()

        fun onTypingStopped()

        fun onTextDeleted()

        fun onTextChanged()

        fun onTabPressed(repeated: Boolean): Boolean
    }

    class Watcher : TextWatcher {

        private val spansToRemove = ArrayList<ImageSpan>()

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (s is SpannableStringBuilder && s.getTextWatcherDepth() > 1) {
                    return
                }
            }

            if (s !is Spannable) {
                return
            }
            val text: Spannable = s

            if (count > 0) { // something deleted
                val end = start + count
                val spans = text.getSpans(start, end, ImageSpan::class.java)
                synchronized(spansToRemove) {
                    for (span in spans) {
                        if (text.getSpanStart(span) < end && start < text.getSpanEnd(span)) {
                            spansToRemove.add(span)
                        }
                    }
                }
            }
        }

        override fun afterTextChanged(s: Editable) {
            val toRemove: List<ImageSpan>
            synchronized(spansToRemove) {
                toRemove = ArrayList(spansToRemove)
                spansToRemove.clear()
            }
            for (span in toRemove) {
                if (s.getSpanStart(span) > -1 && s.getSpanEnd(span) > -1) {
                    s.removeSpan(span)
                }
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    companion object {

        private val SPAN_FILTER = InputFilter { source, _, _, _, _, _ ->
            if (source is Spanned) source.toString() else source
        }
    }
}
