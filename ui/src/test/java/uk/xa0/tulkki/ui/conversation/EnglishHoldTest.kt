package uk.xa0.tulkki.ui.conversation

import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * The English memo's own cells: what a buyer puts in is what the seam answers, the cap evicts in access
 * order, and one row's answer can be forgotten without touching another's.
 *
 * <p>What no cell here can hold is the *writing*: the two buyers are the receive path and the owner's tap,
 * and neither is written yet. What is pinned is the contract they will write through - and the property that
 * makes it worth having: the answer is a purchase, so the words come back exactly as they went in, and the
 * pixels stay the smear factory's (`blurred` is `null`, so the projection draws a placeholder and never a
 * word of the English uninvited).
 */
class EnglishHoldTest {

    @After
    fun emptyTheMemo() {
        EnglishHold.clear()
    }

    @Test
    fun aPurchaseComesBackAsTheWordsAndNeverAsReadablePixels() {
        Assert.assertNull("nothing bought in a fresh process", EnglishHold.of("m1"))
        EnglishHold.put("m1", "the purchased English")
        val inHand = EnglishHold.of("m1")
        Assert.assertEquals("the words are what a revealed row draws", "the purchased English", inHand!!.text)
        Assert.assertNull("and the blur is the smear factory's, which does not exist yet", inHand.blurred)
        Assert.assertNull("another row bought nothing", EnglishHold.of("m2"))
    }

    @Test
    fun theMemoIsBoundedAndEvictsWhatWasLeastRecentlyRead() {
        for (index in 1..EnglishHoldCapacity) {
            EnglishHold.put("m$index", "english $index")
        }
        Assert.assertNotNull("the first $EnglishHoldCapacity are held", EnglishHold.of("m1"))
        EnglishHold.put("m${EnglishHoldCapacity + 1}", "one too many")
        // Reading is what orders the eviction, so the row just read is the one that survives and its
        // neighbour - the least recently *read* - is the one that goes.
        Assert.assertNull("a read counts as a use", EnglishHold.of("m2"))
        Assert.assertEquals(
            "so the row that was read survives the insert",
            "english 1",
            EnglishHold.of("m1")!!.text,
        )
        Assert.assertEquals(
            "and the newest is in hand",
            "one too many",
            EnglishHold.of("m${EnglishHoldCapacity + 1}")!!.text,
        )
    }

    @Test
    fun oneRowsAnswerCanBeForgotten() {
        EnglishHold.put("m1", "english for m1")
        EnglishHold.put("m2", "english for m2")
        EnglishHold.forget("m1")
        Assert.assertNull("an edited or retracted row's answer is stale", EnglishHold.of("m1"))
        Assert.assertEquals("and its neighbour is untouched", "english for m2", EnglishHold.of("m2")!!.text)
    }

    private companion object {
        /** The deleted adapter's own capacity, which the memo keeps: enough for a conversation. */
        const val EnglishHoldCapacity = 128
    }
}
