package uk.xa0.tulkki.ui.utils

import android.text.Spannable

import java.util.ArrayList

object CharSequenceUtils {

    private fun getStartIndex(input: CharSequence): Int {
        val length = input.length
        var index = 0
        while (Character.isWhitespace(input[index])) {
            ++index
            if (index >= length) {
                break
            }
        }
        return index
    }

    private fun getEndIndex(input: CharSequence): Int {
        var index = input.length - 1
        while (Character.isWhitespace(input[index])) {
            --index
            if (index < 0) {
                break
            }
        }
        return index
    }

    @JvmStatic
    fun trim(input: CharSequence): CharSequence {
        val begin = getStartIndex(input)
        val end = getEndIndex(input)
        return if (begin > end) {
            ""
        } else {
            StylingHelper.subSequence(input, begin, end + 1)
        }
    }

    @JvmStatic
    fun split(charSequence: Spannable, c: Char): List<CharSequence> {
        val out = ArrayList<CharSequence>()
        var begin = 0
        var i = 0
        val length = charSequence.length
        while (i < length) {
            if (charSequence[i] == c) {
                out.add(StylingHelper.subSequence(charSequence, begin, i))
                i++
                begin = i
            }
            i++
        }
        if (begin < charSequence.length) {
            out.add(StylingHelper.subSequence(charSequence, begin, charSequence.length))
        }
        return out
    }
}
