package uk.xa0.tulkki.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: a `Jid.Invalid` answers a bare form instead of throwing - the guard upstream wrote the
 * method for. port-10's divergence list had `asBareJid` as the one member of the family upstream
 * implements and we still threw `AssertionError("Not implemented")` from.
 *
 * Why it is a live crash and not a curiosity: `AssertionError` is an `Error`, so nothing that
 * catches `Exception` contains it - and the values are real and stored. `DatabaseBackend` tests
 * `conversation.getJid() instanceof Jid.Invalid` at four sites (876/1506/1545/2924), and
 * `Bookmark.parse`/`parseFromItem` and `AbstractParser` build them from attributes a remote party
 * chose, through `Element.getAttributeAsJid` - which is `Jid.ofOrInvalid`, the construction used
 * here, so the cell builds its subjects the way the island does.
 *
 * The three outcomes the port must keep:
 *
 * 1. the part before the resource separator is empty - the empty segment reaches `Jid.of("")`'s
 *    `IllegalArgumentException`, and the instance itself comes back;
 * 2. it does not parse - the instance itself comes back;
 * 3. it parses - its own `asBareJid()` comes back;
 *
 * and in none of the three does anything throw. Before the port every one of them threw
 * `AssertionError`, which is the whole of the defect.
 *
 * The subjects are chosen from a probe of `jxmpp-jid` 1.1.0 rather than by eye, because the parser
 * is more lenient than it looks: `not a jid/room` and `a@b@c/room` **parse** (so they cannot stand
 * for case 2 or 3 at all), while `tulkki@example.org/` refuses with a parseable bare part and
 * `a@/room` refuses with an unparseable one (`a@`).
 */
class JidInvalidBareFormTest {

    @Test
    fun aMalformedAddressAnswersABareFormInsteadOfThrowing() {
        val emptyAddress = Jid.ofOrInvalid("")
        assertTrue("the empty address is unparseable", emptyAddress is Jid.Invalid)
        assertSame(emptyAddress, emptyAddress.asBareJid())

        val emptySegment = Jid.ofOrInvalid("/room")
        assertTrue(
            "an address with an empty first segment is unparseable",
            emptySegment is Jid.Invalid)
        assertSame(emptySegment, emptySegment.asBareJid())

        val unparseableBarePart = Jid.ofOrInvalid("a@/room")
        assertTrue(
            "a local part with an empty domain is unparseable",
            unparseableBarePart is Jid.Invalid)
        assertSame(unparseableBarePart, unparseableBarePart.asBareJid())

        val parseableBarePart = Jid.ofOrInvalid("tulkki@example.org/")
        assertTrue(
            "a full address with an empty resource is unparseable",
            parseableBarePart is Jid.Invalid)
        assertEquals(
            Jid.of("tulkki@example.org").asBareJid(), parseableBarePart.asBareJid())
    }
}
