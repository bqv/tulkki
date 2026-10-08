package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test

/**
 * Where the anchored card goes, pinned as arithmetic: above its anchor when there is room over it,
 * below when there is not, and inside the screen on both axes whatever the numbers are.
 *
 * The numbers are the emulator's own: a 1080 x 1920 screen at a density of 2, which is an 8 dp margin
 * of 16 px and a 4 dp gap of 8 px. Nothing here touches a device, which is the point of the
 * extraction - the placement rule used to be reachable only through a shown `PopupWindow`.
 */
class PopupPlacementTest {

    private fun place(anchorRect: IntArray, width: Int, height: Int): IntArray =
        PopupPlacement.place(anchorRect, width, height, SCREEN_WIDTH, SCREEN_HEIGHT, DENSITY, GAP)

    // -- over the anchor, or under it ---------------------------------------------------------------

    @Test
    fun aCardThatFitsGoesOverItsAnchor() {
        val placed = place(WORD, WIDTH, HEIGHT)

        Assert.assertEquals(WORD[0], placed[0])
        Assert.assertEquals(WORD[1] - GAP - HEIGHT, placed[1])
        Assert.assertTrue("the card must clear the anchor's top", placed[1] + HEIGHT <= WORD[1])
    }

    @Test
    fun aCardWithNoRoomOverItGoesUnderIt() {
        // The anchor is a word near the top of the screen, so nothing fits above it.
        val nearTheTop = intArrayOf(100, 100, 300, 148)

        val placed = place(nearTheTop, WIDTH, HEIGHT)

        Assert.assertEquals(nearTheTop[0], placed[0])
        Assert.assertEquals(nearTheTop[3] + GAP, placed[1])
    }

    // -- inside the screen --------------------------------------------------------------------------

    @Test
    fun aCardAtTheLeftEdgeKeepsTheMargin() {
        val atTheEdge = intArrayOf(0, 600, 200, 648)

        val placed = place(atTheEdge, WIDTH, HEIGHT)

        Assert.assertEquals(MARGIN, placed[0])
    }

    @Test
    fun aCardRunningOffTheRightEdgeIsPulledBackToTheMargin() {
        val atTheEdge = intArrayOf(1000, 600, 1080, 648)

        val placed = place(atTheEdge, WIDTH, HEIGHT)

        Assert.assertEquals(SCREEN_WIDTH - MARGIN - WIDTH, placed[0])
        Assert.assertEquals(SCREEN_WIDTH - MARGIN, placed[0] + WIDTH)
    }

    @Test
    fun aCardWiderThanTheScreenStartsAtTheMargin() {
        val placed = place(intArrayOf(1000, 600, 1080, 648), SCREEN_WIDTH + 200, HEIGHT)

        Assert.assertEquals(MARGIN, placed[0])
    }

    @Test
    fun aCardTallerThanTheScreenKeepsItsHeadOnScreen() {
        // It fits nowhere; the top margin wins, so the overflow falls off the bottom edge rather than
        // the top, and the card's own title stays readable.
        val height = SCREEN_HEIGHT + 100

        val placed = place(WORD, WIDTH, height)

        Assert.assertEquals(MARGIN, placed[1])
        Assert.assertTrue(placed[1] + height > SCREEN_HEIGHT - MARGIN)
    }

    @Test
    fun aCardThatFitsOnNeitherSideIsPulledUpToTheMargin() {
        // Under the anchor it does not fit either, so the bottom clamp moves it back up until the top
        // margin wins. This is the case that decides the order of the two clamps.
        val nearTheTop = intArrayOf(100, 100, 300, 148)
        val height = SCREEN_HEIGHT - 20

        val placed = place(nearTheTop, WIDTH, height)

        Assert.assertEquals(MARGIN, placed[1])
        Assert.assertTrue(placed[1] + height > SCREEN_HEIGHT - MARGIN)
    }

    // -- the numbers the card borrows from the screen ------------------------------------------------

    @Test
    fun theEdgeMarginScalesWithTheDensity() {
        val nearTheEdge = intArrayOf(10, 600, 210, 648)

        val atDensityOne =
            PopupPlacement.place(nearTheEdge, WIDTH, HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, 1f, 4)[0]
        val atDensityThree =
            PopupPlacement.place(nearTheEdge, WIDTH, HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, 3f, 12)[0]

        // 10 px is past an 8 px margin and inside a 24 px one.
        Assert.assertEquals(10, atDensityOne)
        Assert.assertEquals(24, atDensityThree)
    }

    @Test
    fun aBiggerGapMovesTheCardFurthestFromTheAnchor() {
        val placed =
            PopupPlacement.place(WORD, WIDTH, HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT, DENSITY, 40)

        Assert.assertEquals(WORD[1] - 40 - HEIGHT, placed[1])
    }

    @Test
    fun theAnchorsRightEdgeChangesNothing() {
        // The horizontal clamp is against the screen, not against the anchor, so the fourth number is
        // carried for the rectangle's sake and read by nothing.
        val narrow = intArrayOf(100, 600, 300, 648)
        val wide = intArrayOf(100, 600, 9999, 648)

        Assert.assertArrayEquals(place(narrow, WIDTH, HEIGHT), place(wide, WIDTH, HEIGHT))
    }

    private companion object {
        const val SCREEN_WIDTH = 1080

        const val SCREEN_HEIGHT = 1920

        const val DENSITY = 2f

        const val GAP = 8

        /** The margin an 8 dp edge keeps at this density. */
        const val MARGIN = 16

        /** A word's rectangle: the middle of the screen, one 48 px line tall. */
        val WORD = intArrayOf(100, 600, 300, 648)

        const val WIDTH = 400

        const val HEIGHT = 200
    }
}
