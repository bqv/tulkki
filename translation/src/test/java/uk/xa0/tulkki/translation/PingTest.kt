package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The two shapes that have no language of their own: the nudge (a whole-body name-and-colon) and the
 * conversation's own bare name (a whole body that is exactly the name the interface shows). Neither
 * is translated, covered, held or read for a language, so this predicate is the thing all of that
 * hangs on, and its edges are pinned here rather than left to the regex.
 *
 * <p>The first two sections are the shapes; the last section asks every place that consumes the
 * predicate, for both shapes, because a rule that only exists in {@link Ping} is a rule that does
 * nothing.
 */
class PingTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages. Every
     * consumer of the predicate below is asked with it on, so these tests stay about the ping shape.
     */
    private val ON = Interpreter.of("fi", "de")

    /** No conversation to compare against, so only the ping shape can match. */
    private fun ping(body: String?): Boolean {
        return Ping.isPing(body, null)
    }

    // -- the ping shape ----------------------------------------------------------------------------

    @Test
    fun aNameAndAColonIsAPing() {
        Assert.assertTrue(ping("Matti:"))
        Assert.assertTrue("a trailing space is how the shape is usually typed", ping("Matti: "))
        Assert.assertTrue(ping("  Matti:  "))
        Assert.assertTrue(ping("\tMatti:\n"))
    }

    @Test
    fun theCharactersNicksActuallyUseAreNicks() {
        Assert.assertTrue(ping("matti_:"))
        Assert.assertTrue(ping("matti-b:"))
        Assert.assertTrue(ping("[matti]:"))
        Assert.assertTrue(ping("{matti}:"))
        Assert.assertTrue(ping("|matti|:"))
        Assert.assertTrue(ping("^matti^:"))
        Assert.assertTrue(ping("`matti`:"))
        Assert.assertTrue(ping("Matti_2:"))
        Assert.assertTrue("a nick is not only ASCII", ping("Mätti:"))
    }

    @Test
    fun proseThatOpensWithANameIsNotAPing() {
        // The colon must not make what follows look like a nudge.
        Assert.assertFalse(ping("Matti: hello"))
        Assert.assertFalse(ping("Matti: katsotaan huomenna"))
        Assert.assertFalse(ping("hello world:"))
        Assert.assertFalse(ping("Matti :"))
    }

    @Test
    fun aColonAloneOrAnEmptyBodyIsNotAPing() {
        Assert.assertFalse(ping(":"))
        Assert.assertFalse(ping(": "))
        Assert.assertFalse(ping(""))
        Assert.assertFalse(ping("   "))
        Assert.assertFalse(ping(null))
    }

    @Test
    fun aBareNameIsNotAPing() {
        // The ping shape alone says nothing about a bare name - that is the conversation's business,
        // and with no name to compare against, "Matti" is ordinary text.
        Assert.assertFalse(ping("Matti"))
        Assert.assertFalse(ping("Joo"))
    }

    @Test
    fun aTimeIsNotAPing() {
        Assert.assertFalse(ping("12:30"))
        Assert.assertFalse(ping("9:00 "))
    }

    // -- the conversation's own name ---------------------------------------------------------------

    @Test
    fun aBodyThatIsExactlyTheConversationsNameIsABareName() {
        Assert.assertTrue(Ping.isPing("Helsinki", "Helsinki"))
        Assert.assertTrue(
                "the interface's name can carry padding of its own",
                Ping.isPing("Helsinki", " Helsinki "))
        Assert.assertTrue("and so can the body", Ping.isPing("  Helsinki  ", "Helsinki"))
        Assert.assertTrue(
                "a room whose name has spaces is still one name",
                Ping.isPing("Helsinki Finland", "Helsinki Finland"))
    }

    @Test
    fun theNameIsMatchedCaseInsensitively() {
        Assert.assertTrue(Ping.isPing("helsinki", "Helsinki"))
        Assert.assertTrue(Ping.isPing("HELSINKI", "helsinki"))
        Assert.assertTrue(Ping.isPing("Helsinki", "hElSiNkI"))
    }

    @Test
    fun theNameWithAColonIsStillAPing() {
        // Already the ping shape, and the name rule must not have broken it: "Helsinki:" is how a
        // room is nudged by its own name.
        Assert.assertTrue(Ping.isPing("Helsinki:", "Helsinki"))
        Assert.assertTrue(Ping.isPing("Helsinki: ", "Helsinki"))
    }

    @Test
    fun theNamePlusProseIsProse() {
        // Whole body only: a name that opens a sentence is a message, not a name.
        Assert.assertFalse(Ping.isPing("Helsinki is quiet today", "Helsinki"))
        Assert.assertFalse(Ping.isPing("Helsinki, how are you?", "Helsinki"))
        Assert.assertFalse(
                "a name plus a second name is not the name",
                Ping.isPing("Helsinki Matti", "Helsinki"))
        Assert.assertFalse(Ping.isPing("I am in Helsinki", "Helsinki"))
    }

    @Test
    fun aDifferentNameInTheSameConversationIsNotTheConversationsName() {
        // The rule is about *this* conversation's name, never about names in general.
        Assert.assertFalse(Ping.isPing("Matti", "Helsinki"))
        Assert.assertFalse(Ping.isPing("Kalle", "Helsinki"))
        Assert.assertTrue(
                "the ping shape still matches when the nick is not the room",
                Ping.isPing("Matti: ", "Helsinki"))
    }

    @Test
    fun aBlankOrMissingNameMatchesNothing() {
        // The failure direction: with no name to compare against, the body is ordinary text.
        Assert.assertFalse(Ping.isPing("Helsinki", null))
        Assert.assertFalse(Ping.isPing("Helsinki", ""))
        Assert.assertFalse(Ping.isPing("Helsinki", "   "))
        Assert.assertFalse(Ping.isPing("Helsinki", "\t\n"))
    }

    @Test
    fun nullAndEmptyBodiesAreNeverNames() {
        Assert.assertFalse(Ping.isPing(null, "Helsinki"))
        Assert.assertFalse(Ping.isPing("", "Helsinki"))
        Assert.assertFalse(Ping.isPing("   ", "Helsinki"))
        // Nothing equals nothing here: this is not the place to invent a match.
        Assert.assertFalse(Ping.isPing("", ""))
    }

    // -- and the places that ask, for both shapes --------------------------------------------------

    @Test
    fun aPingHasNoLanguageInEitherDirection() {
        Assert.assertFalse(TranslationDecision.hasLanguage("Matti: ", null))
        // The composer asks that same rule first, so a ping is sent rather than held or turned into
        // a prompt to retype.
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, ComposerGate.verdict("Matti: ", "fi", null, null, ON))
        // A plain name still does, so the ping rule has not quietly widened.
        Assert.assertTrue(TranslationDecision.hasLanguage("Matti", null))
    }

    @Test
    fun aBareNameHasNoLanguageInEitherDirection() {
        Assert.assertFalse(TranslationDecision.hasLanguage("Helsinki", "Helsinki"))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict("Helsinki", "fi", null, "Helsinki", ON))
        Assert.assertFalse(TranslationDecision.hasLanguage("Helsinki", " helsinki "))
        // And a different name in the same conversation still does: the composer holds it for
        // translation, and with no conversation language to translate into it says so.
        Assert.assertTrue(TranslationDecision.hasLanguage("Matti", "Helsinki"))
        Assert.assertEquals(
                ComposerGate.Verdict.UNKNOWN_LANGUAGE,
                ComposerGate.verdict("Matti", "fi", null, "Helsinki", ON))
    }

    @Test
    fun aPingIsNeverRequestableAndNeverCovered() {
        val candidate = TranslationDecision.Candidate()
        candidate.received = true
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = "Matti: "
        Assert.assertFalse(TranslationDecision.isEligible(candidate, ON))
        Assert.assertFalse(TranslationDecision.isRequestable(candidate, ON))
        Assert.assertFalse(
                "a ping renders as it arrived, not as a blur",
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "Matti: ", null, ON))
    }

    @Test
    fun aBareNameIsNeverRequestableAndNeverCovered() {
        val candidate = TranslationDecision.Candidate()
        candidate.received = true
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = "Helsinki"
        candidate.conversationName = "Helsinki"
        Assert.assertFalse(TranslationDecision.isEligible(candidate, ON))
        Assert.assertFalse(TranslationDecision.isRequestable(candidate, ON))
        // The two ways the bubble asks. A body that is the room's own name is drawn as it arrived:
        // one whole half, no cover.
        Assert.assertFalse(
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "Helsinki", "Helsinki", ON)
                        || BubbleHalves.of(
                                        "Helsinki",
                                        null,
                                        Message.TRANSLATION_NONE,
                                        Message.STATUS_RECEIVED,
                                        "Helsinki",
                                        ON)
                                .isDivided())
        // And the conversation's own name from *another* conversation is still covered.
        Assert.assertTrue(
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "Helsinki", "Tampere", ON))
    }

    @Test
    fun aPingPassesTheGateAndIsNotHeld() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, ComposerGate.verdict("Matti: ", "fi", "de", null, ON))
        Assert.assertEquals(
                "not even when the conversation's language is unknown",
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict("Matti: ", "fi", null, null, ON))
        Assert.assertFalse(HeldSend.needsTranslation("Matti: ", "fi", null, null, ON))
    }

    @Test
    fun aBareNamePassesTheGateAndIsNotHeld() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict("Helsinki", "fi", "de", "Helsinki", ON))
        Assert.assertEquals(
                "not even when the conversation's language is unknown",
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict("Helsinki", "fi", null, "Helsinki", ON))
        Assert.assertFalse(HeldSend.needsTranslation("Helsinki", "fi", null, "Helsinki", ON))
        Assert.assertTrue(
                "a different name in the same room is held and translated like any other word",
                HeldSend.needsTranslation("Matti", "fi", "de", "Helsinki", ON))
    }

    @Test
    fun aPingIsNeverALanguageReading() {
        val reading =
                ConversationLanguage.read("Matti: ", "mt", "fi", null, null)
        Assert.assertNull("no conversation language", reading.language)
        Assert.assertNull("and no message language either", reading.messageLanguage)
        Assert.assertFalse(reading.wasAlreadyIn("fi"))
    }

    @Test
    fun aBareNameIsNeverALanguageReading() {
        // "Helsinki" is exactly the shape the offline detector names a language for, so this is the
        // reading that must not happen: a room whose name is Helsinki is not thereby a language.
        val reading =
                ConversationLanguage.read("Helsinki", "mt", "fi", null, "Helsinki")
        Assert.assertNull("no conversation language", reading.language)
        Assert.assertNull("and no message language either", reading.messageLanguage)
        Assert.assertFalse(reading.wasAlreadyIn("fi"))
    }

    @Test
    fun aPingDoesNotVoteInTheArchiveSample() {
        Assert.assertNull(LanguageSample.readOne("Matti: ", "fi", null))
        val candidate = TranslationDecision.Candidate()
        candidate.received = true
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = "Matti: "
        Assert.assertFalse(LanguageSample.isEligible(candidate, ON))
    }

    @Test
    fun aBareNameDoesNotVoteInTheArchiveSample() {
        Assert.assertNull(LanguageSample.readOne("Helsinki", "fi", "Helsinki"))
        val candidate = TranslationDecision.Candidate()
        candidate.received = true
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = "Helsinki"
        candidate.conversationName = "Helsinki"
        Assert.assertFalse(LanguageSample.isEligible(candidate, ON))
    }
}
