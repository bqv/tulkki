package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The outgoing attempts kept in memory: what the ring records and what its one log line says.
 *
 * <p><strong>Why this cell exists.</strong> A held row stores one word - its failure cause - so
 * "why is this still here?" had no answer beyond that word, and the owner's short "ok" held on doubt
 * was indistinguishable from a call that failed. The record and its log line are the diagnosis: the
 * direction, the target, the answer's length, each reader's own code and confidence, the check's
 * outcome and the hold reason, with a re-ask linked to the attempt it re-asks.
 *
 * <p><strong>It also pins what may not be said.</strong> The line names readers and confidences and
 * never a word of either text; a message body must not reach this record, its rendering or the log
 * line. The readers are fakes, so the confidences are the test's own and the cell needs no model to
 * load.
 */
class OutgoingAttemptsTest {

    private lateinit var attempts: OutgoingAttempts

    /** The ring under test, smaller than the real one so a bound can be reached by hand. */
    @Before
    fun freshRing() {
        attempts = OutgoingAttempts(8)
    }

    @After
    fun restoreReaders() {
        LanguageCheck.useReaders(null)
    }

    /**
     * A reader with a name and a fixed reading, or `null` for one that says nothing. The label is
     * what the line draws, so the four fakes below stand in for the app's four readers.
     */
    private fun reader(label: String, code: String?, confidence: Double): LanguageCheck.Reader {
        return object : LanguageCheck.Reader {
            override fun read(text: String, candidates: Set<String>): LanguageCheck.Reading? {
                return if (code == null) null else LanguageCheck.Reading(code, confidence, false)
            }

            override fun label(): String = label
        }
    }

    // ---- (a) an echo: "ok" -> "ok" -----------------------------------------------------------

    /**
     * The owner's own case. "ok" is under the length where an unchanged answer is allowed to be
     * called an answer, so the check accepts it only on doubt - and the line says exactly that: the
     * direction, the answer's length, that no reader was reached, and the reason for the hold.
     */
    @Test
    fun anEchoIsRecordedAsAnEchoHeldOnDoubt() {
        LanguageCheck.useReaders(listOf(reader("a reader", "fi", 0.9)))
        val inspection = LanguageCheck.inspect("ok", "ok", "en", "fi", "fi")

        attempts.record(
                OutgoingAttempts.of(
                        "m-echo",
                        "fi",
                        "en",
                        2,
                        inspection,
                        HeldSend.HoldReason.DOUBT,
                        OutgoingAttempts.Request.NORMAL,
                        1000L))

        val kept = attempts.recent()
        assertEquals(1, kept.size)
        val attempt = kept.get(0)
        assertEquals(1000L, attempt.at)
        assertEquals("m-echo", attempt.messageUuid)
        assertEquals("fi", attempt.appLanguage)
        assertEquals("en", attempt.target)
        assertEquals(2, attempt.answerLength)
        assertEquals(OutgoingAttempts.Check.ECHO, attempt.outcome)
        assertEquals(HeldSend.HoldReason.DOUBT, attempt.reason)
        assertEquals(OutgoingAttempts.Request.NORMAL, attempt.request)
        assertTrue(
                "the check answered before reaching any reader: " + attempt.readers,
                attempt.readers.isEmpty())
        assertEquals(
                "the echo's whole line, with no reader and no text",
                "outgoing attempt; from the app language Finnish (fi); into the conversation's"
                        + " language English (en); answer length 2 characters; readers: not asked, the"
                        + " check answered first; check: echo; held: doubt; question: the ordinary"
                        + " question",
                attempt.logLine())
    }

    // ---- (b) a doubted answer ------------------------------------------------------------------

    /** The four readers the app runs, as fakes, with exactly the readings a doubt needs. */
    private fun installDoubtfulReaders() {
        LanguageCheck.useReaders(
                listOf(
                        reader("the shipped detector", "fi", 0.40),
                        reader("Lingua", null, 0.0),
                        // Below the check's floor, so it is shown but does not count as a vote.
                        reader("OpenNLP", "en", 0.20),
                        reader("the script reader", null, 0.0)))
    }

    /**
     * One reader against the target and nobody for it is doubt, not evidence: the answer is the
     * translation it probably is and the send path holds it for the owner's tap. Every reader is on
     * the record with its own code and confidence - including the two that said nothing and the one
     * whose confidence is below the floor - because that is the whole point of the diagnosis.
     */
    @Test
    fun aDoubtedAnswerRecordsEveryReadersCodeAndConfidence() {
        installDoubtfulReaders()
        val inspection = LanguageCheck.inspect("salaisuus", "geheim", "en", "fi", "fi")

        attempts.record(
                OutgoingAttempts.of(
                        "m-doubt",
                        "fi",
                        "en",
                        6,
                        inspection,
                        HeldSend.HoldReason.DOUBT,
                        OutgoingAttempts.Request.NORMAL,
                        2000L))

        val attempt = attempts.recent().get(0)
        assertEquals(OutgoingAttempts.Check.DOUBT, attempt.outcome)
        assertEquals(HeldSend.HoldReason.DOUBT, attempt.reason)
        assertEquals(4, attempt.readers.size)
        assertEquals("the shipped detector", attempt.readers.get(0).reader)
        assertEquals("fi", attempt.readers.get(0).reading!!.code)
        assertEquals(0.40, attempt.readers.get(0).reading!!.confidence, 0.0001)
        assertNull("Lingua said nothing", attempt.readers.get(1).reading)
        assertNull("the script reader said nothing", attempt.readers.get(3).reading)

        val line = attempt.logLine()
        assertTrue(
                "the direction is on the line: " + line,
                line.contains("from the app language Finnish (fi)"))
        assertTrue(
                "and so is the target: " + line,
                line.contains("into the conversation's language English (en)"))
        assertTrue(
                "the answer's length is on the line: " + line,
                line.contains("answer length 6 characters"))
        assertTrue(
                "the shipped detector's own code and confidence: " + line,
                line.contains("the shipped detector said Finnish (fi) with confidence 0.40"))
        assertTrue("the abstention is said: " + line, line.contains("Lingua said nothing"))
        assertTrue(
                "the below-floor reading is still shown: " + line,
                line.contains("OpenNLP said English (en) with confidence 0.20"))
        assertTrue(
                "the script reader's abstention is said: " + line,
                line.contains("the script reader said nothing"))
        assertTrue("the check's outcome is on the line: " + line, line.contains("check: doubt"))
        assertTrue("and the hold reason: " + line, line.contains("held: doubt"))
        assertFalse("not a word of the message: " + line, line.contains("salaisuus"))
        assertFalse("nor of the answer: " + line, line.contains("geheim"))
    }

    /**
     * The rendering a future surface may draw says the same facts in a sentence, names the readers
     * and their confidences, and still never a word of either text.
     */
    @Test
    fun theRenderingNamesReadersAndConfidencesWithoutTheWords() {
        installDoubtfulReaders()
        attempts.record(
                OutgoingAttempts.of(
                        "m-doubt",
                        "fi",
                        "en",
                        6,
                        LanguageCheck.inspect("salaisuus", "geheim", "en", "fi", "fi"),
                        HeldSend.HoldReason.DOUBT,
                        OutgoingAttempts.Request.NORMAL,
                        2000L))

        val rendered = attempts.describe("m-doubt")

        assertTrue("the target language is named: " + rendered, rendered.contains("target English (en)"))
        assertTrue(
                "the shipped detector's own code and confidence: " + rendered,
                rendered.contains("the shipped detector said Finnish (fi) with confidence 0.40"))
        assertTrue("the abstention is said: " + rendered, rendered.contains("Lingua said nothing"))
        assertTrue(
                "the check's verdict is said: " + rendered,
                rendered.contains("the check called it doubt"))
        assertTrue("held on doubt: " + rendered, rendered.contains("held: doubt"))
        assertFalse("not a word of the message: " + rendered, rendered.contains("salaisuus"))
        assertFalse("nor of the answer: " + rendered, rendered.contains("geheim"))
    }

    // ---- the re-ask is a linked second entry ---------------------------------------------------

    /**
     * The tap is a second attempt and is recorded as one: its own question, its own verdicts, and a
     * link to the attempt it re-asks. The line says so, which is what makes "did the re-ask change
     * anything?" answerable.
     */
    @Test
    fun aReAskIsLinkedToTheAttemptItReAsks() {
        installDoubtfulReaders()
        attempts.record(
                OutgoingAttempts.of(
                        "m-doubt",
                        "fi",
                        "en",
                        6,
                        LanguageCheck.inspect("salaisuus", "geheim", "en", "fi", "fi"),
                        HeldSend.HoldReason.DOUBT,
                        OutgoingAttempts.Request.NORMAL,
                        2000L))

        LanguageCheck.useReaders(
                listOf(
                        reader("the shipped detector", "fi", 0.60),
                        reader("Lingua", "de", 0.55),
                        reader("OpenNLP", null, 0.0),
                        reader("the script reader", null, 0.0)))
        val reAsk =
                OutgoingAttempts.of(
                        "m-doubt",
                        "fi",
                        "en",
                        6,
                        LanguageCheck.inspect("salaisuus", "geheim", "en", "fi", "fi"),
                        HeldSend.HoldReason.FAILED,
                        OutgoingAttempts.Request.RE_ASK,
                        3000L)
        attempts.record(reAsk)

        val own = attempts.forMessage("m-doubt")
        assertEquals(2, own.size)
        assertEquals(OutgoingAttempts.Request.NORMAL, own.get(0).request)
        assertEquals(OutgoingAttempts.Request.RE_ASK, own.get(1).request)
        assertEquals("the re-ask points at the attempt it re-asks", 2000L, own.get(1).reAskOf)
        assertEquals(OutgoingAttempts.Check.WRONG_LANGUAGE, own.get(1).outcome)
        assertEquals("the re-ask's own reason", HeldSend.HoldReason.FAILED, own.get(1).reason)
        assertTrue(
                "the re-ask's line points at the attempt it re-asks: " + reAsk.logLine(),
                reAsk.logLine().contains("re-ask of the attempt at 2000"))
        assertTrue(
                "and says which question it asked: " + reAsk.logLine(),
                reAsk.logLine().contains("the same question asked again with the app's own clause"))
        val rendered = attempts.describe("m-doubt")
        assertTrue(rendered.contains("last attempt (the ordinary question)"))
        assertTrue(rendered.contains("re-ask (the same question asked again with the app's own clause)"))
        assertEquals(
                "and a message with nothing kept renders nothing", "", attempts.describe("m-other"))
    }

    // ---- the ring is bounded, and the port is the seam -----------------------------------------

    /** A couple of dozen at most: the oldest attempt falls off, and the newest is the tail. */
    @Test
    fun theRingKeepsTheNewestAttemptsOnly() {
        for (i in 0 until 12) {
            attempts.record(
                    OutgoingAttempts.notAttempted(
                            "m-" + i,
                            "en",
                            "fi",
                            HeldSend.HoldReason.NO_KEY,
                            OutgoingAttempts.Request.NORMAL,
                            i.toLong()))
        }

        val kept = attempts.recent()
        assertEquals(8, kept.size)
        assertEquals("m-4", kept.get(0).messageUuid)
        assertEquals("m-11", kept.get(7).messageUuid)
        assertTrue("the oldest are gone", attempts.forMessage("m-0").isEmpty())
    }

    /**
     * A hold before any answer is recorded too, with no readers and the reason as the whole story.
     */
    @Test
    fun anAttemptThatNeverReachedTheCheckSaysSo() {
        attempts.record(
                OutgoingAttempts.notAttempted(
                        "m-key",
                        "en",
                        "fi",
                        HeldSend.HoldReason.NO_KEY,
                        OutgoingAttempts.Request.NORMAL,
                        500L))

        val line = attempts.recent().get(0).logLine()
        assertEquals(OutgoingAttempts.Check.NOT_ATTEMPTED, attempts.recent().get(0).outcome)
        assertTrue(
                "the line says no answer came back: " + line,
                line.contains("readers: no answer came back to read"))
        assertTrue("and why it is held: " + line, line.contains("held: no key"))
    }

    /**
     * The ring reaches a surface through the port the app already holds: the real record implements
     * it, and a port that does not (the send path's test doubles) answers an empty list rather than
     * breaking.
     */
    @Test
    fun theRecordExposesTheRingThroughThePort() {
        val record = TranslationActivity(EmptyStore())
        val attempt =
                OutgoingAttempts.of(
                        "m-echo",
                        "fi",
                        "en",
                        2,
                        LanguageCheck.inspect("ok", "ok", "en", "fi", "fi"),
                        HeldSend.HoldReason.DOUBT,
                        OutgoingAttempts.Request.NORMAL,
                        1000L)

        record.recordOutgoingAttempt(attempt)

        assertEquals(listOf(attempt), record.recentOutgoingAttempts())
        val bare =
                object : TranslationActivityPort {
                    override fun addTranslated(day: String?, messages: Int) {}

                    override fun recordFailure(
                            reason: HeldSend.HoldReason?,
                            detail: String?,
                            messageUuid: String?,
                            at: Long
                    ) {}
                }
        assertTrue("an untouched port keeps nothing", bare.recentOutgoingAttempts().isEmpty())
    }

    /** A record with nowhere to persist anything: the ring is the only thing this test reads. */
    private class EmptyStore : TranslationActivity.Store {
        override fun day(): String? {
            return null
        }

        override fun translated(): Int {
            return 0
        }

        override fun saveTranslated(day: String?, translated: Int) {}

        override fun failure(): TranslationActivity.Failure? {
            return null
        }

        override fun saveFailure(failure: TranslationActivity.Failure) {}

        override fun clearFailure() {}
    }
}
