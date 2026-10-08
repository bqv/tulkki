package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-translation check: what fails, what is only doubt, and what the reason is allowed to say.
 *
 * <p>The judgement is pinned with readings a test wrote down, so the rules are visible without
 * loading a detector, and then exercised end to end on real text through the two readers that need
 * no models beyond the ones already shipped.
 */
class LanguageCheckTest {

    /** The readers the app runs on, minus the second library: two opinions are enough to test with. */
    private val READERS: List<LanguageCheck.Reader> =
            java.util.Collections.unmodifiableList(
                    listOf(LanguageReadings.shipped(), LanguageReadings.script()))

    @After
    fun tearDown() {
        LanguageCheck.useReaders(null)
    }

    private fun reading(code: String, confidence: Double): LanguageCheck.Reading {
        return LanguageCheck.Reading(code, confidence, false)
    }

    private fun script(code: String): LanguageCheck.Reading {
        return LanguageCheck.Reading(code, 1.0, true)
    }

    // ---- the judgement, on readings a test wrote down ----

    @Test
    fun theTargetConfirmedIsNotAFailure() {
        assertFalse(
                LanguageCheck.judge("fi", listOf(reading("fi", 0.42))).failed())
    }

    @Test
    fun twoReadersNamingAnotherLanguageIsAFailure() {
        val report =
                LanguageCheck.judge("fi", listOf(reading("de", 0.61), reading("de", 0.52)))

        assertTrue(report.failed())
        assertEquals(LanguageCheck.Outcome.WRONG_LANGUAGE, report.outcome)
        assertEquals("de", report.language)
    }

    @Test
    fun oneLoneDissentIsDoubtAndDoubtIsNotAFailure() {
        // The shipped detector is wrong about short messages often enough that a single reading
        // against the target must not cover a translation: covering costs the owner a tap.
        assertFalse(LanguageCheck.judge("fi", listOf(reading("de", 0.61))).failed())
    }

    @Test
    fun twoReadersAgainstTheTargetNeedNotAgreeOnWhatItIs() {
        // The second reader is asked about a handful of languages in play, so its answer means "not
        // the target, and nearer this one". Two readers against the target agree about the thing
        // that matters even when they name different languages.
        val report =
                LanguageCheck.judge("fi", listOf(reading("de", 0.61), reading("en", 0.55)))

        assertTrue(report.failed())
        assertEquals(LanguageCheck.Outcome.WRONG_LANGUAGE, report.outcome)
    }

    @Test
    fun aReaderThatConfirmsTheTargetOutranksTheOthersDissent() {
        // The veto that keeps the check off honest work: Finnish text that one reader calls Estonian
        // is still Finnish text when the other reader says so.
        assertFalse(
                LanguageCheck.judge("fi", listOf(reading("et", 0.55), reading("fi", 0.40)))
                        .failed())
    }

    @Test
    fun aReadingBelowTheFloorIsNotAVote() {
        assertFalse(
                LanguageCheck.judge("fi", listOf(reading("de", 0.29), reading("de", 0.10)))
                        .failed())
    }

    @Test
    fun aScriptReadingIsDecisiveOnItsOwn() {
        val report =
                LanguageCheck.judge("fi", listOf(script("ko"), reading("fi", 0.9)))

        assertTrue(report.failed())
        assertEquals(LanguageCheck.Outcome.WRONG_LANGUAGE, report.outcome)
        assertEquals("ko", report.language)
    }

    @Test
    fun anUnknownTargetIsNotAReasonToFailAnything() {
        assertFalse(LanguageCheck.judge(null, listOf(reading("de", 0.9))).failed())
        assertFalse(LanguageCheck.judge("und", listOf(reading("de", 0.9))).failed())
    }

    // ---- the two outcomes that need no detector at all ----

    @Test
    fun anEmptyAnswerIsAFailure() {
        LanguageCheck.useReaders(READERS)

        assertEquals(
                LanguageCheck.Outcome.NOTHING_CAME_BACK,
                LanguageCheck.of("Wie geht es dir?", "   ", "fi", "fi", "de").outcome)
        assertEquals(
                LanguageCheck.Outcome.NOTHING_CAME_BACK,
                LanguageCheck.of("Wie geht es dir?", null, "fi", "fi", "de").outcome)
    }

    @Test
    fun theTextHandedBackUnchangedIsAFailure() {
        LanguageCheck.useReaders(READERS)

        val report =
                LanguageCheck.of(
                        "Wie geht es dir heute?", "Wie geht es dir heute?", "fi", "fi", "de")

        assertEquals(LanguageCheck.Outcome.ECHOED, report.outcome)
        assertTrue(report.failed())
    }

    @Test
    fun anEchoOfATextThatWasAlreadyInTheTargetIsNotAFailure() {
        LanguageCheck.useReaders(READERS)

        // The message was Finnish and the target was Finnish: handing it back is the correct answer,
        // and the app's own filters normally keep this out of the check altogether.
        val report =
                LanguageCheck.of(
                        "Moi, mitä kuuluu sinulle tänään?", "Moi, mitä kuuluu sinulle tänään?", "fi", "fi", "fi")

        assertFalse(report.failed())
    }

    @Test
    fun aShortAnswerThatHappensToMatchIsNotAFailure() {
        LanguageCheck.useReaders(READERS)

        // "Super!" really is the Finnish for "Super!". A short text is exempt, so an honest
        // translation is never covered for being the same six characters.
        assertFalse(LanguageCheck.of("Super!", "Super!", "fi", "fi", "de").failed())
    }

    // ---- what the reason may say ----

    @Test
    fun aReasonNamesLanguagesAndNeverTheMessage() {
        LanguageCheck.useReaders(READERS)
        val input = "Wie geht es dir heute, mein Freund?"
        val answer = "Καλημέρα, τι κάνεις σήμερα;"

        val report = LanguageCheck.of(input, answer, "fi", "fi", "de")

        assertTrue("the Greek answer must be caught: " + report, report.failed())
        assertEquals(LanguageCheck.Outcome.WRONG_LANGUAGE, report.outcome)
        // The reason is stored as the failure's detail and shown on the failures screen, so it may
        // name languages and outcomes only - never a word of either text.
        for (word in arrayOf("geht", "heute", "Freund", "Καλημέρα", "κάνεις")) {
            assertFalse(
                    "the reason must not carry the message: " + report.because,
                    report.because.contains(word))
        }
        assertTrue(report.because, report.because.contains("Greek"))
        assertTrue(report.because, report.because.contains("Finnish"))
    }

    // ---- three readers, and each of them able to be wrong on its own ----

    @Test
    fun theReadersAreThreeIdentifiersAndTheScript() {
        LanguageCheck.useReaders(null)
        val readers = LanguageCheck.readers()

        // The shipped n-gram detector, Lingua, OpenNLP, and the writing system. Adding a fourth
        // identifier is a decision, not an accident, so the count is pinned here.
        assertEquals(4, readers.size)
    }

    @Test
    fun allThreeIdentifiersTogetherCatchAnAnswerInAnotherLanguage() {
        // End to end, through the real readers: no candidate list, no fakes. A Greek answer to a
        // Finnish target is caught by all three identifiers and by the script.
        LanguageCheck.useReaders(null)
        val report =
                LanguageCheck.of(
                        "Wie geht es dir heute, mein Freund?",
                        "Καλημέρα, τι κάνεις σήμερα;",
                        "fi",
                        "fi",
                        "de")

        assertTrue(report.failed())
        assertEquals(LanguageCheck.Outcome.WRONG_LANGUAGE, report.outcome)
        assertEquals("el", report.language)
    }

    @Test
    fun aReaderThatCannotRunIsAReaderWithNoOpinion() {
        // A second detector that cannot load is a missing opinion, never a covered message.
        LanguageCheck.useReaders(
                listOf(
                        LanguageCheck.Reader { _, _ ->
                            throw IllegalStateException("no models")
                        },
                        LanguageReadings.shipped()))

        val report =
                LanguageCheck.of("Hei, mitä sinulle kuuluu?", "Moi, mitä sinulle kuuluu?", "fi", "fi", "fi")

        assertFalse(report.failed())
    }

    // ---- the third kind: accepted on doubt, for the send path alone (docs/MIGRATION.md item 16) ----

    @Test
    fun aShortEchoIsAcceptedOnDoubtAndNotAFailure() {
        LanguageCheck.useReaders(READERS)

        // "Ok, nähdään" is under MIN_ECHO_LENGTH, so the check does not call it an echo: for what is
        // DRAWN it stays the translation it probably is. The send path is the one that is told.
        val report =
                LanguageCheck.of("Ok, nähdään", "Ok, nähdään", "fi", "fi", "de")

        assertFalse("display must not cover it", report.failed())
        assertTrue(report.acceptedOnDoubt())
        assertEquals(LanguageCheck.Doubt.NOTHING_TRANSLATED, report.doubt)
    }

    @Test
    fun aDoubtfulLanguageIsAcceptedOnDoubtAndNotAFailure() {
        // One reader against the target and no reader for it: "doubt is not evidence" for display,
        // and the send path's other doubt kind.
        val report =
                LanguageCheck.judge("fi", listOf(reading("de", 0.61)))

        assertFalse(report.failed())
        assertTrue(report.acceptedOnDoubt())
        assertEquals(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE, report.doubt)
    }

    /**
     * The short correct answer, over the real readers: the identity case (the model hands the input
     * back, under {@code MIN_ECHO_LENGTH}) and the all-abstain case (an answer so short that no
     * reader has an opinion about it) are both <em>doubt</em>, never evidence, in both directions.
     *
     * <p><strong>This is the cell whose absence let the bug ship.</strong> Every other cell here
     * pinned the judgement with readings a test wrote down, so nothing said what the four real
     * readers do to the short answers the owner actually sends - and the send path's
     * {@code OutgoingTranslation.holdOnDoubt} was free to file the doubt under the evidence reason
     * {@code FAILED}. The owner's bar then read "DeepSeek could not translate this message" about an
     * answer DeepSeek had just produced. The judgement was never wrong; the coverage was.
     */
    @Test
    fun shortCorrectAnswersAreDoubtAndNeverEvidenceOverTheRealReaders() {
        LanguageCheck.useReaders(null)

        // The identity case, under MIN_ECHO_LENGTH: "ok" -> "ok" and friends, each way.
        for (text in arrayOf("ok", "joo", "hei", "kiitos")) {
            val intoEnglish = LanguageCheck.of(text, text, "en", "fi", "fi")
            assertFalse("not evidence: " + intoEnglish, intoEnglish.failed())
            assertEquals(
                    "the input handed back is nothing translated: " + intoEnglish,
                    LanguageCheck.Doubt.NOTHING_TRANSLATED,
                    intoEnglish.doubt)
            val intoFinnish = LanguageCheck.of(text, text, "fi", "en", "en")
            assertFalse("not evidence: " + intoFinnish, intoFinnish.failed())
            assertEquals(
                    "and the same the other way: " + intoFinnish,
                    LanguageCheck.Doubt.NOTHING_TRANSLATED,
                    intoFinnish.doubt)
        }

        // The all-abstain case: no reader has an opinion about the short answer, so there is no
        // evidence either way - the send path's doubtful-language doubt, not a refusal.
        val abstaining = arrayOf(
            arrayOf("moi", "hi", "en", "fi", "fi"),
            arrayOf("ei", "no", "en", "fi", "fi"),
            arrayOf("no", "ei", "fi", "en", "en"),
        )
        for (probe in abstaining) {
            val report =
                    LanguageCheck.of(probe[0], probe[1], probe[2], probe[3], probe[4])
            assertFalse("not evidence: " + report, report.failed())
            assertEquals(
                    "no reader could vouch for the language, which is doubt: " + report,
                    LanguageCheck.Doubt.DOUBTFUL_LANGUAGE,
                    report.doubt)
        }
    }

    @Test
    fun theTwoDoubtKindsWantOppositeTaps() {
        // The asymmetry is in the types, so flattening it is a compile-visible edit and not a
        // sentence someone can tidy away.
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                LanguageCheck.Doubt.NOTHING_TRANSLATED.tap)
        assertEquals(
                LanguageCheck.Tap.SEND_AS_HELD,
                LanguageCheck.Doubt.DOUBTFUL_LANGUAGE.tap)
        assertNotEquals(
                LanguageCheck.Doubt.NOTHING_TRANSLATED.tap,
                LanguageCheck.Doubt.DOUBTFUL_LANGUAGE.tap)
    }

    @Test
    fun anEchoAndAnEmptyAnswerReTranslateRatherThanSendingTheOriginal() {
        LanguageCheck.useReaders(READERS)

        // The detected forms are not "probably fine": their tap buys a fresh answer, because sending
        // the held one would send the original.
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                LanguageCheck.of("Wie geht es dir heute?", "Wie geht es dir heute?", "fi", "fi", "de")
                        .tap())
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                LanguageCheck.of("Wie geht es dir heute?", "  ", "fi", "fi", "de").tap())
        // A clean answer and a refusal hold nothing, so they have no tap.
        assertNull(LanguageCheck.of("Wie geht es dir heute?", "Mitä sinulle kuuluu?", "fi", "fi", "de").tap())
        assertNull(LanguageCheck.judge("fi", listOf(reading("de", 0.61), reading("de", 0.52))).tap())
    }

    @Test
    fun aLongEnoughEchoIsStillAFailureAndNotADoubt() {
        LanguageCheck.useReaders(READERS)

        // At or above MIN_ECHO_LENGTH the echo is named as one, exactly as before this slice.
        val report =
                LanguageCheck.of("Wie geht es dir heute?", "Wie geht es dir heute?", "fi", "fi", "de")

        assertTrue(report.failed())
        assertEquals(LanguageCheck.Outcome.ECHOED, report.outcome)
        assertFalse(report.acceptedOnDoubt())
    }
}
