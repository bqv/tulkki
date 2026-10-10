package uk.xa0.tulkki.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert
import org.junit.Test

/**
 * The parser -> styled-text mapping, as a JVM cell: what `ImStyleParser` reads, and the Compose text
 * `StyledText.of` answers from it.
 *
 * <p>**The drawn string is pinned, not only the spans**, because the marker glyphs are the whole of
 * the defect this helper exists for: a run that renders its own `*` is not styled however bold the
 * letters around it are. So every cell asserts the text as well as the ranges.
 *
 * <p>**Two cells exist to prove the syntax is the real parser's** and not a regex that happens to
 * pass the happy path: `a*b*` is not emphasis (the parser requires a marker to start a word), and an
 * unclosed `*bold` is literal. A second reader would fail both.
 *
 * <p>Offsets are pinned where the marker removal could drift: a run after another run, and a nested
 * pair, both have to land on their content once the markers between them are gone.
 *
 * <p>**The kept-marker mode is one parse with the other assembly, and the cells below pin both halves
 * of its look**: the string still carries the glyphs, each marker run takes `StylingHelper`'s own
 * accent - the foreground at 45% - and the content between them keeps the emphasis unchanged. The
 * literal cells are repeated here so the mode cannot style a marker the parser never recognised.
 */
class StyledTextTest {

    @Test
    fun boldIsDrawnAsItsContent() {
        val styled = StyledText.of("*bold*")

        Assert.assertEquals("bold", styled.text)
        Assert.assertEquals(listOf(Span(0, 4, SpanStyle(fontWeight = FontWeight.Bold))), spans(styled))
    }

    @Test
    fun emphasisIsDrawnAsItsContent() {
        val styled = StyledText.of("_emphasis_")

        Assert.assertEquals("emphasis", styled.text)
        Assert.assertEquals(
            listOf(Span(0, 8, SpanStyle(fontStyle = FontStyle.Italic))),
            spans(styled),
        )
    }

    @Test
    fun strikeIsDrawnAsItsContent() {
        val styled = StyledText.of("~gone~")

        Assert.assertEquals("gone", styled.text)
        Assert.assertEquals(
            listOf(Span(0, 4, SpanStyle(textDecoration = TextDecoration.LineThrough))),
            spans(styled),
        )
    }

    @Test
    fun inlineCodeIsDrawnAsItsContentInMonospace() {
        val styled = StyledText.of("`code`")

        Assert.assertEquals("code", styled.text)
        Assert.assertEquals(
            listOf(Span(0, 4, SpanStyle(fontFamily = FontFamily.Monospace))),
            spans(styled),
        )
    }

    @Test
    fun aFencedBlockDropsItsFenceLineAndKeepsTheLinesUnderIt() {
        val styled = StyledText.of("```\ncode\n```")

        // The fence, the language tag that would ride it and its newline are the opening marker; the
        // closing fence is the closing one. What is left is the block's own lines, newline and all -
        // the range `StylingHelper.format` set, moved onto the text that remains.
        Assert.assertEquals("code\n", styled.text)
        Assert.assertEquals(
            listOf(Span(0, 5, SpanStyle(fontFamily = FontFamily.Monospace))),
            spans(styled),
        )
    }

    @Test
    fun aNestedPairCarriesBothEmphasesOverTheSameContent() {
        val styled = StyledText.of("*_both_*")

        Assert.assertEquals("both", styled.text)
        Assert.assertEquals(
            setOf(
                Span(0, 4, SpanStyle(fontWeight = FontWeight.Bold)),
                Span(0, 4, SpanStyle(fontStyle = FontStyle.Italic)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun aRunAfterAnotherRunKeepsItsOwnOffsets() {
        val styled = StyledText.of("hi *you* and _me_")

        Assert.assertEquals("hi you and me", styled.text)
        Assert.assertEquals(
            setOf(
                Span(3, 6, SpanStyle(fontWeight = FontWeight.Bold)),
                Span(11, 13, SpanStyle(fontStyle = FontStyle.Italic)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun aMarkerInsideAWordIsNotAnOpening() {
        // The parser's own rule: an opener must be preceded by whitespace or start the text. A
        // scanner that matched `*...*` anywhere would style this, so this cell fails for a second
        // reader rather than for this one.
        val styled = StyledText.of("a*b*")

        Assert.assertEquals("a*b*", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    @Test
    fun anUnclosedMarkerStaysLiteral() {
        val styled = StyledText.of("*bold")

        Assert.assertEquals("*bold", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    @Test
    fun aBodyWithNoMarkupComesBackCharacterForCharacter() {
        val styled = StyledText.of("Hei! Oletko tulossa huomenna?")

        Assert.assertEquals("Hei! Oletko tulossa huomenna?", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    @Test
    fun anEmptyBodyIsEmpty() {
        Assert.assertEquals("", StyledText.of("").text)
    }

    @Test
    fun boldKeepsItsMarkersAndStillBoldsTheContent() {
        val styled = StyledText.of("*bold*", showMarkers = true, foreground = INK)

        Assert.assertEquals("*bold*", styled.text)
        Assert.assertEquals(
            setOf(
                Span(0, 1, accent(INK)),
                Span(1, 5, SpanStyle(fontWeight = FontWeight.Bold)),
                Span(5, 6, accent(INK)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun emphasisKeepsItsMarkersAndStillSlantsTheContent() {
        val styled = StyledText.of("_emphasis_", showMarkers = true, foreground = INK)

        Assert.assertEquals("_emphasis_", styled.text)
        Assert.assertEquals(
            setOf(
                Span(0, 1, accent(INK)),
                Span(1, 9, SpanStyle(fontStyle = FontStyle.Italic)),
                Span(9, 10, accent(INK)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun aFencedBlockKeepsItsWholeFenceLineAndStillMakesTheContentMonospace() {
        val styled = StyledText.of("```\ncode\n```", showMarkers = true, foreground = INK)

        // The opening marker is the whole fence line, newline and all, exactly what
        // `StylingHelper.format` handed `makeKeywordOpaque`; the closing fence is its own run.
        Assert.assertEquals("```\ncode\n```", styled.text)
        Assert.assertEquals(
            setOf(
                Span(0, 4, accent(INK)),
                Span(4, 9, SpanStyle(fontFamily = FontFamily.Monospace)),
                Span(9, 12, accent(INK)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun aNestedPairKeepsBothMarkerPairsAndBothEmphases() {
        val styled = StyledText.of("*_both_*", showMarkers = true, foreground = INK)

        Assert.assertEquals("*_both_*", styled.text)
        Assert.assertEquals(
            setOf(
                Span(0, 1, accent(INK)),
                Span(1, 2, accent(INK)),
                Span(1, 7, SpanStyle(fontWeight = FontWeight.Bold)),
                Span(2, 6, SpanStyle(fontStyle = FontStyle.Italic)),
                Span(6, 7, accent(INK)),
                Span(7, 8, accent(INK)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun aRunAfterAnotherRunKeepsItsOwnOffsetsWithTheMarkersInPlace() {
        val styled = StyledText.of("hi *you* and _me_", showMarkers = true, foreground = INK)

        Assert.assertEquals("hi *you* and _me_", styled.text)
        Assert.assertEquals(
            setOf(
                Span(3, 4, accent(INK)),
                Span(4, 7, SpanStyle(fontWeight = FontWeight.Bold)),
                Span(7, 8, accent(INK)),
                Span(13, 14, accent(INK)),
                Span(14, 16, SpanStyle(fontStyle = FontStyle.Italic)),
                Span(16, 17, accent(INK)),
            ),
            spans(styled).toSet(),
        )
    }

    @Test
    fun theMarkerGlyphsTakeTheTextColourAtFortyFivePercent() {
        val styled = StyledText.of("*bold*", showMarkers = true, foreground = INK)

        // The only coloured spans are the two marker runs, and the colour is the foreground's own
        // RGB at `transformColor`'s 45% - the `makeKeywordOpaque` accent - never full strength.
        val coloured = spans(styled).filter { it.style.color != Color.Unspecified }
        Assert.assertEquals(setOf(Span(0, 1, accent(INK)), Span(5, 6, accent(INK))), coloured.toSet())
        Assert.assertEquals(0.45f, accent(INK).color.alpha, 0.001f)
    }

    @Test
    fun aMarkerInsideAWordStaysLiteralWhenTheMarkersAreShown() {
        val styled = StyledText.of("a*b*", showMarkers = true, foreground = INK)

        Assert.assertEquals("a*b*", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    @Test
    fun anUnclosedMarkerStaysLiteralWhenTheMarkersAreShown() {
        val styled = StyledText.of("*bold", showMarkers = true, foreground = INK)

        Assert.assertEquals("*bold", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    @Test
    fun aBodyWithNoMarkupComesBackWithNoSpansWhenTheMarkersAreShown() {
        val styled = StyledText.of("Hei! Oletko tulossa huomenna?", showMarkers = true, foreground = INK)

        Assert.assertEquals("Hei! Oletko tulossa huomenna?", styled.text)
        Assert.assertEquals(emptyList<Span>(), spans(styled))
    }

    /** One emphasis over one range of the drawn text, in the shape a cell can compare. */
    private data class Span(val start: Int, val end: Int, val style: SpanStyle)

    private fun spans(styled: AnnotatedString): List<Span> =
        styled.spanStyles.map { Span(it.start, it.end, it.item) }

    /** The colour a body is drawn in, opaque, so the marker accent's 45% is a real change. */
    private val INK = Color(0xFF112233)

    /** `StylingHelper.transformColor`'s recipe, written out: the text colour at 45% alpha. */
    private fun accent(ink: Color): SpanStyle = SpanStyle(color = ink.copy(alpha = 0.45f))
}
