package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.UiEncryption

/**
 * Which of the five bubble families a row's body is drawn in.
 *
 * <p>It is `MessageAdapter`'s own `BubbleColor` decision (`ui/src/main/java/uk/xa0/tulkki/ui/adapter/MessageAdapter.java:2893-2912`),
 * lifted out of `render` and its view holder so the decision has a cell and the Composable has no
 * branch: a received body is `SECONDARY` when the owner asked for colourful bubbles and `SURFACE`
 * when they did not, an own body is `TERTIARY` or `SURFACE_HIGH`, and a body whose crypto the tree
 * could not read is `WARNING`. The tree's third branch, the owner's own body under a
 * `colorful == false && black` theme, took `SECONDARY`; that combination is `SURFACE_HIGH` here
 * because the theme is a Compose scheme now and "the surface happens to be black" is not a fact a
 * screen can read - recorded rather than translated.
 *
 * <p>**`WARNING` is the one case the projection can decide, and it is decided on failure.** The
 * tree asked a live session object (`isInValidSession`); a snapshot cannot answer that and §2.2.1
 * keeps the finer crypto vocabulary deferred, so the one row the projection carries as unreadable is
 * `UiEncryption.Failed`, and that is what takes the warning colour. A pending decryption is not a
 * failure and stays the ordinary incoming tone.
 */
enum class BubbleTone {
    /** `surface`: the plain, uncoloured bubble. */
    SURFACE,

    /** `surfaceVariant`: the plain bubble on the owner's side. */
    SURFACE_HIGH,

    /** `secondaryContainer`: the incoming bubble when the owner asked for colour. */
    SECONDARY,

    /** `tertiaryContainer`: the own bubble when the owner asked for colour. */
    TERTIARY,

    /** `errorContainer`: a body whose crypto could not be read. */
    WARNING;

    companion object {

        /**
         * The tree's branch, with the two arguments it read the screen cannot: whether the owner
         * asked for colourful bubbles, and - through [UiEncryption.Failed] - whether the body is
         * readable at all.
         */
        @JvmStatic
        fun of(direction: Direction, encryption: UiEncryption, colorful: Boolean): BubbleTone =
            when {
                encryption is UiEncryption.Failed -> WARNING
                direction == Direction.INCOMING -> if (colorful) SECONDARY else SURFACE
                else -> if (colorful) TERTIARY else SURFACE_HIGH
            }
    }
}
