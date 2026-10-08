package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.DoubtHold

/**
 * Item 16's per-conversation switch, as the conversation screen reads it.
 *
 * <p>The cells exist for one trap and its consequence: the column is a **tri-state**, so "the owner
 * never chose" is not a spelling of "off". A surface that read `NULL` as off would turn the hold off
 * in every room the owner has not visited - which is item 16's whole complaint, since a switch they
 * must remember to set leaves the bug everywhere they forgot - and a fresh install would hold nothing.
 * [UiDoubtHold.of] is therefore pinned to answer `DoubtHold`'s own rule rather than a rule of its own:
 * the shipped default is read from `DoubtHold.SHIPPED_DEFAULT` here, so the surface and the send path
 * cannot be told apart when the build changes its mind.
 */
class UiDoubtHoldTest {

    @Test
    fun aConversationThatNeverChoseFollowsTheBuildsDefault() {
        Assert.assertEquals(
            "and the build's default is what it is: the surface has none of its own",
            DoubtHold.SHIPPED_DEFAULT,
            UiDoubtHold.of(null).inForce,
        )
        Assert.assertTrue("which is on, so a fresh install holds a doubtful translation", UiDoubtHold.of(null).inForce)
    }

    @Test
    fun theOwnersOwnAnswersWin() {
        Assert.assertTrue(UiDoubtHold.of(true).inForce)
        Assert.assertFalse("the owner turned the hold off for this room", UiDoubtHold.of(false).inForce)
    }

    /**
     * The trap itself, stated as its own cell: an untouched room and a room the owner turned the hold
     * off in must not read alike, which is exactly what a nullable Boolean collapses if it is asked
     * for its truthiness.
     */
    @Test
    fun neverChoseIsNotOff() {
        Assert.assertNotEquals(UiDoubtHold.of(null), UiDoubtHold.of(false))
    }
}
