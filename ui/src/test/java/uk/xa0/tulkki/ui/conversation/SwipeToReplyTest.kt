package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test

/**
 * §4.4's three numbers, as the design states them: "rightward only; damped at
 * `MAX_SWIPE_DISTANCE_RATIO = 0.18`; armed at `ACTIVE_SWIPE_DISTANCE_RATIO = 0.13`".
 *
 * <p>The cells pin the two boundaries rather than the middle: a drag that never moves leftwards, a
 * drag that stops at the wall instead of carrying the bubble off the row, and arming at exactly the
 * design's distance while a hair less does not - because a gesture that fires one pixel early is a
 * reply the owner did not mean to open.
 */
class SwipeToReplyTest {

    private val width = 1000f

    @Test
    fun aLeftwardDragMovesNothing() {
        Assert.assertEquals(0f, SwipeToReply.offset(-120f, width), 0f)
        Assert.assertEquals("and a long one is still nothing", 0f, SwipeToReply.offset(-500f, width), 0f)
        Assert.assertFalse("and it never arms", SwipeToReply.armed(SwipeToReply.offset(-500f, width), width))
    }

    @Test
    fun theBubbleStopsAtTheDesignsOwnWall() {
        Assert.assertEquals(width * SwipeToReply.MAX_SWIPE_DISTANCE_RATIO, SwipeToReply.offset(300f, width), 1e-3f)
        Assert.assertEquals("a very long drag is the same drag", width * SwipeToReply.MAX_SWIPE_DISTANCE_RATIO, SwipeToReply.offset(50_000f, width), 1e-3f)
    }

    @Test
    fun theBubbleFollowsTheFingerUpToTheWall() {
        var previous = 0f
        for (step in 1..9) {
            val now = SwipeToReply.offset(step * 20f, width)
            Assert.assertTrue("the bubble went back at step $step", now > previous)
            previous = now
        }
    }

    @Test
    fun armingHappensAtExactlyTheDesignsDistance() {
        val active = width * SwipeToReply.ACTIVE_SWIPE_DISTANCE_RATIO
        Assert.assertTrue(SwipeToReply.armed(active, width))
        Assert.assertFalse("a hair short of it is not a reply", SwipeToReply.armed(active - 0.5f, width))
    }

    @Test
    fun armingHappensBeforeTheWall() {
        // The reply must become possible while the bubble is still moving, or the haptic arrives after
        // the gesture has already stopped.
        Assert.assertTrue(
            SwipeToReply.ACTIVE_SWIPE_DISTANCE_RATIO < SwipeToReply.MAX_SWIPE_DISTANCE_RATIO,
        )
        Assert.assertTrue(SwipeToReply.armed(SwipeToReply.offset(500f, width), width))
    }

    @Test
    fun theArmProgressFadesInAndReachesOneWhenItArms() {
        Assert.assertEquals(0f, SwipeToReply.armProgress(0f, width), 0f)
        Assert.assertEquals(
            0.5f,
            SwipeToReply.armProgress(width * SwipeToReply.ACTIVE_SWIPE_DISTANCE_RATIO / 2f, width),
            1e-3f,
        )
        Assert.assertEquals(
            "past arming the mark is fully drawn, and never brighter",
            1f,
            SwipeToReply.armProgress(width * SwipeToReply.MAX_SWIPE_DISTANCE_RATIO, width),
            0f,
        )
    }

    @Test
    fun nothingIsSwipeableWithoutAWidth() {
        Assert.assertEquals(0f, SwipeToReply.offset(100f, 0f), 0f)
        Assert.assertFalse(SwipeToReply.armed(100f, 0f))
        Assert.assertEquals(0f, SwipeToReply.armProgress(100f, 0f), 0f)
    }
}
