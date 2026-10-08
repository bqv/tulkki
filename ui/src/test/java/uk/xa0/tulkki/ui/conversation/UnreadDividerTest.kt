package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test

/**
 * §4.2's two numbers, as the design writes them: the jump decision is "a pure function of `(unreadCount,
 * lastVisible, itemCount)`" and the divider's position "a pure function of the list".
 *
 * <p>The cell that matters most is the slack's own edge - the `+ 2` is what keeps a reader who is one or two
 * rows from the bottom from being jumped to a divider directly above them - so the boundary is pinned on
 * both sides rather than only the two obvious cases.
 */
class UnreadDividerTest {

    @Test
    fun nothingUnreadStaysAtTheBottom() {
        Assert.assertEquals(UnreadJump.BOTTOM, UnreadDivider.jump(0, 3, 40))
        Assert.assertEquals("and a count that has not landed yet is not unread either", UnreadJump.BOTTOM, UnreadDivider.jump(-1, 3, 40))
    }

    @Test
    fun aReaderNearTheEndIsNotThrownUpwards() {
        // `lastVisible + 2 >= itemCount - unreadCount`: 8 + 2 >= 40 - 30, so the bottom is where they are.
        Assert.assertEquals(UnreadJump.BOTTOM, UnreadDivider.jump(30, 8, 40))
        // The slack's own edge with the divider at index 5: 3 + 2 >= 40 - 35 is exactly true.
        Assert.assertEquals("the edge itself is still the bottom", UnreadJump.BOTTOM, UnreadDivider.jump(35, 3, 40))
        Assert.assertEquals(
            "one row further back and the first unread is worth showing",
            UnreadJump.FIRST_UNREAD,
            UnreadDivider.jump(35, 2, 40),
        )
    }

    @Test
    fun aReaderAtTheStartIsShownTheFirstUnread() {
        Assert.assertEquals(UnreadJump.FIRST_UNREAD, UnreadDivider.jump(3, 1, 40))
        // Older history prepended while the reader sits at the top: the divider moves away from them, never
        // under their eyes, which is the same answer.
        Assert.assertEquals(UnreadJump.FIRST_UNREAD, UnreadDivider.jump(3, 1, 80))
    }

    @Test
    fun theDividerIsTheFirstUnreadRowAndNeverOffTheList() {
        Assert.assertNull("nothing unread, nothing to draw", UnreadDivider.anchor(0, 10))
        Assert.assertNull("and an empty list has no place for one", UnreadDivider.anchor(1, 0))
        Assert.assertEquals("the newest three of ten start at index seven", 7, UnreadDivider.anchor(3, 10))
        Assert.assertEquals("the whole list unread puts it at the top", 0, UnreadDivider.anchor(10, 10))
        Assert.assertEquals(
            "a count from a read that has not landed is clamped to the top, not to a negative index",
            0,
            UnreadDivider.anchor(20, 5),
        )
    }
}
