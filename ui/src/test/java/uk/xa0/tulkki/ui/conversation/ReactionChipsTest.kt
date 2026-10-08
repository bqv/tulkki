package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.UiReaction

/**
 * The reaction chip's one word, pinned: the emoji alone for a group of one, the emoji and the group's
 * size otherwise - the branch the deleted `BindingAdapters.setupEmojiChip` decided
 * (`count < 2` draws `emoji.unicode`, else `String.format(Locale.ENGLISH, "%s %d", …)`), narrowed to
 * the unicode chips `MessageProjection.reactions` keeps.
 *
 * <p>The fill and the pill are pixels and are the screenshot cell's; this is the only part of a chip
 * that is a rule rather than a drawing.
 */
class ReactionChipsTest {

    @Test
    fun aSingleReactionDrawsTheEmojiAlone() {
        Assert.assertEquals(
            "the count is not information for a group of one",
            "\uD83D\uDC4D",
            ReactionChips.label(UiReaction("\uD83D\uDC4D", 1, false)),
        )
    }

    @Test
    fun aGroupOfTwoOrMoreDrawsItsSizeAfterTheEmoji() {
        Assert.assertEquals(
            "\uD83D\uDC4D 2",
            ReactionChips.label(UiReaction("\uD83D\uDC4D", 2, true)),
        )
        Assert.assertEquals(
            "and the size is the group's length however large it grows",
            "\uD83C\uDF89 11",
            ReactionChips.label(UiReaction("\uD83C\uDF89", 11, false)),
        )
    }

    @Test
    fun theOwnersFillDoesNotChangeTheWord() {
        Assert.assertEquals(
            "mine is the chip's colour, never its text",
            ReactionChips.label(UiReaction("\uD83D\uDC4D", 3, true)),
            ReactionChips.label(UiReaction("\uD83D\uDC4D", 3, false)),
        )
    }
}
