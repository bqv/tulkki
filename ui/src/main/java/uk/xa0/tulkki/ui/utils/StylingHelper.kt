/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.xa0.tulkki.ui.utils

import android.graphics.Color
import android.graphics.Typeface
import android.preference.PreferenceManager
import android.text.Editable
import android.text.ParcelableSpan
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import uk.xa0.tulkki.ui.text.QuoteSpan
import java.util.ArrayList
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.adapter.MessageAdapter
import uk.xa0.tulkki.xmpp.utils.FtsUtils

object StylingHelper {

    const val XHTML_IGNORE = 1
    const val XHTML_REMOVE = 2
    const val XHTML_EMPHASIS = 3
    const val XHTML_CODE = 4
    const val NOLINKIFY = 0xf0

    private val SPAN_CLASSES: List<Class<out ParcelableSpan>> = listOf(
        StyleSpan::class.java,
        StrikethroughSpan::class.java,
        TypefaceSpan::class.java,
        RelativeSizeSpan::class.java,
        ForegroundColorSpan::class.java
    )

    @JvmStatic
    fun clear(editable: Editable) {
        val end = editable.length - 1
        for (clazz in SPAN_CLASSES) {
            for (span in editable.getSpans(0, end, clazz)) {
                editable.removeSpan(span)
            }
        }
    }

    @JvmStatic
    fun format(
        editable: Editable,
        start: Int,
        end: Int,
        @ColorInt textColor: Int,
        composing: Boolean
    ) {
        for (style in ImStyleParser.parse(editable, start, end)) {
            val keywordLength = style.keyword.length
            var keywordLengthStart = keywordLength
            if ("```" == style.keyword) {
                var i = style.start
                while (i < editable.length) {
                    if (editable[i] == '\n') break
                    i++
                }
                keywordLengthStart = i - style.start + 1
            }

            editable.setSpan(
                createSpanForStyle(style),
                style.start + keywordLengthStart,
                style.end - keywordLength + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or
                    (if ("*" == style.keyword || "_" == style.keyword) {
                        XHTML_EMPHASIS shl Spanned.SPAN_USER_SHIFT
                    } else {
                        0
                    }) or
                    (if ("```" == style.keyword && keywordLengthStart > 4) {
                        XHTML_CODE shl Spanned.SPAN_USER_SHIFT
                    } else {
                        0
                    }) or
                    (if ("`" == style.keyword || "```" == style.keyword) {
                        NOLINKIFY shl Spanned.SPAN_USER_SHIFT
                    } else {
                        0
                    })
            )
            makeKeywordOpaque(
                editable,
                style.start,
                style.start + keywordLengthStart,
                textColor,
                composing
            )
            makeKeywordOpaque(
                editable,
                style.end - keywordLength + 1,
                style.end + 1,
                textColor,
                composing
            )
        }
    }

    @JvmStatic
    fun format(editable: Editable, @ColorInt textColor: Int) {
        format(editable, textColor, false)
    }

    @JvmStatic
    fun format(editable: Editable, @ColorInt textColor: Int, composing: Boolean) {
        val end = 0
        format(editable, end, editable.length - 1, textColor, composing)
    }

    @JvmStatic
    fun highlight(view: TextView, editable: Editable, needles: List<String>) {
        for (needle in needles) {
            if (!FtsUtils.isKeyword(needle)) {
                highlight(view, editable, needle)
            }
        }
    }

    @JvmStatic
    fun filterHighlightedWords(terms: List<String>): List<String> {
        val words = ArrayList<String>()
        for (term in terms) {
            if (!FtsUtils.isKeyword(term)) {
                val builder = StringBuilder()
                var i = 0
                while (i < term.length) {
                    val codepoint = term.codePointAt(i)
                    if (Character.isLetterOrDigit(codepoint)) {
                        builder.append(Character.toChars(codepoint))
                    } else if (builder.length > 0) {
                        words.add(builder.toString())
                        builder.delete(0, builder.length)
                    }
                    i += Character.charCount(codepoint)
                }
                if (builder.length > 0) {
                    words.add(builder.toString())
                }
            }
        }
        return words
    }

    private fun highlight(view: TextView, editable: Editable, needle: String) {
        val length = needle.length
        val string = editable.toString()
        var start = indexOfIgnoreCase(string, needle, 0)
        while (start != -1) {
            val end = start + length
            editable.setSpan(
                BackgroundColorSpan(
                    MaterialColors.getColor(
                        view, com.google.android.material.R.attr.colorPrimaryFixedDim
                    )
                ),
                start,
                end,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                ForegroundColorSpan(
                    MaterialColors.getColor(
                        view, com.google.android.material.R.attr.colorOnPrimaryFixed
                    )
                ),
                start,
                end,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = indexOfIgnoreCase(string, needle, start + length)
        }
    }

    /** C1: `public` because `uk.xa0.tulkki.ui.utils.CharSequenceUtils` stayed behind and this class moved
     * to `uk.xa0.tulkki.ui.utils` - the access it had was an accident of the shared package. */
    @JvmStatic
    fun subSequence(charSequence: CharSequence, start: Int, end: Int): CharSequence {
        if (start == 0 && charSequence.length + 1 == end) {
            return charSequence
        }
        return if (charSequence is Spannable) {
            val spannable = charSequence
            val sub = spannable.subSequence(start, end) as Spannable
            for (clazz in SPAN_CLASSES) {
                val spannables = spannable.getSpans(start, end, clazz)
                for (parcelableSpan in spannables) {
                    val beginSpan = spannable.getSpanStart(parcelableSpan)
                    val endSpan = spannable.getSpanEnd(parcelableSpan)
                    if (beginSpan >= start && endSpan <= end) {
                        continue
                    }
                    sub.setSpan(
                        clone(parcelableSpan),
                        maxOf(beginSpan - start, 0),
                        minOf(sub.length - 1, endSpan),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            sub
        } else {
            charSequence.subSequence(start, end)
        }
    }

    private fun clone(span: ParcelableSpan): ParcelableSpan {
        return when (span) {
            is ForegroundColorSpan -> ForegroundColorSpan(span.getForegroundColor())
            is TypefaceSpan -> TypefaceSpan(span.getFamily())
            is StyleSpan -> StyleSpan(span.getStyle())
            is StrikethroughSpan -> StrikethroughSpan()
            else -> throw AssertionError("Unknown Span")
        }
    }

    private fun createSpanForStyle(style: ImStyleParser.Style): ParcelableSpan {
        return when (style.keyword) {
            "*" -> StyleSpan(Typeface.BOLD)
            "_" -> StyleSpan(Typeface.ITALIC)
            "~" -> StrikethroughSpan()
            "`", "```" -> TypefaceSpan("monospace")
            else -> throw AssertionError("Unknown Style")
        }
    }

    private fun makeKeywordOpaque(
        editable: Editable,
        start: Int,
        end: Int,
        @ColorInt fallbackTextColor: Int,
        composing: Boolean
    ) {
        val quoteSpans = editable.getSpans(start, end, QuoteSpan::class.java)
        @ColorInt val textColor =
            if (quoteSpans.isNotEmpty()) quoteSpans[0].color else fallbackTextColor
        @ColorInt val keywordColor = transformColor(textColor)
        if (composing) {
            if (end - start > 1) {
                editable.setSpan(
                    ForegroundColorSpan(keywordColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (XHTML_REMOVE shl Spanned.SPAN_USER_SHIFT)
                )
            } else {
                editable.setSpan(
                    RelativeSizeSpan(0f),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (XHTML_REMOVE shl Spanned.SPAN_USER_SHIFT)
                )
            }
        } else {
            editable.setSpan(
                ForegroundColorSpan(keywordColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    @ColorInt
    private fun transformColor(@ColorInt c: Int): Int {
        return Color.argb(
            Math.round(Color.alpha(c) * 0.45f), Color.red(c), Color.green(c), Color.blue(c)
        )
    }

    private fun indexOfIgnoreCase(haystack: String, needle: String, start: Int): Int {
        val endLimit = haystack.length - needle.length + 1
        if (start > endLimit) {
            return -1
        }
        if (needle.length == 0) {
            return start
        }
        for (i in start until endLimit) {
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) {
                return i
            }
        }
        return -1
    }

    class MessageEditorStyler(
        private val mEditText: EditText,
        private val mAdapter: MessageAdapter
    ) : TextWatcher {

        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

        override fun afterTextChanged(editable: Editable) {
            clear(editable)
            val p = PreferenceManager.getDefaultSharedPreferences(mEditText.context)
            if (!p.getBoolean(
                    "compose_rich_text",
                    mEditText.context.resources.getBoolean(R.bool.compose_rich_text)
                )
            ) {
                return
            }
            for (span in editable.getSpans(0, editable.length - 1, QuoteSpan::class.java)) {
                editable.removeSpan(span)
            }
            format(editable, mEditText.currentTextColor, true)
            mAdapter.handleTextQuotes(mEditText, editable, false)
        }
    }
}
