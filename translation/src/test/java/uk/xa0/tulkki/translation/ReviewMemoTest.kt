package uk.xa0.tulkki.translation

import java.util.Collections
import org.junit.Assert
import org.junit.Test

/**
 * The in-process memo in front of the stored reviews.
 *
 * <p>It exists so a scrolling list does not repeat a lookup for a review it has already read. The
 * one behaviour worth pinning beyond hit and miss is that a <em>miss</em> is not remembered: the
 * notes are written just before a message is sent, so a bubble drawn in between must be able to ask
 * again rather than being told "nothing" for the rest of the process's life.
 */
class ReviewMemoTest {

    private fun note(text: String): Review {
        return Review.of(Collections.singletonList(Review.Note.of(text)))
    }

    @Test
    fun anAnswerThatWasReadIsRemembered() {
        val memo = ReviewMemo()

        memo.put("key", note("the inessive is -ssa"))

        Assert.assertEquals(
                Collections.singletonList("the inessive is -ssa"), memo.get("key")!!.notes())
    }

    @Test
    fun theStretchesComeBackWithTheRemark() {
        // The memo is a cache in front of the database, so what it hands back has to be the whole
        // note - a remark with its underline, not a remark alone. Losing the slices here would make
        // the underline flicker away as soon as the bubble was re-bound from memory.
        val memo = ReviewMemo()
        val reviewed =
                Review.of(
                        Collections.singletonList(
                                Review.Note.of(
                                        "the inessive is -ssa",
                                        Collections.singletonList("talossa"))))

        memo.put("key", reviewed)

        Assert.assertEquals(
                Collections.singletonList("talossa"), memo.get("key")!!.items().get(0).flagged())
    }

    @Test
    fun anAnswerThatWasNeverReadIsAMiss() {
        Assert.assertNull(ReviewMemo().get("key"))
    }

    @Test
    fun aNullKeyIsNeverAKey() {
        val memo = ReviewMemo()

        Assert.assertNull(memo.get(null))
        memo.put(null, note("x"))

        Assert.assertEquals(0, memo.size())
    }

    @Test
    fun theMemoIsBounded() {
        val memo = ReviewMemo(2)

        memo.put("one", note("1"))
        memo.put("two", note("2"))
        memo.put("three", note("3"))

        Assert.assertEquals(2, memo.size())
        Assert.assertNull("the oldest answer is the one to go", memo.get("one"))
        Assert.assertNotNull(memo.get("two"))
        Assert.assertNotNull(memo.get("three"))
    }

    @Test
    fun readingAnAnswerKeepsItYoung() {
        val memo = ReviewMemo(2)

        memo.put("one", note("1"))
        memo.put("two", note("2"))
        memo.get("one")
        memo.put("three", note("3"))

        Assert.assertNotNull("'one' was read most recently, so 'two' goes first", memo.get("one"))
        Assert.assertNull(memo.get("two"))
    }

    @Test
    fun aRepeatedKeyReplacesTheAnswer() {
        val memo = ReviewMemo()

        memo.put("key", note("first"))
        memo.put("key", note("second"))

        Assert.assertEquals(1, memo.size())
        Assert.assertEquals(Collections.singletonList("second"), memo.get("key")!!.notes())
    }

    @Test
    fun aMemoOfOneStillHoldsOne() {
        val memo = ReviewMemo(0)

        memo.put("key", note("x"))

        Assert.assertEquals(1, memo.size())
        Assert.assertNotNull(memo.get("key"))
    }
}
