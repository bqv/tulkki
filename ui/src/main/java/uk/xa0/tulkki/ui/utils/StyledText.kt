package uk.xa0.tulkki.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A body's markup as Compose text: `*bold*`, `_italic_`, `~strike~` and the two code forms, drawn
 * through one of two modes.
 *
 * <p>**One parser, and it is not this file's.** [ImStyleParser] is the syntax's only reader - the
 * same object `StylingHelper.createSpanForStyle` answers from - so this is the Compose face of one
 * parse, never a second one. A rule that only the real parser has (an opener must start a word, a
 * run must close on its own line) holds here because it holds there.
 *
 * <p>**The two modes are one parse with two assemblies.** The single-argument [of] is the shipped
 * drawing: a completed run is drawn as its content - `*bold*` is bold `bold` - and the marker glyphs
 * are dropped rather than drawn, which is what the tree drew whenever the body had an XHTML alternate
 * implemented through `Html.fromHtml`. [of] with `showMarkers` on is the owner's "Show formatting
 * marks": the glyphs stay in the string and take a [SpanStyle] of their own, so `*bold*` is drawn
 * whole - the two `*` dimmed, the `bold` between them bold. Both read the same [ImStyleParser.parse]
 * and take the marker ranges the same way, so a rule cannot hold in one mode and not the other.
 * [ImStyleParser] leaves an unpaired or unusable marker alone, and so does this: it comes through as
 * the literal character it is, in either mode.
 *
 * <p>**The marker colour is `StylingHelper`'s, not a new one.** `makeKeywordOpaque` gives the marker
 * glyphs a `ForegroundColorSpan` of the text colour run through `transformColor` - the same RGB at 45%
 * alpha - so the markers stay readable but recede while the content between them takes the emphasis.
 * The kept mode's marker style is exactly that recipe expressed as a Compose [SpanStyle]:
 * [MARKER_OF_FOREGROUND] times the foreground's own alpha, applied with [Color.copy], which is
 * `Color.argb`'s arithmetic to the float. The colour is the one the body is drawn in (the bubble's
 * foreground), because that is what `makeKeywordOpaque` was handed.
 *
 * <p>**What it is not.** It reads a string and answers text; it knows nothing about a bubble, a
 * cover or which half is drawn. Its one caller hands it [uk.xa0.tulkki.ui.projection.UiBody.Visible]'s
 * own text, which is the side the projector already decided is the drawn one, so no concealment
 * decision can be reached from here - and none may be added. The marker mode is a drawing switch:
 * drawing the markers of a *visible* body changes nothing about what is concealed, and the switch is
 * never consulted on a concealed one.
 */
object StyledText {

    /**
     * The shipped drawing: the markup of [body] applied, the markers removed and the emphases left in
     * their place.
     *
     * @return the drawn text. A body with no completed run comes back character for character, which
     *     is the common case and costs one parse.
     */
    @JvmStatic
    fun of(body: CharSequence): AnnotatedString =
        of(body, showMarkers = false, foreground = Color.Unspecified)

    /**
     * The markup of [body], with its markers either consumed or kept.
     *
     * @param showMarkers off is [of]'s own drawing, the markers gone. On keeps every recognised
     *     marker in the string and draws it in [foreground] at [MARKER_OF_FOREGROUND] alpha - the
     *     `makeKeywordOpaque` accent - while the content between the markers keeps its emphasis.
     * @param foreground the colour the body is drawn in, read only when [showMarkers] is on. It is the
     *     bubble's own text colour, which is what `StylingHelper.format` handed `makeKeywordOpaque`.
     */
    @JvmStatic
    fun of(body: CharSequence, showMarkers: Boolean, foreground: Color): AnnotatedString {
        val styles = ImStyleParser.parse(body)
        if (styles.isEmpty()) {
            return AnnotatedString(body.toString())
        }

        val length = body.length
        // The accent `makeKeywordOpaque` puts on a marker glyph: the text colour's own alpha at
        // `transformColor`'s 45%. Null in the shipped mode, where no glyph is left to tint.
        val markerStyle =
            if (showMarkers) {
                SpanStyle(color = foreground.copy(alpha = foreground.alpha * MARKER_OF_FOREGROUND))
            } else {
                null
            }
        val resolved = ArrayList<Run>(styles.size)
        // Every recognised run has two marker ranges - the opening one and the closing one -
        // whether or not its content is drawable, exactly as the span arithmetic of
        // `StylingHelper.format` marked both. They are the ranges `makeKeywordOpaque` was given:
        // [start, start + opening) and [end - keyword + 1, end + 1). The shipped mode drops them; the
        // kept mode tints them.
        val dropped = BooleanArray(length)
        for (style in styles) {
            val keywordLength = style.keyword.length
            val openStart = style.start.coerceIn(0, length)
            val openEnd = (style.start + openingLength(body, style)).coerceIn(0, length)
            val closeStart = (style.end - keywordLength + 1).coerceIn(0, length)
            val closeEnd = (style.end + 1).coerceIn(0, length)
            if (markerStyle == null) {
                for (at in openStart until openEnd) {
                    dropped[at] = true
                }
                for (at in closeStart until closeEnd) {
                    dropped[at] = true
                }
            } else {
                resolved.add(Run(markerStyle, openStart, openEnd))
                resolved.add(Run(markerStyle, closeStart, closeEnd))
            }
            if (openEnd < closeStart) {
                resolved.add(Run(spanFor(style.keyword), openEnd, closeStart))
            }
        }

        val kept = StringBuilder(length)
        // Where each original index lands once the markers are gone: the boundary a range needs, so
        // a run whose opening or closing marker abuts another run's still maps to the right pair. The
        // kept mode drops nothing, so this is the identity and each range lands where it was.
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
            if (run.start < run.end) {
                builder.addStyle(run.style, moved[run.start], moved[run.end])
            }
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

    /** One emphasis or marker accent over the range of the original text it covers. */
    private class Run(val style: SpanStyle, val start: Int, val end: Int)

    /** `StylingHelper.transformColor`'s accent: a marker glyph at the text colour's 45% alpha. */
    private const val MARKER_OF_FOREGROUND = 0.45f

    private const val BLOCK_FENCE = "```"
}
