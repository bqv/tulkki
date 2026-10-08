package uk.xa0.tulkki.data.utils

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import uk.xa0.tulkki.xmpp.Config

/**
 * The quote grammar of the wire format, and the predicates that read it off a body.
 *
 * This is pair 8b's move: the class lived at `uk.xa0.tulkki.ui.util.QuoteHelper` and `:data` named it from
 * `Message.updateReplyTo` and `MessageUtils`, which is the forbidden `:data` -> `:ui` direction (D9). Nothing
 * in it draws: it reads a `CharSequence` and answers questions about `'>'` and `'»'`, and the only Android
 * type it sees is [Spanned] and a [RelativeSizeSpan] span *type*, both of which `:data` already uses.
 *
 * The quote-boundary predicates that used to live in `uk.xa0.tulkki.ui.utils.UIHelper` moved with it, because
 * they are what the grammar is written against (`isPositionPrecededByLineStart`,
 * `isPositionFollowedByQuoteableCharacter` and the three private tests that build the second one). `UIHelper`
 * keeps the same public methods with the same signatures and now delegates, so no `:ui` caller changed.
 *
 * `uk.xa0.tulkki.ui.util.QuoteHelper` — the empty subclass pair 8b left as a seam, because
 * `uk.xa0.tulkki.xmpp.services.XmppConnectionService` was the one caller that could not be retyped in that
 * commit — was deleted by pair 11, which reads the class from here in the island's own body and retargeted the
 * four `:ui` callers. This is now the only spelling of the grammar.
 */
object QuoteHelper {

    const val QUOTE_CHAR = '>'
    const val QUOTE_END_CHAR = '<' // used for one check, not for actual quoting
    const val QUOTE_ALT_CHAR = '»'
    const val QUOTE_ALT_END_CHAR = '«'

    @JvmStatic
    fun isRelativeSizeSpanned(body: Spanned, pos: Int): Boolean {
        for (span in body.getSpans(pos, pos, RelativeSizeSpan::class.java)) {
            if (body.getSpanEnd(span) != pos) return true
        }

        return false
    }

    @JvmStatic
    fun isPositionQuoteCharacter(body: CharSequence, pos: Int): Boolean {
        // second part of logical check actually goes against the logic indicated in the method name, since it
        // also checks for context but it's very useful
        return body[pos] == QUOTE_CHAR || isPositionAltQuoteStart(body, pos)
    }

    @JvmStatic
    fun isPositionQuoteEndCharacter(body: CharSequence, pos: Int): Boolean = body[pos] == QUOTE_END_CHAR

    @JvmStatic
    fun isPositionAltQuoteCharacter(body: CharSequence, pos: Int): Boolean = body[pos] == QUOTE_ALT_CHAR

    @JvmStatic
    fun isPositionAltQuoteEndCharacter(body: CharSequence, pos: Int): Boolean =
        body[pos] == QUOTE_ALT_END_CHAR

    @JvmStatic
    fun isPositionAltQuoteStart(body: CharSequence, pos: Int): Boolean =
        isPositionAltQuoteCharacter(body, pos) &&
            isPositionPrecededByPreQuote(body, pos) &&
            !isPositionFollowedByAltQuoteEnd(body, pos)

    @JvmStatic
    fun isPositionFollowedByQuoteChar(body: CharSequence, pos: Int): Boolean =
        body.length > pos + 1 && isPositionQuoteCharacter(body, pos + 1)

    /** 'Prequote' means anything we require or can accept in front of a QuoteChar. */
    @JvmStatic
    fun isPositionPrecededByPreQuote(body: CharSequence, pos: Int): Boolean {
        if (body is Spanned) {
            if (isRelativeSizeSpanned(body, pos - 1)) return true
        }
        return isPositionPrecededByLineStart(body, pos)
    }

    @JvmStatic
    fun isPositionQuoteStart(body: CharSequence, pos: Int): Boolean =
        isPositionQuoteCharacter(body, pos) &&
            isPositionPrecededByPreQuote(body, pos) &&
            (isPositionFollowedByQuoteableCharacter(body, pos) || isPositionFollowedByQuoteChar(body, pos))

    @JvmStatic
    fun bodyContainsQuoteStart(body: CharSequence): Boolean {
        for (i in 0 until body.length) {
            if (body is Spanned) {
                if (isRelativeSizeSpanned(body, i)) continue
            }
            if (isPositionQuoteStart(body, i)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun isPositionFollowedByAltQuoteEnd(body: CharSequence, pos: Int): Boolean {
        if (body.length <= pos + 1 || Character.isWhitespace(body[pos + 1])) {
            return false
        }
        var previousWasWhitespace = false
        for (i in pos + 1 until body.length) {
            val c = body[i]
            if (c == '\n' || isPositionAltQuoteCharacter(body, i)) {
                return false
            } else if (isPositionAltQuoteEndCharacter(body, i) && !previousWasWhitespace) {
                return true
            } else {
                previousWasWhitespace = Character.isWhitespace(c)
            }
        }
        return false
    }

    @JvmStatic
    fun isNestedTooDeeply(line: CharSequence): Boolean = isNestedTooDeeply(line, Config.QUOTING_MAX_DEPTH)

    @JvmStatic
    fun isNestedTooDeeply(line: CharSequence, maxDepth: Int): Boolean {
        if (isPositionQuoteStart(line, 0)) {
            var nestingDepth = 1
            for (i in 1 until line.length) {
                if (isPositionQuoteCharacter(line, i)) {
                    nestingDepth++
                } else if (line[i] != ' ') {
                    break
                }
            }
            return nestingDepth >= maxDepth
        }
        return false
    }

    @JvmStatic
    fun replaceAltQuoteCharsInText(text: String): String {
        var out = text
        for (i in out.indices) {
            if (isPositionAltQuoteStart(out, i)) {
                out = out.substring(0, i) + QUOTE_CHAR + out.substring(i + 1)
            }
        }
        return out
    }

    @JvmStatic
    fun quote(text: String): String {
        var out = replaceAltQuoteCharsInText(text)
        // first replace all '>' at the beginning of the line with nice and tidy '>>' for nested quoting
        out = out.replace(Regex("(^|\n)($QUOTE_CHAR)"), "\$1\$2\$2")
        // then find all other lines and have them start with a '> '
        out = out.replace(Regex("(^|\n)(?!$QUOTE_CHAR)(.*)"), "\$1> \$2")
        return out
    }

    // -- the quote-boundary predicates, moved down from UIHelper (pair 8b) ------------------------

    /** True when there is no line break before [pos], only spaces. */
    @JvmStatic
    fun isPositionPrecededByBodyStart(body: CharSequence, pos: Int): Boolean {
        // true if not a single linebreak before current position
        for (i in pos - 1 downTo 0) {
            if (body[i] != ' ') {
                return false
            }
        }
        return true
    }

    /** True when [pos] begins a line: the body's own start, or one byte past a newline. */
    @JvmStatic
    fun isPositionPrecededByLineStart(body: CharSequence, pos: Int): Boolean {
        if (isPositionPrecededByBodyStart(body, pos)) {
            return true
        }
        return body[pos - 1] == '\n'
    }

    /** True when what follows [pos] could be quoted prose rather than a number or an emoticon. */
    @JvmStatic
    fun isPositionFollowedByQuoteableCharacter(body: CharSequence, pos: Int): Boolean =
        !isPositionFollowedByNumber(body, pos) &&
            !isPositionFollowedByEmoticon(body, pos) &&
            !isPositionFollowedByEquals(body, pos)

    private fun isPositionFollowedByNumber(body: CharSequence, pos: Int): Boolean {
        var previousWasNumber = false
        for (i in pos + 1 until body.length) {
            val c = body[i]
            if (Character.isDigit(body[i])) {
                previousWasNumber = true
            } else if (previousWasNumber && (c == '.' || c == ',')) {
                previousWasNumber = false
            } else {
                return (Character.isWhitespace(c) || c == '%' || c == '+') && previousWasNumber
            }
        }
        return previousWasNumber
    }

    private fun isPositionFollowedByEquals(body: CharSequence, pos: Int): Boolean =
        body.length > pos + 1 && body[pos + 1] == '='

    private fun isPositionFollowedByEmoticon(body: CharSequence, pos: Int): Boolean {
        if (body.length <= pos + 1) {
            return false
        } else {
            val first = body[pos + 1]
            return first == ';' ||
                first == ':' ||
                first == '.' || // do not quote >.< (but >>.<)
                closingBeforeWhitespace(body, pos + 1)
        }
    }

    private fun closingBeforeWhitespace(body: CharSequence, pos: Int): Boolean {
        for (i in pos until body.length) {
            val c = body[i]
            if (Character.isWhitespace(c)) {
                return false
            } else if (isPositionQuoteCharacter(body, pos) || isPositionQuoteEndCharacter(body, pos)) {
                return body.length == i + 1 || Character.isWhitespace(body[i + 1])
            }
        }
        return false
    }
}
