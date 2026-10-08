package uk.xa0.tulkki.ui.utils

import java.util.ArrayList

object ImStyleParser {

    private val KEYWORDS = listOf('*', '_', '~', '`')
    private val NO_SUB_PARSING_KEYWORDS = listOf('`')
    private val BLOCK_KEYWORDS = listOf('`')
    private const val ALLOW_EMPTY = false
    private const val PARSE_HIGHER_ORDER_END = true

    @JvmStatic
    fun parse(text: CharSequence): List<Style> {
        return parse(text, 0, text.length - 1)
    }

    @JvmStatic
    fun parse(text: CharSequence, start: Int, end: Int): List<Style> {
        val styles = ArrayList<Style>()
        var i = start
        while (i <= end) {
            val c = text[i]
            if (KEYWORDS.contains(c)
                && precededByWhiteSpace(text, i, start)
                && !followedByWhitespace(text, i, end)) {
                if (BLOCK_KEYWORDS.contains(c) && isCharRepeatedTwoTimes(text, c, i + 1, end)) {
                    val to = seekEndBlock(text, c, i + 3, end)
                    if (to != -1 && (to != i + 5 || ALLOW_EMPTY)) {
                        val keyword = "" + c + c + c
                        styles.add(Style(keyword, i, to))
                        i = to
                        i++ // the for-loop's ++i after the continue
                        continue
                    }
                    i++ // the for-loop's ++i after the continue
                    continue
                }
                val to = seekEnd(text, c, i + 1, end)
                if (to != -1 && (to != i + 1 || ALLOW_EMPTY)) {
                    styles.add(Style(c, i, to))
                    if (!NO_SUB_PARSING_KEYWORDS.contains(c)) {
                        styles.addAll(parse(text, i + 1, to - 1))
                    }
                    i = to
                }
            }
            i++
        }
        return styles
    }

    private fun isCharRepeatedTwoTimes(text: CharSequence, c: Char, index: Int, end: Int): Boolean {
        return index + 1 <= end && text[index] == c && text[index + 1] == c
    }

    private fun precededByWhiteSpace(text: CharSequence, index: Int, start: Int): Boolean {
        return index == start || Character.isWhitespace(text[index - 1])
    }

    private fun followedByWhitespace(text: CharSequence, index: Int, end: Int): Boolean {
        return index >= end || Character.isWhitespace(text[index + 1])
    }

    private fun seekEnd(text: CharSequence, needle: Char, start: Int, end: Int): Int {
        for (i in start..end) {
            val c = text[i]
            if (c == needle && !Character.isWhitespace(text[i - 1])) {
                if (!PARSE_HIGHER_ORDER_END || followedByWhitespace(text, i, end)) {
                    return i
                } else {
                    val higherOrder =
                        seekHigherOrderEndWithoutNewBeginning(text, needle, i + 1, end)
                    if (higherOrder != -1) {
                        return higherOrder
                    }
                    return i
                }
            } else if (c == '\n') {
                return -1
            }
        }
        return -1
    }

    private fun seekHigherOrderEndWithoutNewBeginning(
        text: CharSequence,
        needle: Char,
        start: Int,
        end: Int,
    ): Int {
        for (i in start..end) {
            val c = text[i]
            if (c == needle
                && precededByWhiteSpace(text, i, start)
                && !followedByWhitespace(text, i, end)) {
                return -1 // new beginning
            } else if (c == needle
                && !Character.isWhitespace(text[i - 1])
                && followedByWhitespace(text, i, end)) {
                return i
            } else if (c == '\n') {
                return -1
            }
        }
        return -1
    }

    private fun seekEndBlock(text: CharSequence, needle: Char, start: Int, end: Int): Int {
        var foundNewline = false
        for (i in start..end) {
            val c = text[i]
            if (c == '\n') foundNewline = true
            if (foundNewline && c == needle && isCharRepeatedTwoTimes(text, needle, i + 1, end)) {
                return i + 2
            }
        }
        return -1
    }

    class Style(val keyword: String, val start: Int, val end: Int) {

        constructor(character: Char, start: Int, end: Int) : this(character.toString(), start, end)
    }
}
