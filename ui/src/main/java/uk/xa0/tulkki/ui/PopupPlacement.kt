package uk.xa0.tulkki.ui

/**
 * Where the anchored card goes - a pure rule, so the arithmetic can be measured without a device.
 *
 * Everything that decides where a card sits lives here, with no Android type in it at all: the
 * geometry a `PopupWindow` needs is read from the screen metrics and the card's own measured size by
 * the card, and this class is handed the numbers. That is the same split [ReviewWave] makes on the
 * drawing side, and for the same reason - a placement rule pinned by a device reading is a rule
 * nobody can re-check.
 *
 * Above the anchor when there is room over it, below when there is not, and inside the screen on
 * both axes whatever happens: a card that half-leaves the screen is worse than the sheet it
 * replaced, so the clamp is this class's reason for existing rather than a nicety. Above comes first
 * because the card is a reading aid and the reader moves forward: covering the lines already read
 * costs less than covering the lines they are about to reach.
 */
object PopupPlacement {

    /** The gap between the card and the anchor, in dp. */
    const val GAP_DP = 4f

    /** The smallest distance the card keeps from the screen's edge, in dp. */
    const val MARGIN_DP = 8f

    /**
     * Where the card goes.
     *
     * The anchor is four ints - `{left, top, right, bottom}` in screen coordinates - rather than a
     * `Rect`, because a `Rect` is an Android type and this rule has to be callable from a JVM test.
     * Only the anchor's left, top and bottom are read: the horizontal clamp is against the screen,
     * not against the anchor, so its right edge changes nothing.
     *
     * @param anchorRect the anchor's screen rectangle as `{left, top, right, bottom}`
     * @param width the card's width in pixels
     * @param height the card's height in pixels
     * @param screenWidth the screen's width in pixels
     * @param screenHeight the screen's height in pixels
     * @param density the display's density, which the edge margin scales with
     * @param gapPx the distance to keep between the card and the anchor, in pixels
     * @return the card's screen position as `{left, top}`
     */
    @JvmStatic
    fun place(
        anchorRect: IntArray,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        gapPx: Int,
    ): IntArray {
        val margin = Math.round(MARGIN_DP * density)

        var left = anchorRect[0]
        if (left + width > screenWidth - margin) {
            left = screenWidth - margin - width
        }
        if (left < margin) {
            left = margin
        }

        var top = anchorRect[1] - gapPx - height
        if (top < margin) {
            // Not enough room over the anchor: sit below it instead.
            top = anchorRect[3] + gapPx
        }
        if (top + height > screenHeight - margin) {
            top = screenHeight - margin - height
        }
        if (top < margin) {
            // A card taller than the screen fits nowhere: the top margin wins, so its head stays
            // readable and the overflow falls off the bottom edge rather than the top.
            top = margin
        }
        return intArrayOf(left, top)
    }
}
