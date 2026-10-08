package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test

/**
 * `ui-8`'s cells over `Anchor`, `Design: the Compose UI` §4.3's own list - "anchor reducer across prepend,
 * append, in-place translation change, list emptied" - and the doc's sentinel: "**`0` means 'was at the
 * bottom'**".
 *
 * <p>What the screen does with the index is the screen's; what these hold is what changed and what did not,
 * which is the whole of the reader's place.
 */
class AnchorTest {

    @Test
    fun anOlderHistoryPrependKeepsTheReadersOwnRow() {
        val ids = listOf("m3", "m4", "m5")
        val before = Anchor.before(ids, firstVisible = 0, lastVisible = 1)
        Assert.assertEquals(
            "two rows were added above, so the reader's own row is two places further down",
            2,
            Anchor.after(before, listOf("m1", "m2") + ids),
        )
    }

    @Test
    fun anAppendAndAnInPlaceChangeAreNotMoves() {
        val ids = listOf("m3", "m4", "m5")
        // The window is the middle row alone: the list's end is NOT on screen, or the doc's own sentinel
        // would answer instead and the cell would be testing the wrong branch.
        val before = Anchor.before(ids, firstVisible = 1, lastVisible = 1)
        Assert.assertFalse("the end is not on screen", before.atBottom)
        Assert.assertEquals(
            "a row added below changes nothing above it",
            1,
            Anchor.after(before, ids + "m6"),
        )
        Assert.assertEquals(
            "and a row that changed in place - a translation landing - keeps the same ids and the same place",
            1,
            Anchor.after(before, ids),
        )
    }

    @Test
    fun aReaderAtTheBottomStaysAtTheBottom() {
        val ids = listOf("m3", "m4", "m5")
        val before = Anchor.before(ids, firstVisible = 1, lastVisible = 2)
        Assert.assertTrue("the last row being on screen is the doc's own test", before.atBottom)
        Assert.assertEquals(
            "so the sentinel, whatever was added below",
            0,
            Anchor.after(before, ids + "m6"),
        )
    }

    @Test
    fun anEmptiedListIsTheBottom() {
        val before = Anchor.before(listOf("m3", "m4"), firstVisible = 0, lastVisible = 1)
        Assert.assertTrue("a list whose end is on screen", before.atBottom)
        Assert.assertEquals(0, Anchor.after(before, emptyList()))
        Assert.assertEquals(
            "and a window read from a list that is already empty reads as the bottom too",
            0,
            Anchor.after(Anchor.before(emptyList(), firstVisible = 0, lastVisible = 0), emptyList()),
        )
    }

    @Test
    fun aRowThatDisappearedFallsBackToWhereItWas() {
        // The middle row is the reader's, and the end is not on screen.
        val before = Anchor.before(listOf("m3", "m4", "m5"), firstVisible = 1, lastVisible = 1)
        Assert.assertEquals(
            "the reader's own row is gone, so the index it was at is the honest answer",
            1,
            Anchor.after(before, listOf("m3", "m5")),
        )
        Assert.assertEquals(
            "and a list that shrank past it clamps rather than throwing",
            0,
            Anchor.after(before, listOf("m5")),
        )
    }
}
