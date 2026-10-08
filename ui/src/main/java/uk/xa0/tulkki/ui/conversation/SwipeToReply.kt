package uk.xa0.tulkki.ui.conversation

/**
 * §4.4's gesture hygiene as arithmetic: "rightward only; damped at `MAX_SWIPE_DISTANCE_RATIO = 0.18`;
 * armed at `ACTIVE_SWIPE_DISTANCE_RATIO = 0.13`; haptics on arming".
 *
 * <p>It is a rule rather than a gesture handler so the three numbers have a cell and the Composable
 * has no branch. `IDEA-SCAN §2.8`'s damping is read here as the wall the design's own word describes:
 * the bubble follows the finger and then stops at 18% of the row, so a long drag cannot carry a
 * message off the screen and a short one cannot arm the reply by accident. The armed distance is
 * below the wall on purpose - arming happens before the wall is reached, so the reply fires while the
 * bubble is still moving and the haptic is felt at the moment the action becomes possible.
 */
object SwipeToReply {

    /** The furthest a bubble travels, as a fraction of the row's width. */
    const val MAX_SWIPE_DISTANCE_RATIO = 0.18f

    /** The distance at which letting go replies, as a fraction of the row's width. */
    const val ACTIVE_SWIPE_DISTANCE_RATIO = 0.13f

    /**
     * How far the bubble is drawn for a raw drag: rightward only, and never past the wall.
     *
     * <p>A leftward drag answers `0` rather than a negative offset - the design's "rightward only" is
     * about which gesture means reply, and the leftward one means nothing here, so the bubble is not
     * dragged in a direction that leads nowhere.
     */
    @JvmStatic
    fun offset(rawDrag: Float, width: Float): Float {
        if (width <= 0f || rawDrag <= 0f) {
            return 0f
        }
        return rawDrag.coerceAtMost(width * MAX_SWIPE_DISTANCE_RATIO)
    }

    /** Whether letting go at this offset replies. */
    @JvmStatic
    fun armed(offset: Float, width: Float): Boolean =
        width > 0f && offset >= width * ACTIVE_SWIPE_DISTANCE_RATIO

    /**
     * How far the arming is towards happening, in `[0, 1]`: the reply mark's own fade, so the mark is
     * absent while nothing is armed and fully drawn at the moment the gesture would fire.
     */
    @JvmStatic
    fun armProgress(offset: Float, width: Float): Float {
        if (width <= 0f || offset <= 0f) {
            return 0f
        }
        return (offset / (width * ACTIVE_SWIPE_DISTANCE_RATIO)).coerceIn(0f, 1f)
    }
}
