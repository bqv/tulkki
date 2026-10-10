package uk.xa0.tulkki.ui.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A body's markup as Compose text: `*bold*`, `_italic_`, `~strike~` and the two code forms, with
 * their markers consumed.
 *
 * <p>**One parser, and it is not this file's.** [ImStyleParser] is the syntax's only reader - the
 * same object `StylingHelper.createSpanForStyle` answers from - so this is the Compose face of one
 * parse, never a second one. A rule that only the real parser has (an opener must start a word, a
 * run must close on its own line) holds here because it holds there.
 *
 * <p>**The marker glyphs are syntax, not text.** A completed run is drawn as its content: `*bold*`
 * is bold `bold`. That is what the tree drew whenever the body had an XHTML alternate (a sent
 * rich-text message rendered through `Html.fromHtml`, markers gone), and it is why the markers are
 * dropped from the drawn string rather than kept. [ImStyleParser] leaves an unpaired or unusable
 * marker alone, and so does this: it comes through as the literal character it is.
 *
 * <p>**The colour and background accents are deliberately absent.** `StylingHelper.makeKeywordOpaque`
 * dims the *marker glyphs* to 45% alpha, and this helper consumes those glyphs, so there is nothing
 * left for the accent to tint; the search highlight's `BackgroundColorSpan` was never a message
 * body's. The four emphases are the whole of `createSpanForStyle`'s set, and the whole of this.
 *
 * <p>**What it is not.** It reads a string and answers text; it knows nothing about a bubble, a
 * cover or which half is drawn. Its one caller hands it [uk.xa0.tulkki.ui.projection.UiBody.Visible]'s
 * own text, which is the side the projector already decided is the drawn one, so no concealment
 * decision can be reached from here - and none may be added.
 */
object StyledText {

    /**
     * The markup of [body], applied: the markers removed and the emphases left in their place.
     *
     * @return the drawn text. A body with no completed run comes back character for character, which
     *     is the common case and costs one parse.
     */
    @JvmStatic
    fun of(body: CharSequence): AnnotatedString {
        val styles = ImStyleParser.parse(body)
        if (styles.isEmpty()) {
            return AnnotatedString(body.toString())
        }

        val length = body.length
        val resolved = ArrayList<Run>(styles.size)
        // Every recognised run drops its two marker runs - the opening one and the closing one -
        // whether or not its content is drawable, exactly as the span arithmetic of
        // `StylingHelper.format` marked both markers for every parsed style. The two are the ranges
        // `makeKeywordOpaque` was given: [start, start + opening) and [end - keyword + 1, end + 1).
        val dropped = BooleanArray(length)
        for (style in styles) {
            val keywordLength = style.keyword.length
            val openEnd = (style.start + openingLength(body, style)).coerceIn(0, length)
            val closeStart = (style.end - keywordLength + 1).coerceIn(0, length)
            val closeEnd = (style.end + 1).coerceIn(0, length)
            for (at in style.start.coerceIn(0, length) until openEnd) {
                dropped[at] = true
            }
            for (at in closeStart until closeEnd) {
                dropped[at] = true
            }
            if (openEnd < closeStart) {
                resolved.add(Run(spanFor(style.keyword), openEnd, closeStart))
            }
        }

        val kept = StringBuilder(length)
        // Where each original index lands once the markers are gone: the boundary a range needs, so
        // a run whose opening or closing marker abuts another run's still maps to the right pair.
        val moved = IntArray(length + 1)
        for (at in 0 until length) {
            moved[at] = kept.length
            if (!dropped[at]) {
                kept.append(body[at])
            }
        }
        moved[length] = kept.length

        val builder = AnnotatedString.Builder(kept.toString())
        for (run in resolved) {
            builder.addStyle(run.style, moved[run.start], moved[run.end])
        }
        return builder.toAnnotatedString()
    }

    /**
     * How much of the opening marker is consumed, in the parser's own terms: one keyword, except a
     * fenced code block, whose opening fence line - the fence and the language tag on it - is the
     * marker and the content starts on the next line. This is `StylingHelper.format`'s
     * `keywordLengthStart`, kept rather than reinvented.
     */
    private fun openingLength(body: CharSequence, style: ImStyleParser.Style): Int {
        if (style.keyword != BLOCK_FENCE) {
            return style.keyword.length
        }
        var at = style.start
        while (at < body.length && body[at] != '\n') {
            at++
        }
        return at - style.start + 1
    }

    /** `StylingHelper.createSpanForStyle`'s four answers, as Compose styles. */
    private fun spanFor(keyword: String): SpanStyle =
        when (keyword) {
            "*" -> SpanStyle(fontWeight = FontWeight.Bold)
            "_" -> SpanStyle(fontStyle = FontStyle.Italic)
            "~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "`", BLOCK_FENCE -> SpanStyle(fontFamily = FontFamily.Monospace)
            else -> throw AssertionError("Unknown Style")
        }

    /** One emphasis and the range of the original text it covers, before the markers are removed. */
    private class Run(val style: SpanStyle, val start: Int, val end: Int)

    private const val BLOCK_FENCE = "```"
}
