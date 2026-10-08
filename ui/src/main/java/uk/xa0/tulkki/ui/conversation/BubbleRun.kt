package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.unit.Dp
import uk.xa0.tulkki.ui.projection.UiRunFlags
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * Where a row's avatar is, which is not the same question as whether the row could have one.
 *
 * <p>§4.1 keeps the tree's own distinction and says why it is easy to lose: "the avatar is
 * **INVISIBLE (space kept)** when not first-of-run and **GONE** only when avatars are off". So a
 * row inside its run still holds the avatar's column - the bubbles of a run stay on one edge - while
 * a screen with avatars off does not reserve anything. [DRAWN] is the row the flags name
 * (`UiRunFlags.showAvatar`, which is the run's head on the incoming side and its tail on the own
 * one), and a host whose port has not resolved the image yet draws its plate in the same space.
 */
enum class AvatarPlacement {
    /** This row shows the avatar. */
    DRAWN,

    /** No image on this row, and the space is still the run's. */
    RESERVED,

    /** Avatars are off: no space at all. */
    GONE,
}

/**
 * What one row's own run asks of its bubble's geometry, §4.1's "tail vs no-tail changes the bubble's
 * padding" and the avatar distinction above.
 *
 * <p>It is a pure rule so the geometry has a cell and the `LazyColumn`'s item has no arithmetic:
 * the screen asks this for each row and draws the answer.
 */
data class BubbleRun(
    /** The space above this bubble: the run gap at a run's head, the tight gap inside one. */
    val gapAbove: Dp,

    /** The space below it: the run gap at a run's tail, the tight gap inside one. */
    val gapBelow: Dp,

    val avatar: AvatarPlacement,

    /** §4.1's name flag, passed through: whether the row's own label would be drawn above it. */
    val showName: Boolean,
) {

    companion object {

        /** Between two runs: one step of the scale, so a run reads as a block. */
        private val RUN_GAP = TulkkiSpacing.sm

        /** Inside a run: the smallest step, which is what keeps merged bubbles visually one stack. */
        private val IN_RUN_GAP = TulkkiSpacing.xxs

        /**
         * The row's own answer.
         *
         * @param flags the row's run flags, from `MessageRuns.runFlags`
         * @param avatarsOn whether this screen draws avatars at all: off means [AvatarPlacement.GONE]
         *     and no reserved column, which is the tree's one `GONE` case
         */
        @JvmStatic
        fun of(flags: UiRunFlags, avatarsOn: Boolean): BubbleRun =
            BubbleRun(
                // A day break always starts a run on screen, whatever the ninety-second merge window
                // says: `firstOfDay` is true only where the calendar moved, and a new day that sat
                // flush against the previous bubble would read as one message.
                gapAbove = if (flags.firstOfRun || flags.firstOfDay) RUN_GAP else IN_RUN_GAP,
                gapBelow = if (flags.lastOfRun) RUN_GAP else IN_RUN_GAP,
                avatar =
                    when {
                        !avatarsOn -> AvatarPlacement.GONE
                        flags.showAvatar -> AvatarPlacement.DRAWN
                        else -> AvatarPlacement.RESERVED
                    },
                showName = flags.showName,
            )
    }
}
