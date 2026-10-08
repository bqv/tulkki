package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.UiRunFlags
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * §4.1's geometry, as the design writes it: "tail vs no-tail changes the bubble's padding; the avatar
 * is **INVISIBLE (space kept)** when not first-of-run and **GONE** only when avatars are off".
 *
 * <p>The two cells that carry the design's own distinction are `avatarsOffReserveNoSpaceAtAll` and
 * `aRowInsideItsRunKeepsTheColumnsSpace`: those two rows draw the same nothing and must not be the
 * same state, because one of them still holds the run's edge.
 */
class BubbleRunTest {

    private fun flags(
        firstOfRun: Boolean = false,
        lastOfRun: Boolean = false,
        firstOfDay: Boolean = false,
        showAvatar: Boolean = false,
        showName: Boolean = false,
    ): UiRunFlags = UiRunFlags(firstOfRun, lastOfRun, firstOfDay, showAvatar, showName)

    @Test
    fun theHeadOfARunTakesTheRunGapAbove() {
        Assert.assertEquals(TulkkiSpacing.sm, BubbleRun.of(flags(firstOfRun = true), avatarsOn = true).gapAbove)
    }

    @Test
    fun theTailOfARunTakesTheRunGapBelow() {
        Assert.assertEquals(TulkkiSpacing.sm, BubbleRun.of(flags(lastOfRun = true), avatarsOn = true).gapBelow)
    }

    @Test
    fun theMiddleOfARunIsTightOnBothSides() {
        val run = BubbleRun.of(flags(), avatarsOn = true)
        Assert.assertEquals(TulkkiSpacing.xxs, run.gapAbove)
        Assert.assertEquals(TulkkiSpacing.xxs, run.gapBelow)
    }

    @Test
    fun aNewDayGetsTheRunGapEvenMidRun() {
        // `MessageRuns` computes the two flags independently, and a bubble that sat flush against one
        // sent yesterday would read as one message.
        val run = BubbleRun.of(flags(firstOfDay = true), avatarsOn = true)
        Assert.assertEquals(TulkkiSpacing.sm, run.gapAbove)
    }

    @Test
    fun avatarsOffReserveNoSpaceAtAll() {
        Assert.assertEquals(
            AvatarPlacement.GONE,
            BubbleRun.of(flags(showAvatar = true), avatarsOn = false).avatar,
        )
    }

    @Test
    fun aRowInsideItsRunKeepsTheColumnsSpace() {
        Assert.assertEquals(
            "space kept, and nothing drawn in it",
            AvatarPlacement.RESERVED,
            BubbleRun.of(flags(showAvatar = false), avatarsOn = true).avatar,
        )
    }

    @Test
    fun theRowTheFlagsNameDrawsIt() {
        Assert.assertEquals(AvatarPlacement.DRAWN, BubbleRun.of(flags(showAvatar = true), avatarsOn = true).avatar)
    }

    @Test
    fun theNameFlagIsTheRowsOwn() {
        Assert.assertTrue(BubbleRun.of(flags(showName = true), avatarsOn = true).showName)
        Assert.assertFalse(BubbleRun.of(flags(), avatarsOn = true).showName)
    }
}
