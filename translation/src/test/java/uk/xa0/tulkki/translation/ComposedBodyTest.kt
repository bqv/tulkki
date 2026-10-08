package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * Where a reply's quote stops and the owner's own text starts.
 *
 * <p>One boundary, read from the reply's declared fallback span and nothing else. The tests that
 * matter most are the ones about the failure direction: every span that cannot be trusted must come
 * back as "the whole body is translatable", because a wasted translation is recoverable and a reply
 * sent half-untranslated is not.
 */
class ComposedBodyTest {

    /** What upstream writes in front of a reply's own text, quote markers and blank line included. */
    private val QUOTE = "> Guten Morgen! Wie geht es dir heute?\n\n"

    @Test
    fun aBodyWithNoSpanIsAllTranslatable() {
        val parts = ComposedBody.of("Guten Morgen", null, null)
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals("", parts.carried())
        Assert.assertEquals("Guten Morgen", parts.translatable())
    }

    @Test
    fun aReplysDeclaredSpanLiftsTheQuoteOutVerbatim() {
        val body = QUOTE + "Hyvaa huomenta! Mita sinulle kuuluu?"
        val parts =
                ComposedBody.of(body, "0", QUOTE.length.toString())
        Assert.assertTrue(parts.hasCarried())
        Assert.assertEquals(QUOTE, parts.carried())
        Assert.assertEquals("Hyvaa huomenta! Mita sinulle kuuluu?", parts.translatable())
    }

    @Test
    fun recomposePutsTheCarriedQuoteBackInFrontOfTheTranslation() {
        val body = QUOTE + "Hyvaa huomenta"
        val parts =
                ComposedBody.of(body, "0", QUOTE.length.toString())
        Assert.assertEquals(
                "the wire body is the quote verbatim plus the translation of the owner's text",
                QUOTE + "Good morning",
                parts.recompose("Good morning"))
    }

    @Test
    fun offsetsAreCodePointsLikeTheStripInMessage() {
        // One emoji is one code point and two chars. A char-based reading of end=8 would stop a
        // newline early and leave it at the front of the reply's own text.
        val emoji = "\uD83D\uDE00"
        val body = "> " + emoji + " ok\n\nHei"
        val parts = ComposedBody.of(body, "0", "8")
        Assert.assertEquals("> " + emoji + " ok\n\n", parts.carried())
        Assert.assertEquals("Hei", parts.translatable())
    }

    @Test
    fun anOutOfRangeSpanFallsBackToTheWholeBody() {
        val body = QUOTE + "Hyvaa huomenta"
        val parts =
                ComposedBody.of(body, "0", (body.length + 5).toString())
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals(body, parts.translatable())
    }

    @Test
    fun aSpanThatDoesNotStartAtZeroFallsBackToTheWholeBody() {
        // A quote in the middle is not a prefix this class can carry; guessing which piece is the
        // quote is exactly what must not happen.
        val parts = ComposedBody.of("> quote\n\nHello", "2", "8")
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals("> quote\n\nHello", parts.translatable())
    }

    @Test
    fun aReversedOrEmptySpanFallsBackToTheWholeBody() {
        val empty = ComposedBody.of("> quote\n\nHello", "5", "5")
        Assert.assertFalse(empty.hasCarried())
        Assert.assertEquals("> quote\n\nHello", empty.translatable())
        val reversed = ComposedBody.of("> quote\n\nHello", "9", "3")
        Assert.assertFalse(reversed.hasCarried())
        Assert.assertEquals("> quote\n\nHello", reversed.translatable())
    }

    @Test
    fun unreadableAttributesFallBackToTheWholeBody() {
        val bad = arrayOf("", " ", "abc", "3.0", "-1", "99999999999999999999")
        for (value in bad) {
            val asEnd = ComposedBody.of("> quote\n\nHello", "0", value)
            Assert.assertFalse("end=" + value, asEnd.hasCarried())
            Assert.assertEquals("end=" + value, "> quote\n\nHello", asEnd.translatable())
            val asStart = ComposedBody.of("> quote\n\nHello", value, "5")
            Assert.assertFalse("start=" + value, asStart.hasCarried())
            Assert.assertEquals("start=" + value, "> quote\n\nHello", asStart.translatable())
        }
    }

    @Test
    fun aMissingAttributeFallsBackToTheWholeBody() {
        val noEnd = ComposedBody.of("> quote\n\nHello", "0", null)
        Assert.assertFalse(noEnd.hasCarried())
        Assert.assertEquals("> quote\n\nHello", noEnd.translatable())
        val noStart = ComposedBody.of("> quote\n\nHello", null, "5")
        Assert.assertFalse(noStart.hasCarried())
        Assert.assertEquals("> quote\n\nHello", noStart.translatable())
    }

    @Test
    fun whitespaceAroundAnAttributeIsAccepted() {
        val parts = ComposedBody.of("> quote\n\nHello", " 0 ", " 7 ")
        Assert.assertEquals("> quote", parts.carried())
        Assert.assertEquals("\n\nHello", parts.translatable())
    }

    @Test
    fun aSpanOverTheWholeBodyCarriesEverythingAndTranslatesNothing() {
        // A declaration, not a mistake: the message is all fallback, so there is no reply text.
        val body = "> Guten Morgen\n\n"
        val parts = ComposedBody.of(body, "0", body.length.toString())
        Assert.assertEquals(body, parts.carried())
        Assert.assertEquals("", parts.translatable())
    }

    @Test
    fun aNullBodyBecomesEmptyTextAndNeverBreaks() {
        val parts = ComposedBody.of(null, "0", "3")
        Assert.assertEquals("", parts.carried())
        Assert.assertEquals("", parts.translatable())
        Assert.assertEquals("", ComposedBody.whole(null).translatable())
    }

    @Test
    fun wholeIsTheSameAnswerAsNoSpan() {
        val parts = ComposedBody.whole("Guten Morgen")
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals("Guten Morgen", parts.translatable())
        Assert.assertEquals("Good morning", parts.recompose("Good morning"))
        Assert.assertEquals("a null remainder is empty text, not a crash", "", parts.recompose(null))
    }
}
