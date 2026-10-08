package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.libs.Jid

/**
 * What the create-account screen blames, and what it must not. The screen's own symptom - "This is
 * not a valid username" on a perfectly good username, and then the same tap working after the field
 * was retyped - came from a check that compared a value with itself and from a catch-all that read
 * every failure as a bad username, so both verdicts are pinned here.
 */
class SignupIdentityTest {

    private fun jid(local: String, server: String): Jid = Jid.ofLocalAndDomain(local, server)

    // -- the case question -------------------------------------------------------------------------

    @Test
    fun aCapitalisedUsernameIsTheNameThatWasTyped() {
        // Jid lower-cases the local part; registering alice@yax.im for a typed Alice is what this
        // screen has always done, so the check must compare ignoring case and must not refuse it.
        Assert.assertEquals("the normalisation this test leans on", "alice", jid("Alice", "yax.im").getLocal())
        Assert.assertEquals(
            SignupIdentity.Problem.NONE,
            SignupIdentity.problem(jid("Alice", "yax.im"), "Alice", "yax.im"),
        )
        Assert.assertEquals(
            SignupIdentity.Problem.NONE,
            SignupIdentity.problem(jid("ALICE", "yax.im"), "ALICE", "yax.im"),
        )
        Assert.assertEquals(
            "not only ASCII: the case fold is not Java's toLowerCase either",
            SignupIdentity.Problem.NONE,
            SignupIdentity.problem(jid("АЛИСА", "yax.im"), "АЛИСА", "yax.im"),
        )
    }

    @Test
    fun aNameNormalisedBeyondCaseIsRefused() {
        // The mapping the check is actually for: the name that comes out is not the name that went
        // in, and it is not merely a difference of case.
        Assert.assertNotEquals(jid("İstanbul", "yax.im").getLocal(), "İstanbul")
        Assert.assertEquals(
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(jid("İstanbul", "yax.im"), "İstanbul", "yax.im"),
        )
    }

    @Test
    fun aUsernameThatIsNotTheOneTypedIsRefused() {
        Assert.assertEquals(
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(jid("alice", "yax.im"), "bob", "yax.im"),
        )
    }

    // -- the empty "own provider" field, the third defect ------------------------------------------

    @Test
    fun anEmptyOwnProviderServerIsTheServerField() {
        Assert.assertEquals(
            "the field the message belongs on",
            SignupIdentity.Problem.SERVER,
            SignupIdentity.problem(null, "alice", ""),
        )
        Assert.assertEquals(
            "jxmpp accepts a whitespace-only domain, so the emptiness test has to trim",
            SignupIdentity.Problem.SERVER,
            SignupIdentity.problem(null, "alice", "   "),
        )
        Assert.assertEquals(
            SignupIdentity.Problem.SERVER,
            SignupIdentity.problem(null, "alice", null),
        )
        // And when the jid did get built, a whitespace-only server is still a server problem: it
        // must not quietly register alice@  .
        Assert.assertEquals(
            SignupIdentity.Problem.SERVER,
            SignupIdentity.problem(jid("alice", "   "), "alice", "   "),
        )
    }

    @Test
    fun aServerThatCannotBeAJidAtAllIsTheServerField() {
        // Both fields non-empty and the construction still throws: the server is the one that
        // cannot be a domain. (NUL is what jxmpp's domainprep refuses.)
        val server = "a" + '\u0000' + "b"
        Assert.assertEquals(
            SignupIdentity.Problem.SERVER,
            SignupIdentity.problem(null, "alice", server),
        )
    }

    @Test
    fun aBadUsernameWithAGoodServerIsStillTheUsernameField() {
        Assert.assertEquals(
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(null, "a b", "yax.im"),
        )
        Assert.assertEquals(
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(null, "alice@yax.im", "yax.im"),
        )
        Assert.assertEquals(
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(null, "", "yax.im"),
        )
        Assert.assertEquals(
            "a blank form is a username problem first, which is the field it has always named",
            SignupIdentity.Problem.USERNAME,
            SignupIdentity.problem(null, "", ""),
        )
    }

    @Test
    fun aGoodPairSaysNothingAndMakesTheJid() {
        val built = jid("alice", "yax.im")
        Assert.assertEquals(
            SignupIdentity.Problem.NONE,
            SignupIdentity.problem(built, "alice", "yax.im"),
        )
        Assert.assertEquals("alice@yax.im", built.toString())
    }

    // -- the premise the screen's try/catch rests on ------------------------------------------------

    @Test
    fun theseAreTheInputsThatReachTheCatch() {
        // The screen builds the jid and catches IllegalArgumentException, so every input the helper
        // is asked about with a null jid must genuinely throw - and the whitespace-only server must
        // NOT, which is why the helper's trim is load-bearing rather than decorative.
        val throwers =
            arrayOf(
                arrayOf("", "yax.im"),
                arrayOf("a b", "yax.im"),
                arrayOf("alice", ""),
                arrayOf("", ""),
            )
        for (pair in throwers) {
            try {
                Jid.ofLocalAndDomain(pair[0], pair[1])
                Assert.fail("expected no Jid for \"${pair[0]}\"+\"${pair[1]}\"")
            } catch (expected: IllegalArgumentException) {
                Assert.assertNotEquals(
                    "blamed on nobody",
                    SignupIdentity.Problem.NONE,
                    SignupIdentity.problem(null, pair[0], pair[1]),
                )
            }
        }
        Assert.assertEquals(
            "a whitespace-only domain does not throw, so it is caught by the rule and not by the catch",
            "alice@   ",
            jid("alice", "   ").toString(),
        )
    }
}
