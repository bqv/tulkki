package uk.xa0.tulkki.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The short outgoing message: an answer accepted only on doubt is not a failure, and the owner's tap
 * asks the model again differently.
 *
 * <p><strong>This is the cell whose absence let the bug ship.</strong> Every other cell pinned
 * {@code LanguageCheck}'s judgement or the send path's row states separately, so nothing said the
 * fact the two have to agree on: the check accepts "ok" -&gt; "ok" and "hi" without evidence, so the
 * send path may not file that hold under the evidence reason {@code FAILED}. It did, and the owner's
 * bar read "DeepSeek could not translate this message" about an answer DeepSeek had just produced.
 *
 * <p>The second bug is the tap: it ran the identical request, so an echoed answer came back verbatim
 * for ever. The tap now asks the ordinary question with the app's own re-ask clause appended, which
 * is what {@link OutgoingTranslation#requestForSend} builds and what this test watches on the wire.
 * {@code OutgoingTranslation}'s whole hold path needs a {@code Context}, a screen and a
 * {@code Message}, so the request builder and the reason mapper are the JVM-reachable facts; the
 * walk from {@code sendHeldNow} to them is pinned by the source scan in
 * {@code DoubtHoldConsultationTest}'s family where it can be.
 */
class ShortSendDoubtTest {

    /**
     * One short correct answer each way. The identity cases are the model handing the owner's own
     * words back under {@code MIN_ECHO_LENGTH}; the all-abstain cases are answers so short that no
     * reader has an opinion about them at all. Both are doubt, never evidence.
     */
    private val SHORT_ANSWERS = arrayOf(
        // input, answer, target, app, model, the doubt kind
        arrayOf("ok", "ok", "en", "fi", "fi", "NOTHING_TRANSLATED"),
        arrayOf("joo", "joo", "en", "fi", "fi", "NOTHING_TRANSLATED"),
        arrayOf("hei", "hei", "en", "fi", "fi", "NOTHING_TRANSLATED"),
        arrayOf("kiitos", "kiitos", "en", "fi", "fi", "NOTHING_TRANSLATED"),
        arrayOf("ok", "ok", "fi", "en", "en", "NOTHING_TRANSLATED"),
        arrayOf("joo", "joo", "fi", "en", "en", "NOTHING_TRANSLATED"),
        arrayOf("hei", "hei", "fi", "en", "en", "NOTHING_TRANSLATED"),
        arrayOf("kiitos", "kiitos", "fi", "en", "en", "NOTHING_TRANSLATED"),
        arrayOf("moi", "hi", "en", "fi", "fi", "DOUBTFUL_LANGUAGE"),
        arrayOf("ei", "no", "en", "fi", "fi", "DOUBTFUL_LANGUAGE"),
        arrayOf("no", "ei", "fi", "en", "en", "DOUBTFUL_LANGUAGE"),
    )

    /** No fakes: the four readers the app runs, which is the machine the bug escaped. */
    @Before
    fun useTheRealReaders() {
        LanguageCheck.useReaders(null)
    }

    @After
    fun restoreReaders() {
        LanguageCheck.useReaders(null)
    }

    private fun shortAnswer(probe: Array<String>): LanguageCheck.Report {
        return LanguageCheck.of(probe[0], probe[1], probe[2], probe[3], probe[4])
    }

    /**
     * The check never calls any of these a failure - and the send path's reason for holding one is
     * therefore not {@code FAILED}, which is the bug this test exists for.
     */
    @Test
    fun aShortAnswerAcceptedOnDoubtIsNeverReportedUnderFailed() {
        for (probe in SHORT_ANSWERS) {
            val report = shortAnswer(probe)
            val where = probe[0] + " -> " + probe[1] + " into " + probe[2] + ": " + report

            assertFalse("doubt is not evidence: " + where, report.failed())
            assertTrue("and it is still accepted only on doubt: " + where, report.acceptedOnDoubt())
            assertEquals(
                    "the kind is the check's own: " + where,
                    LanguageCheck.Doubt.valueOf(probe[5]),
                    report.doubt)

            val reason = OutgoingTranslation.holdReason(report)
            assertEquals("a doubt hold is filed as doubt: " + where, HeldSend.HoldReason.DOUBT, reason)
            assertNotEquals(
                    "and never as an evidence failure: " + where,
                    HeldSend.HoldReason.FAILED,
                    reason)
        }
    }

    /**
     * The two are distinguishable, and the doubt side is not clearable: {@code FailureCause} answers
     * {@code null} for {@code DOUBT}, exactly as it does for {@code FAILED}, so no received row's
     * automatic retry may claim a doubt hold (docs/MIGRATION.md item 17, two and six).
     */
    @Test
    fun doubtIsDistinguishableFromAnEvidenceRefusalAndIsNotACause() {
        val doubt = shortAnswer(arrayOf("ok", "ok", "en", "fi", "fi"))
        // A Greek answer to a Finnish target: the evidence refusal, on all four readers.
        val evidence =
                LanguageCheck.of(
                        "Wie geht es dir heute, mein Freund?",
                        "Καλημέρα, τι κάνεις σήμερα;",
                        "fi",
                        "fi",
                        "de")

        assertTrue("the premise: this answer really is refused: " + evidence, evidence.failed())
        assertEquals(HeldSend.HoldReason.FAILED, OutgoingTranslation.holdReason(evidence))
        assertEquals(HeldSend.HoldReason.DOUBT, OutgoingTranslation.holdReason(doubt))
        assertNotEquals(
                "a doubt hold must not look like an evidence refusal",
                OutgoingTranslation.holdReason(evidence),
                OutgoingTranslation.holdReason(doubt))
        assertNull(
                "no retry may clear a doubt hold",
                FailureCause.fromReason(HeldSend.HoldReason.DOUBT))
        assertNull(
                "and the evidence refusal already had no cause of its own",
                FailureCause.fromReason(HeldSend.HoldReason.FAILED))
    }

    // ---- the tap asks again differently, on the wire -----------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient

    @Before
    fun setUpWire() {
        TranslationSettings.inMemory()
        server = MockWebServer()
        server.start()
        client =
                DeepSeekClient(
                        OkHttpClient(),
                        "test-key",
                        server.url("/chat/completions").toString(),
                        "deepseek-flash")
    }

    @After
    fun tearDownWire() {
        server.shutdown()
        // The settings instance is process-wide on purpose; leave it as a fresh install.
        TranslationSettings.inMemory()
    }

    /**
     * The normal send is the owner's template rendered and nothing else; the tap is the same bytes
     * plus the app's clause. Asserted as a prefix, not merely by containment, because "the template
     * stays the instruction" is the decision that makes the clause safe to append.
     */
    @Test
    fun theTappedSendReAsksWhileTheNormalSendDoesNot() {
        val target = "en"
        val reviewLanguage = "fi"
        val rendered =
                DeepSeekClient.systemPrompt(DeepSeekClient.DEFAULT_SYSTEM_PROMPT, target)

        // The normal path, a lone message: no clause at all.
        enqueue("en", "ok")
        OutgoingTranslation.requestForSend(
                client, "joo", target, reviewLanguage, false, false)
        val normal = systemContent(server.takeRequest())
        assertEquals(
                "the normal path sends the owner's template and nothing else: " + normal,
                rendered,
                normal)
        assertFalse("and no re-ask clause: " + normal, normal.contains(DeepSeekClient.RE_ASK_CLAUSE))

        // The tap: the same template, byte for byte, with the clause appended after a blank line.
        enqueue("en", "ok")
        OutgoingTranslation.requestForSend(client, "joo", target, reviewLanguage, false, true)
        val tapped = systemContent(server.takeRequest())
        assertTrue(
                "the owner's template must stay a byte-for-byte prefix: " + tapped,
                tapped.startsWith(rendered))
        assertEquals(
                "and the app's own clause must be the only thing added: " + tapped,
                rendered + "\n\n" + DeepSeekClient.RE_ASK_CLAUSE,
                tapped)
        assertFalse(
                "a re-ask is not a batch, so it carries no batching envelope: " + tapped,
                tapped.contains(DeepSeekClient.BATCH_CLAUSE))

        // The normal path with notes asks a different question, and still no clause.
        enqueue("en", "ok")
        OutgoingTranslation.requestForSend(client, "joo", target, reviewLanguage, true, false)
        val withNotes = systemContent(server.takeRequest())
        assertEquals(
                "the notes ride the ordinary review prompt, not a re-ask: " + withNotes,
                DeepSeekClient.reviewSystemPrompt(
                        DeepSeekClient.DEFAULT_REVIEW_PROMPT, target, reviewLanguage),
                withNotes)
        assertFalse(
                "and no re-ask clause: " + withNotes,
                withNotes.contains(DeepSeekClient.RE_ASK_CLAUSE))
    }

    /** A chat completion whose content is the translation contract. */
    private fun enqueue(lang: String, text: String) {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)

        val message = JsonObject()
        message.addProperty("content", contract.toString())

        val choice = JsonObject()
        choice.add("message", message)

        val choices = com.google.gson.JsonArray()
        choices.add(choice)

        val usage = JsonObject()
        usage.addProperty("total_tokens", 12)

        val root = JsonObject()
        root.add("choices", choices)
        root.add("usage", usage)
        server.enqueue(MockResponse().setBody(root.toString()))
    }

    /** The system instruction of the one request that was made. */
    private fun systemContent(request: RecordedRequest): String {
        val root =
                JsonParser.parseString(request.body.readUtf8()).getAsJsonObject()
        return root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()
    }
}
