package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: 3.7 C5-D. The island's `AccountRef.StateRef` is a type of its own precisely so the two
 * enums never meet, which means the one place they do meet - the name-for-name mapper in
 * `Account.State` - is the only thing keeping them honest. A build cannot see an enum constant
 * added on one side and not the other: the switch would simply fall through to the throw at
 * runtime, on a path only a real connection reaches.
 *
 * So this pins it, and it is deliberately a pure JVM test - `Account.State`'s own static
 * initialiser touches no Android type, which is why it can run here at all.
 */
class AccountStateRefTest {

    @Test
    fun everyModelStateHasARefAndRoundTripsBack() {
        for (state in Account.State.values()) {
            val ref = state.toRef()
            Assert.assertNotNull("no StateRef for " + state.name, ref)
            Assert.assertEquals(state.name, ref.name)
            Assert.assertSame(state, Account.State.fromRef(ref))
        }
    }

    @Test
    fun everyRefStateHasAModelState() {
        for (ref in AccountRef.StateRef.values()) {
            val state = Account.State.fromRef(ref)
            Assert.assertEquals(ref.name, state.name)
            Assert.assertSame(ref, state.toRef())
        }
    }

    /** The two constants must be the same set, which is what makes the two loops above equivalent. */
    @Test
    fun theTwoEnumsHaveTheSameConstants() {
        Assert.assertEquals(
            Account.State.values().size, AccountRef.StateRef.values().size)
    }
}
