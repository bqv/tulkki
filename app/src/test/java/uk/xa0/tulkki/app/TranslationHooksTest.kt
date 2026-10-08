package uk.xa0.tulkki.app

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationDecision
import uk.xa0.tulkki.libs.Jid

/**
 * The receive path's own mapping, from a [Message] to the candidate the gate reads - and the
 * gate itself, [TranslationHooks.shouldQueue], whose off state is the one thing this class
 * exists to pin.
 *
 * <p>Deliberately not a hand-built candidate. The bug this pins was one wrong value inside that
 * mapping - a getter that never returns null, asked whether it was null - so a test that assembles
 * the candidate itself cannot see it, and neither could any amount of reading the rules.
 * {@code TranslationDecisionTest} covers the rules; this covers what they are fed, and the seam that
 * consults them is exercised through the same code the receive path runs.
 */
class TranslationHooksTest {

    private companion object {

        /** The off state of a fresh install: both languages read the locale, so the pair is equal. */
        val OFF: Interpreter = Interpreter.of("fi", "fi")

        /** The one shape of the truth table that is on: two different languages. */
        val ON: Interpreter = Interpreter.of("fi", "de")

        /**
         * The least a Message needs to exist; getAccount and getContact are never reached here.
         */
        class FakeConversation : Conversational {
            override fun getAccount(): Account? = null

            /**
             * The interface's `getContact()` is non-null, while the Java fake answered `null`; the
             * message constructors never ask a conversation for a contact, so the faithful Kotlin
             * spelling of "null, never read" is to refuse the call rather than invent a contact.
             */
            override fun getContact(): Contact =
                throw UnsupportedOperationException(
                    "the fake conversation is never asked for a contact")

            override fun getJid(): Jid? = Jid.of("tulkki@example.org")

            override fun getMode(): Int = Conversational.MODE_SINGLE

            override fun getUuid(): String = "conversation"

            override fun getEphemeralTimer(): Int = 0

            override fun canInferPresence(): Boolean = false
        }

        /**
         * A plain live message from somebody else: the shape {@code MessageParser} builds for a
         * {@code <message type='chat'><body>…</body></message>} from a stranger - the encryption and the
         * status it passes to the constructor, and nothing else attached. If this ever stops being
         * eligible, nothing arriving is ever translated again.
         */
        fun livePlainText(body: String?): Message =
            Message(FakeConversation(), body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)

        /** The gate's input for a live plain-text message: the shape the automatic pass translates. */
        fun liveCandidate(body: String): TranslationDecision.Candidate {
            val message = livePlainText(body)
            return TranslationHooks.candidate(message, message.getRawBody(), false, false, false)
        }
    }

    @Test
    fun aPlainLiveMessageIsEligible() {
        val message = livePlainText("Guten Morgen! Wie geht es dir heute?")
        Assert.assertTrue(
            "the automatic pass refuses this shape and the feature translates nothing at all",
            TranslationDecision.isEligible(
                TranslationHooks.candidate(message, message.getRawBody(), false, false, false), ON))
    }

    @Test
    fun aPlainLiveMessageCanBeAskedForByTappingIt() {
        val message = livePlainText("Hallo! Ich habe dir gestern ein Buch gegeben.")
        Assert.assertTrue(
            "a tap on the same message has to pass the same mapping",
            TranslationDecision.isRequestable(
                TranslationHooks.candidate(message, message.getRawBody(), false, false, false), ON))
    }

    @Test
    fun aDecryptedPgpMessageIsRequestableWhileCiphertextIsNot() {
        // The two shapes the same row has before and after the PGP decryption service runs: still
        // ciphertext, which nothing may read, and decrypted, which the owner's tap may buy one
        // message at a time. The mapping has to say so for the real Message, which is why this is
        // here and not only in TranslationDecisionTest.
        val ciphertext =
            Message(
                FakeConversation(),
                "-----BEGIN PGP MESSAGE-----",
                Message.ENCRYPTION_PGP,
                Message.STATUS_RECEIVED)
        val stillEncrypted =
            TranslationHooks.candidate(ciphertext, ciphertext.getRawBody(), false, false, false)
        Assert.assertFalse(
            "ciphertext must never be sent anywhere, by the automatic pass or by a tap",
            TranslationDecision.isRequestable(stillEncrypted, ON))
        Assert.assertFalse(TranslationDecision.isEligible(stillEncrypted, ON))

        val decrypted =
            Message(
                FakeConversation(),
                "Guten Morgen! Wie geht es dir heute?",
                Message.ENCRYPTION_DECRYPTED,
                Message.STATUS_RECEIVED)
        Assert.assertTrue(
            "the decryption service leaves the row in this shape, and the tap has to pass it",
            TranslationDecision.isRequestable(
                TranslationHooks.candidate(decrypted, decrypted.getRawBody(), false, false, false),
                ON))
    }

    @Test
    fun aMessageWithNothingAttachedDoesNotLookLikeAFile() {
        // getFileParams() is never null: for a message with nothing attached it builds exactly this.
        // "hasFileParams = getFileParams() != null" therefore answered yes for every message there
        // is, which is how both ways in came to refuse all of them.
        Assert.assertTrue(
            "an empty FileParams is what a message with no attachment reports",
            Message.FileParams("").isEmpty())
        Assert.assertFalse(
            "a real attachment must still report as one, or the rule stops refusing files",
            Message.FileParams("https://example.org/photo.jpg").isEmpty())
    }

    /**
     * The off state's whole point: nothing is enqueued, and the shape below is the one the receive
     * path queues when the interpreter is on, so a {@code false} here can only be the predicate -
     * never the message.
     */
    @Test
    fun theOffInterpreterNeverQueuesAnOtherwiseEligibleMessage() {
        val candidate = liveCandidate("Guten Morgen! Wie geht es dir heute?")
        Assert.assertTrue(
            "this is the shape the automatic pass translates when the interpreter is on",
            TranslationDecision.isEligible(candidate, ON))
        Assert.assertFalse(
            "with the interpreter off nothing is enqueued, not even the eligible message",
            TranslationHooks.shouldQueue(candidate, OFF))
    }

    /** Every off shape of the truth table, not only the same-language one. */
    @Test
    fun everyOffPairRefusesTheSameEligibleMessage() {
        val candidate = liveCandidate("Guten Morgen! Wie geht es dir heute?")
        val offPairs =
            arrayOf(
                arrayOf("fi", "fi"),
                arrayOf("de", "de"),
                arrayOf("en", "en"),
                arrayOf("fi", Interpreter.NONE),
                arrayOf(Interpreter.NONE, "fi"),
                arrayOf("en", Interpreter.NONE),
                arrayOf(Interpreter.NONE, "en"),
                arrayOf(Interpreter.NONE, Interpreter.NONE),
            )
        for (pair in offPairs) {
            Assert.assertFalse(
                "the interpreter is off for app=" + pair[0] + " study=" + pair[1] +
                    ", so nothing is queued",
                TranslationHooks.shouldQueue(candidate, Interpreter.of(pair[0], pair[1])))
        }
    }

    /** The other half of the seam: with the interpreter on, nothing about the decision moved. */
    @Test
    fun theOnInterpreterQueuesExactlyWhatItAlwaysDid() {
        Assert.assertTrue(
            "with the interpreter on the receive path is unchanged: an eligible message is queued",
            TranslationHooks.shouldQueue(
                liveCandidate("Guten Morgen! Wie geht es dir heute?"), ON))
        val ciphertext =
            Message(
                FakeConversation(),
                "-----BEGIN PGP MESSAGE-----",
                Message.ENCRYPTION_PGP,
                Message.STATUS_RECEIVED)
        Assert.assertFalse(
            "and a body the rules refuse is still refused with the interpreter on",
            TranslationHooks.shouldQueue(
                TranslationHooks.candidate(ciphertext, ciphertext.getRawBody(), false, false, false),
                ON))
    }

    /** The tap's seam: with the interpreter off there is nothing a tap could ask for either. */
    @Test
    fun aTapAsksForNothingWhenTheInterpreterIsOff() {
        val candidate = liveCandidate("Hallo! Ich habe dir gestern ein Buch gegeben.")
        Assert.assertTrue(
            "the tap's own rule allows this message when the interpreter is on",
            TranslationDecision.isRequestable(candidate, ON))
        Assert.assertTrue(TranslationHooks.shouldRequest(candidate, ON))
        Assert.assertFalse(
            "with the interpreter off a tap enqueues nothing at all",
            TranslationHooks.shouldRequest(candidate, OFF))
    }
}
