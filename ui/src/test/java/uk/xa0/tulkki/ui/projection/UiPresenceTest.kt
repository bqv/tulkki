package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Presence

/**
 * `ui-8`'s cells over the presence dot's vocabulary: the six states `Presence.Status` has, the three the
 * tree drew, and the three it drew nothing for.
 *
 * <p>What the screen draws *with* each state is the screen's, and no JVM test reaches a Composable; what
 * these cells hold is that the state a row carries is the one the tree's own two gates imply - the
 * contact's shown status, and whether that contact's account is connected - and that everything else is
 * `UNKNOWN`, the state the row draws nothing for.
 */
class UiPresenceTest {

    @Test
    fun everyStatusTheTreeColouredMapsToItsOwnState() {
        Assert.assertEquals(UiPresence.ONLINE, UiPresence.of(Presence.Status.CHAT, true, true))
        Assert.assertEquals(UiPresence.ONLINE, UiPresence.of(Presence.Status.ONLINE, true, true))
        Assert.assertEquals(UiPresence.AWAY, UiPresence.of(Presence.Status.AWAY, true, true))
        Assert.assertEquals(
            "the tree drew XA and DND in the same colour, so they are one state here",
            UiPresence.DND,
            UiPresence.of(Presence.Status.XA, true, true),
        )
        Assert.assertEquals(UiPresence.DND, UiPresence.of(Presence.Status.DND, true, true))
        Assert.assertEquals(UiPresence.OFFLINE, UiPresence.of(Presence.Status.OFFLINE, true, true))
    }

    @Test
    fun theUnknownsAreEveryCaseTheTreeDrewNothingFor() {
        Assert.assertEquals("nobody has read a status", UiPresence.UNKNOWN, UiPresence.of(null, true, true))
        Assert.assertEquals(
            "the contact's account is not connected, so the tree dropped the status it had",
            UiPresence.UNKNOWN,
            UiPresence.of(Presence.Status.ONLINE, false, true),
        )
        Assert.assertEquals(
            "the owner asked for no contact status at all",
            UiPresence.UNKNOWN,
            UiPresence.of(Presence.Status.ONLINE, true, false),
        )
        Assert.assertEquals(
            "and a room, which has no contact to be present",
            UiPresence.UNKNOWN,
            UiPresence.of(Presence.Status.AWAY, false, false),
        )
    }
}
