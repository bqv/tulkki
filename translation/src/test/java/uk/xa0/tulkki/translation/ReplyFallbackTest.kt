package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * Where a reply's quote is, given the span the sender declared.
 *
 * <p>Reading that span out of the message's payload tree is the host's job and is pinned by
 * {@code uk.xa0.tulkki.app.ReplySpanReadTest}; what is pinned here is the pure half: which body offsets the
 * declared span makes carried, and which of the three states a caller is looking at. The distinction
 * that matters is the last two: no fallback at all is a message that is all its own text, while a
 * declared fallback whose span cannot be read is a quote that cannot be placed - and the split must
 * still fail toward translating the whole body, because that costs a request while the other
 * direction would send half a message untranslated.
 */
class ReplyFallbackTest {

    private fun quote(ownText: String): String {
        return "> Guten Morgen! Wie geht es dir heute?\n\n" + ownText
    }

    @Test
    fun aRepliesDeclaredSpanSplitsTheQuoteFromTheOwnersOwnText() {
        val carried = "> Guten Morgen! Wie geht es dir heute?\n\n"
        val span = ReplySpan.of("0", carried.length.toString())
        val parts =
                ReplyFallback.of(quote("Hyvaa huomenta! Mita sinulle kuuluu?"), span)
        Assert.assertEquals(carried, parts.carried())
        Assert.assertEquals("Hyvaa huomenta! Mita sinulle kuuluu?", parts.translatable())
        Assert.assertTrue(ReplyFallback.declared(span))
    }

    @Test
    fun aMessageWithNoFallbackIsAllItsOwnText() {
        val parts = ReplyFallback.of("Guten Morgen", ReplySpan.NONE)
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals("Guten Morgen", parts.translatable())
        Assert.assertFalse(ReplyFallback.declared(ReplySpan.NONE))
    }

    @Test
    fun aFallbackWhoseSpanCannotBeReadLeavesTheWholeBodyAlone() {
        // It is still declared - that is the difference from "no quote here" - so a caller that must
        // not show a fallback it cannot place can cover instead. The split fails toward translating
        // the whole body, which costs a needless request and cannot send a half message.
        for (span in
                arrayOf(
                        arrayOf<String?>(null, null),
                        arrayOf<String?>("", "5"),
                        arrayOf<String?>("0", "abc"),
                        arrayOf<String?>("2", "8"),
                        arrayOf<String?>("9", "3"))) {
            val declared = ReplySpan.of(span[0], span[1])
            Assert.assertTrue(
                    "a declared fallback stays declared, start=" + span[0] + " end=" + span[1],
                    ReplyFallback.declared(declared))
            val parts = ReplyFallback.of(quote("Hyvaa huomenta"), declared)
            Assert.assertFalse(parts.hasCarried())
            Assert.assertEquals(quote("Hyvaa huomenta"), parts.translatable())
        }
    }

    @Test
    fun offsetsAreCodePointsLikeTheStripInMessage() {
        val emoji = "\uD83D\uDE00"
        val carried = "> " + emoji + " ok\n\n"
        val span = ReplySpan.of("0", "8")
        val parts = ReplyFallback.of(carried + "Hei", span)
        Assert.assertEquals(carried, parts.carried())
        Assert.assertEquals("Hei", parts.translatable())
    }

    @Test
    fun aNullBodyOrSpanNeverBreaksTheRead() {
        Assert.assertFalse(ReplyFallback.declared(null))
        Assert.assertEquals("Guten Morgen", ReplyFallback.of("Guten Morgen", null).translatable())
        Assert.assertEquals("", ReplyFallback.of(null, ReplySpan.of("0", "5")).translatable())
        Assert.assertEquals("", ReplyFallback.of(null, null).translatable())
    }

    @Test
    fun theSpanIsTheOnlyThingConsulted() {
        // A body that looks like a quote but carries no span is all its own text: nothing here scans
        // for '>' markers or blank lines, because a guess puts half a message on each side.
        val parts = ReplyFallback.of(quote("Hyvaa huomenta"), ReplySpan.NONE)
        Assert.assertFalse(parts.hasCarried())
        Assert.assertEquals(quote("Hyvaa huomenta"), parts.translatable())
    }
}
