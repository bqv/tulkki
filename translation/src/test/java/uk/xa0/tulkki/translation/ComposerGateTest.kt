package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The gate is deliberately absolute, so the tests here are mostly about the one line it has to get
 * right: "another language" is refused, "no language at all" passes.
 *
 * <p>The one thing above that line is the interpreter itself: with it off the gate is not asked at
 * all and a confidently foreign draft passes, which the last cases here pin. Every other case runs
 * with the interpreter on, through the local {@link #verdict} shorthand - a test-local convenience,
 * never a production default: the interpreter is a required argument in {@code ComposerGate.verdict}
 * so a real call site cannot compile without deciding.
 */
class ComposerGateTest {

    private val FINNISH =
            "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."
    private val GERMAN =
            "Hallo, treffen wir uns morgen zum Kaffee? Ich habe Neuigkeiten."
    private val ENGLISH =
            "Hey, are we still meeting tomorrow for coffee? I have news."

    /** The interpreter on: app Finnish, study German, so neither side names English or NONE. */
    private val ON = Interpreter.of("fi", "de")

    /** The interpreter off: app Finnish with study Finnish, the same language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    /** The gate's verdict while the interpreter is on. */
    private fun verdict(
            draft: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?): ComposerGate.Verdict {
        return ComposerGate.verdict(
                draft, appLanguage, conversationLanguage, conversationName, ON)
    }

    @Test
    fun appLanguageGoesToARoomThatSpeaksItUnchanged() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, verdict(FINNISH, "fi", "fi", null))
    }

    @Test
    fun appLanguageIsTranslatedIntoAForeignRoom() {
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict(FINNISH, "fi", "de", null))
    }

    @Test
    fun foreignDraftIsRefusedEvenInAForeignRoom() {
        Assert.assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict(GERMAN, "fi", "de", null))
    }

    @Test
    fun foreignDraftIsRefusedInAnAppLanguageRoom() {
        Assert.assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict(ENGLISH, "fi", "fi", null))
    }

    @Test
    fun linkHasNoLanguageAndPasses() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                verdict("https://example.org/some/page?a=1", "fi", "de", null))
    }

    @Test
    fun linkPassesEvenWhenTheConversationLanguageIsUnknown() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                verdict("https://example.org/", "fi", null, null))
    }

    @Test
    fun verificationCodeAndNumberPass() {
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, verdict("482913", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, verdict("+1 555 0100", "fi", "de", null))
    }

    @Test
    fun emojiAndSignalsPass() {
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict("🎉🎉", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict(":-)", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, verdict("🎉👍🎉", "fi", "de", null))
    }

    @Test
    fun aShortWordTheDetectorCannotNameIsNotRefusedButIsTranslated() {
        // "ok" comes back unknown; unknown is not "another language", but it is still text the room
        // may not read, so it is translated rather than turned into a prompt.
        Assert.assertEquals(ComposerGate.Verdict.TRANSLATE, verdict("ok", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict("ok", "fi", "fi", null))
        Assert.assertEquals(ComposerGate.Verdict.TRANSLATE, verdict("ja", "fi", "de", null))
    }

    @Test
    fun aBareNameIsNotRefusedEvenThoughTheDetectorIsSureItIsForeign() {
        // The profiles read "Matti" as Maltese at 0.96 and "Joo" as Somali at 0.90. Refusing those
        // would make a name unsendable, which is the one failure the design forbids.
        Assert.assertNotEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict("Matti", "fi", "de", null))
        Assert.assertNotEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE, verdict("Joo", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("Matti", "fi", "de", null))
    }

    @Test
    fun aForeignPhraseIsRefused() {
        Assert.assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict("Danke schön", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict("Wie geht's?", "fi", "fi", null))
    }

    @Test
    fun aWeakGuessIsNotFinal() {
        // Below the confidence bar the draft is translated rather than refused, so a wrong guess can
        // never lock the composer - but nothing foreign leaves untranslated either.
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("Guten Morgen", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("Hello world", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("merci beaucoup", "fi", "de", null))
    }

    @Test
    fun aShortForeignWordIsTranslatedRatherThanRefused() {
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("Danke", "fi", "de", null))
        // A greeting the profiles have no opinion about is still held and translated: nothing
        // foreign is sent raw just because the detector could not name it.
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict("こんにちは", "fi", "de", null))
    }

    @Test
    fun emptyDraftHasNoLanguage() {
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict("", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict(null, "fi", "de", null))
    }

    @Test
    fun unknownConversationLanguageIsSaidOutLoudRatherThanGuessed() {
        Assert.assertEquals(
                ComposerGate.Verdict.UNKNOWN_LANGUAGE,
                verdict(FINNISH, "fi", null, null))
        Assert.assertEquals(
                ComposerGate.Verdict.UNKNOWN_LANGUAGE,
                verdict(FINNISH, "fi", TextLanguage.UNKNOWN, null))
        Assert.assertEquals(
                ComposerGate.Verdict.UNKNOWN_LANGUAGE, verdict(FINNISH, "fi", "  ", null))
    }

    @Test
    fun refusalBeatsTheUnknownLanguageQuestion() {
        // The draft is the thing the owner can fix right now; the room's language is the next step.
        Assert.assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict(GERMAN, "fi", null, null))
    }

    @Test
    fun proseIsHeldAndEverySignalIsSent() {
        // The rule itself is TranslationDecision.hasLanguage now, and HasLanguageTest is its table;
        // what this pins is that the composer's own first question is that one rule, so every signal
        // passes as it stands and a sentence is held for translation.
        Assert.assertEquals(
                ComposerGate.Verdict.TRANSLATE, verdict(FINNISH, "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                verdict("https://example.org/x", "fi", "de", null))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND,
                verdict("geo:60.17,24.94", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict("42", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict(":-)", "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict(null, "fi", "de", null))
        Assert.assertEquals(ComposerGate.Verdict.SEND, verdict("   ", "fi", "de", null))
    }

    @Test
    fun unknownLanguageIsRecognised() {
        Assert.assertTrue(ComposerGate.isUnknownLanguage(null))
        Assert.assertTrue(ComposerGate.isUnknownLanguage(""))
        Assert.assertTrue(ComposerGate.isUnknownLanguage(TextLanguage.UNKNOWN))
        Assert.assertTrue(ComposerGate.isUnknownLanguage(" UND "))
        Assert.assertFalse(ComposerGate.isUnknownLanguage("fi"))
    }

    @Test
    fun theAppLanguageIsNamed() {
        Assert.assertEquals("Finnish", ComposerGate.languageName("fi"))
        Assert.assertEquals("German", ComposerGate.languageName("de"))
        Assert.assertEquals("", ComposerGate.languageName(null))
    }

    // -- the model's answer that says the draft was already the app language -----------------------

    @Test
    fun aSuggestionThatIsTheDraftStandsTheGateDown() {
        Assert.assertTrue(
                ComposerGate.suggestionIsTheDraft("Moi, mitä kuuluu?", "Moi, mitä kuuluu?"))
    }

    @Test
    fun surroundingWhitespaceAloneIsNotADifference() {
        // Leading and trailing whitespace is not a language, so a model that pads the draft has
        // still handed the draft back. This is the whole of the tolerance that is left.
        Assert.assertTrue(
                "both sides padded",
                ComposerGate.suggestionIsTheDraft("Moi, mitä kuuluu?", "  Moi, mitä kuuluu?  "))
        Assert.assertTrue(ComposerGate.suggestionIsTheDraft("Hei!", "Hei!  "))
        Assert.assertTrue(ComposerGate.suggestionIsTheDraft("  Hei!", "Hei!"))
    }

    @Test
    fun aDifferenceOfCaseSpacingOrPunctuationIsNotTheDraft() {
        // The owner's rule (docs/MIGRATION.md "The interpreter off-switch" item 9): these are not evidence the draft was already the
        // app language, so the gate must not stand down for them - "show me the Finnish" still
        // translates. The old normalised comparison read all three as the same words.
        Assert.assertFalse("case", ComposerGate.suggestionIsTheDraft("Hei!", "hei!"))
        Assert.assertFalse("internal whitespace", ComposerGate.suggestionIsTheDraft("a  b", "a b"))
        Assert.assertFalse(
                "trailing punctuation",
                ComposerGate.suggestionIsTheDraft("Matti: hello", "Matti: hello."))
        Assert.assertFalse(
                "all of them at once",
                ComposerGate.suggestionIsTheDraft("Moi, mitä kuuluu?", "  moi,   mitä kuuluu?  "))
    }

    @Test
    fun aRealTranslationIsNotTheDraft() {
        Assert.assertFalse(
                ComposerGate.suggestionIsTheDraft(
                        "Guten Morgen, wie geht es dir?", "Hyvää huomenta, mitä kuuluu?"))
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft("Hello there", "Hei siellä"))
    }

    @Test
    fun emptinessIsNeverAConfirmation() {
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft("", ""))
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft("   ", "   "))
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft(null, null))
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft("Moi", null))
        Assert.assertFalse(ComposerGate.suggestionIsTheDraft(null, "Moi"))
    }

    @Test
    fun aConfidentLocalReadingDoesNotVetoTheConfirmation() {
        // The residual risk, accepted and pinned: the detector is sure this is German, and the model
        // echoing it still stands the gate down. The alternative is what the owner asked to be rid
        // of - a heuristic with a demonstrated confident failure mode making them retype.
        val draft = "Guten Morgen, wie geht es dir?"
        Assert.assertTrue(
                "the premise: the detector is confident about this",
                TextLanguage.detect(draft).isProbably("de", TextLanguage.TRUSTWORTHY_CONFIDENCE))
        Assert.assertEquals(
                "and it is refused while the model answers with Finnish",
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict(draft, "fi", "de", null))
        Assert.assertTrue(ComposerGate.suggestionIsTheDraft(draft, draft))
    }

    // -- the interpreter off: the gate is not asked ------------------------------------------------

    /**
     * The whole off state of the composer gate in one case: a draft the on-mode gate confidently
     * refuses - and which it would hold for translation in a foreign room - is sent as the owner
     * typed it. This is the "no composer gate" clause of the off-switch, and it is deliberately the
     * first thing {@code ComposerGate.verdict} answers, before the draft is looked at: a plain XMPP
     * client has no opinion about languages.
     */
    @Test
    fun theOffInterpreterSendsAConfidentlyForeignDraft() {
        // The premise, with the interpreter on: German prose into a German room is refused, and the
        // owner is asked to write the app language themselves.
        Assert.assertEquals(
                "the premise: the on-mode gate refuses this",
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                verdict(GERMAN, "fi", "de", null))
        Assert.assertEquals(
                "off: the same draft goes out as typed",
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict(GERMAN, "fi", "de", null, OFF))
        Assert.assertEquals(
                "off: and so does English, which the on-mode gate refuses too",
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict(ENGLISH, "fi", "fi", null, OFF))
    }

    /**
     * Off, every answer that is not {@code SEND} is gone: no hold for translation and no "the
     * conversation has no language yet". A plain client does not know or care, so the three shapes
     * the on-mode gate distinguishes all collapse to the one.
     */
    @Test
    fun theOffInterpreterNeverHoldsOrRefusesForAnyRoom() {
        for (room in arrayOf("de", "fi", null, TextLanguage.UNKNOWN)) {
            Assert.assertEquals(
                    "off, a foreign room: " + room,
                    ComposerGate.Verdict.SEND,
                    ComposerGate.verdict(GERMAN, "fi", room, null, OFF))
            Assert.assertEquals(
                    "off, the app language's own room: " + room,
                    ComposerGate.Verdict.SEND,
                    ComposerGate.verdict(FINNISH, "fi", room, null, OFF))
        }
        Assert.assertEquals(
                "off, with no app language chosen either",
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict(GERMAN, Interpreter.NONE, "de", null, OFF))
    }
}
