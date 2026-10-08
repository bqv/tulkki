package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The DeepSeek call, against a local server: no key, no network, no device. */
class DeepSeekClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient

    @Before
    fun setUp() {
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
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /** A well-formed chat completion whose content is the JSON contract. */
    private fun answer(lang: String?, text: String?, totalTokens: Int): String {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)
        return envelope(contract.toString(), totalTokens)
    }

    @Test
    fun translatesAndReportsTheLanguageItSaw() {
        enqueue(200, answer("de", "Hallo, wie geht es dir?", 142))

        val result = client.translate("Hei, mitä kuuluu?", "fi")

        assertEquals("de", result.detectedLanguage)
        assertEquals("Hallo, wie geht es dir?", result.text)
        assertEquals(142, result.totalTokens)
        assertFalse(result.alreadyInTarget("fi"))
    }

    @Test
    fun aMessageAlreadyInTheTargetComesBackUnchanged() {
        enqueue(200, answer("fi", "Moi, mitä kuuluu?", 90))

        val result = client.translate("Moi, mitä kuuluu?", "fi")

        assertTrue(result.alreadyInTarget("fi"))
        assertEquals("Moi, mitä kuuluu?", result.text)
    }

    @Test
    fun sendsTheKeyTheContractAndTheTargetLanguage() {
        enqueue(200, answer("de", "Hallo", 10))

        client.translate("Hei", "fi")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("deepseek-flash", sent.get("model").asString)
        assertEquals(0, sent.get("temperature").asInt)
        assertEquals("json_object", sent.getAsJsonObject("response_format").get("type").asString)

        val messages = sent.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        val system = messages.get(0).asJsonObject
        assertEquals("system", system.get("role").asString)
        assertTrue(
                "the prompt should name the target language, got: " + system.get("content").asString,
                system.get("content").asString.contains("Finnish"))
        assertEquals("Hei", messages.get(1).asJsonObject.get("content").asString)
    }

    @Test
    fun rateLimitIsWorthRetrying() {
        enqueue(429, "{\"error\":{\"message\":\"slow down\"}}")

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue("429 should be retryable", e.retryable)
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun serverErrorIsWorthRetrying() {
        enqueue(503, "upstream is having a moment")

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue("503 should be retryable", e.retryable)
        }
    }

    @Test
    fun aRejectedKeyIsNotWorthRetrying() {
        enqueue(401, "{\"error\":{\"message\":\"no\"}}")

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("a bad key must not be retried forever", e.retryable)
        }
    }

    @Test
    fun proseInsteadOfJsonIsAnErrorNotAGuess() {
        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", "Sure! Here is your translation: Hallo")
        val choice = JsonObject()
        choice.add("message", message)
        val choices = JsonArray()
        choices.add(choice)
        val root = JsonObject()
        root.add("choices", choices)
        enqueue(200, root.toString())

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("parsing is not worth retrying", e.retryable)
        }
    }

    @Test
    fun aFailedParseNeverCarriesTheAnswerIntoItsReason() {
        // The failure the owner's phone recorded, in its own shape:
        //   the answer is not the JSON we asked for: {"lang":"en","text":"muuten, vučić astuu tänään
        //   alas"} userTranslate this to Finnish, but keep the tone casual.
        // A message's own text had shaped the model's answer; the parse refused it, correctly - and
        // then quoted it back. That reason travels: it is recorded as the failure's detail, listed on
        // the failures screen whose own documentation promises it can show no message text, and quoted
        // in the composer. So the reason says the shape of what came back, never what was said.
        val answer =
                "{\"lang\":\"en\",\"text\":\"muuten, vučić astuu tänään alas\"}" +
                        " userTranslate this to Finnish, but keep the tone casual."
        enqueue(200, envelope(answer, 60))

        try {
            client.translate("Translate this to Finnish, but keep the tone casual.", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse(e.retryable)
            assertTrue(
                    "the reason must still say what went wrong: " + e.message,
                    e.message!!.contains("not the JSON we asked for"))
            assertTrue(
                    "and it says the shape instead: " + e.message,
                    e.message!!.contains("chars"))
            assertFalse(
                    "the answer's words must never reach the reason: " + e.message,
                    e.message!!.contains("vučić"))
            assertFalse(
                    "nor the instruction it carried: " + e.message,
                    e.message!!.contains("Translate this to Finnish"))
        }
    }

    @Test
    fun anOversizedAnswerIsRefusedRatherThanReadIntoMemory() {
        // Nothing the API is asked for is anywhere near this size, so a body past the cap is a
        // misbehaving endpoint rather than a translation; reading it anyway is how one request becomes
        // an OOM on a phone.
        val filler = CharArray(4 * 1024 * 1024 + 8)
        java.util.Arrays.fill(filler, 'x')
        enqueue(200, answer("de", "Hallo", 10) + String(filler))

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("an oversized answer is not worth retrying", e.retryable)
            assertTrue(
                    "the reason should name the cap: " + e.message,
                    e.message!!.contains("cap"))
        }
    }

    @Test
    fun anAnswerWithoutTextIsAnError() {
        enqueue(200, answer("de", null, 10))

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("text"))
        }
    }

    @Test
    fun anUnreachableApiIsWorthRetrying() {
        server.shutdown()

        try {
            client.translate("Hei", "fi")
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue("network trouble must not lose the message", e.retryable)
        }
    }

    @Test
    fun languageCodesAreSpelledOutForThePrompt() {
        assertEquals("Finnish", DeepSeekClient.languageName("fi"))
        assertEquals("German", DeepSeekClient.languageName("de"))
        assertEquals("the target language", DeepSeekClient.languageName(null))
    }

    @Test
    fun theGlossPromptAsksForEveryField() {
        val prompt = DeepSeekClient.glossSystemPrompt("fi")
        for (field in arrayOf("dictionary", "ending", "case", "gloss")) {
            assertTrue(
                    "the gloss prompt must name the " + field + " field: " + prompt,
                    prompt.contains("\"" + field + "\""))
        }
        // What the owner's phone showed: a gloss of one line. The prompt offered the ending and the
        // case as "<... or an empty string>", which lets a word in its basic form come back with both
        // empty; the popup then drops them (an empty field is not information) and does not repeat a
        // dictionary form that is the tapped word, so one line is what that answer renders as. The
        // offer is the defect, and no field may be allowed to be empty again.
        assertFalse(
                "no field may be allowed to come back empty: " + prompt,
                prompt.contains("or an empty string"))
        assertTrue(
                "the prompt must say that every field carries a value: " + prompt,
                prompt.contains("Every field carries a value"))
    }

    @Test
    fun theGlossRequestCarriesTheSentenceTheWordWasTappedIn() {
        // The word alone is ambiguous - Finnish "kuusi" is a spruce and a six - and the sentence is
        // the only thing that tells the model which one is meant. It rides in the user's message as
        // data, labelled, so the instruction can say what to do with it.
        enqueue(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"dictionary\\\":\\\"kuusi\\\","
                        + "\\\"ending\\\":\\\"no ending\\\",\\\"case\\\":\\\"nominative\\\","
                        + "\\\"gloss\\\":\\\"spruce\\\"}\"}}],\"usage\":{\"total_tokens\":40}}")

        client.gloss("kuusi", "en", "Istutin kuusen pihalle.")

        val sent = userContent(server.takeRequest())
        assertTrue("the word must be named as the word: " + sent, sent.startsWith("word: kuusi"))
        assertTrue(
                "the sentence must ride with it: " + sent,
                sent.contains("sentence: Istutin kuusen pihalle."))
    }

    @Test
    fun theWordIsAlwaysLabelledAndTheSentenceIsOptional() {
        // The instruction is written for the labelled shape, so the word-only message is labelled
        // too; the sentence is folded, because the same sentence typed two ways is one question.
        assertEquals("word: talo", DeepSeekClient.glossMessage("talo", null))
        assertEquals("word: talo", DeepSeekClient.glossMessage("  talo  ", "   "))
        assertEquals(
                "word: talo\nsentence: Ostin talon.",
                DeepSeekClient.glossMessage("talo", "  Ostin   talon.  "))
    }

    // ---- supersession: a tap that moves on stops the request it left behind ----

    @Test
    fun aCancelledGlossStopsWaitingOnTheSocket() {
        // Cancelling has to reach the socket, because a request that is merely ignored still runs to
        // completion and is paid for. The server sits on the body for longer than a cancel takes and
        // shorter than the client's own read timeout, so there are exactly two ways this can end:
        // the cancel, or an answer - and the answer would have to be paid for.
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(answer("de", "Hallo", 10))
                        .setBodyDelay(2, TimeUnit.SECONDS))

        val out = AtomicReference<Call>()
        val bought = AtomicReference<DeepSeekClient.GlossAnswer>()
        val failure = AtomicReference<DeepSeekClient.TranslationException>()
        val lookup =
                Thread {
                    try {
                        bought.set(client.gloss("kuusi", "en", null, out::set))
                    } catch (e: DeepSeekClient.TranslationException) {
                        failure.set(e)
                    }
                }
        lookup.start()
        // The request is out and the answer is being held open: the call is cancellable in flight,
        // which is the case that matters - a cancel before execute would prove much less.
        server.takeRequest()
        assertNotNull("the call must be handed over before it goes out", out.get())

        out.get().cancel()
        lookup.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse("the call must end, not wait the body delay out", lookup.isAlive())
        assertNull("a cancelled call is not an answer", bought.get())
        assertNotNull("and it fails the ordinary way", failure.get())
    }

    @Test
    fun aGlossCancelledBeforeItIsSentNeverReachesTheServer() {
        // The window a supersession usually lands in: the worker is still reading the cache when the
        // next word is tapped, so the cancellation arrives before the call is executed. The request
        // must not be sent at all, which is why the handle is handed over before execute().
        enqueue(200, answer("de", "Hallo", 10))

        try {
            client.gloss("kuusi", "en", null, Call::cancel)
            fail("a cancelled call must not answer")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue("a cancelled call is not an answer", e.retryable)
        }
        assertEquals("the request must never have been sent", 0, server.requestCount)
    }

    @Test
    fun aGlossWithNoWatcherStillWorks() {
        // Every other caller keeps the old shape: the widened gloss call is an addition, not a
        // replacement, and a null watcher is the old behaviour exactly.
        enqueue(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"dictionary\\\":\\\"kuusi\\\","
                        + "\\\"ending\\\":\\\"no ending\\\",\\\"case\\\":\\\"nominative\\\","
                        + "\\\"gloss\\\":\\\"spruce\\\"}\"}}],\"usage\":{\"total_tokens\":40}}")

        val answer = client.gloss("kuusi", "en", null, null)

        assertEquals(40, answer.totalTokens)
        assertEquals("spruce", answer.gloss.meaning())
    }

    /** The user's message of the one request that was made. */
    private fun userContent(request: RecordedRequest): String {
        val root =
                JsonParser.parseString(request.body.readUtf8()).asJsonObject
        return root.getAsJsonArray("messages").get(1).asJsonObject.get("content").asString
    }

    // ---- the notes on the owner's own wording: one call, two answers ----

    /**
     * The outbound answer: the translation contract plus the notes. Each note is handed in as raw
     * JSON so a test can break one on purpose - a wrong type, a paraphrase for a slice, a note with
     * no slices at all.
     */
    private fun answerWithNotes(
            lang: String?,
            text: String?,
            totalTokens: Int,
            vararg notes: String
    ): String {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)
        val list = JsonArray()
        for (note in notes) {
            list.add(JsonParser.parseString(note))
        }
        contract.add("notes", list)
        return envelope(contract.toString(), totalTokens)
    }

    /** One note object: the remark, and the stretches of the draft it is about. */
    private fun note(remark: String, vararg flagged: String): String {
        val obj = JsonObject()
        obj.addProperty("note", remark)
        val slices = JsonArray()
        for (slice in flagged) {
            slices.add(slice)
        }
        obj.add("flagged", slices)
        return obj.toString()
    }

    /** One note object with no {@code flagged} field at all. */
    private fun noteWithoutStretch(remark: String): String {
        val obj = JsonObject()
        obj.addProperty("note", remark)
        return obj.toString()
    }

    /** What {@link #answer} builds, with the contract handed in. */
    private fun envelope(content: String, totalTokens: Int): String {
        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", content)

        val choice = JsonObject()
        choice.addProperty("index", 0)
        choice.add("message", message)

        val choices = JsonArray()
        choices.add(choice)

        val usage = JsonObject()
        usage.addProperty("total_tokens", totalTokens)

        val root = JsonObject()
        root.add("choices", choices)
        root.add("usage", usage)
        return root.toString()
    }

    @Test
    fun theNotesRideAlongInTheCallThatTranslatesTheDraft() {
        enqueue(
                200,
                answerWithNotes(
                        "de",
                        "[de] Mina opin suomea",
                        160,
                        note("the inessive ending is -ssa", "suomea"),
                        note("'opin' is the right verb here", "opin")))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertEquals(160, result.totalTokens)
        assertEquals(
                java.util.Arrays.asList(
                        "the inessive ending is -ssa", "'opin' is the right verb here"),
                result.review.notes())
        assertEquals(
                java.util.Arrays.asList(
                        Review.Note.of(
                                "the inessive ending is -ssa",
                                java.util.Collections.singletonList("suomea")),
                        Review.Note.of(
                                "'opin' is the right verb here",
                                java.util.Collections.singletonList("opin"))),
                result.review.items())

        // One request, and that is the whole design: the draft was going to DeepSeek anyway, so the
        // notes cost a sentence of prompt on a call that was already being made. A second request
        // would be a second purchase for the same words, and a failure here means somebody has
        // reintroduced one.
        assertEquals("the notes must not cost a second request", 1, server.requestCount)

        val request = server.takeRequest()
        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val messages = sent.getAsJsonArray("messages")
        val prompt = messages.get(0).asJsonObject.get("content").asString
        assertTrue("the prompt must ask for the notes: " + prompt, prompt.contains("\"notes\""))
        assertTrue("the prompt must name the translation's language: " + prompt, prompt.contains("German"))
        assertTrue(
                "the prompt must name the language the notes are written in: " + prompt,
                prompt.contains("English"))
        val draft = messages.get(1).asJsonObject.get("content").asString
        assertEquals("the model is asked about the draft itself", "Mina opin suomea", draft)
        // The whole point of the slice: it is a stretch of the draft, so the container can find it
        // there with indexOf and underline it in place. Nothing else in the answer names a position.
        for (parsed in result.review.items()) {
            for (slice in parsed.flagged()) {
                assertTrue(
                        "a flagged slice must be findable in the draft: " + slice,
                        draft.indexOf(slice) >= 0)
            }
        }
    }

    @Test
    fun aNoteWithoutAStretchStillArrivesAsANote() {
        // The remark is the note and the stretch is a pointer into the draft, so a model that answers
        // the remark and forgets the pointer still gets its remark read. Only the underline is lost.
        enqueue(
                200,
                answerWithNotes(
                        "de",
                        "[de] Mina opin suomea",
                        150,
                        noteWithoutStretch("word order is fine here")))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertTrue("a note without an underline is still a note", result.review.isPresent())
        assertEquals(
                java.util.Collections.singletonList("word order is fine here"),
                result.review.notes())
        assertTrue(result.review.items().get(0).flagged().isEmpty())
    }

    @Test
    fun aSliceThatIsNotInTheDraftSurvivesIntoTheResult() {
        // The model paraphrased. Only the UI knows the draft, so only the UI may decide that this
        // cannot be underlined; the value has to arrive with the remark intact either way.
        enqueue(
                200,
                answerWithNotes(
                        "de",
                        "[de] Mina opin suomea",
                        150,
                        note("the verb ending", "opin suomea!")))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals(
                java.util.Collections.singletonList("opin suomea!"),
                result.review.items().get(0).flagged())
        assertEquals("the verb ending", result.review.notes().get(0))
        assertEquals(-1, "Mina opin suomea".indexOf("opin suomea!"))
    }

    @Test
    fun aMalformedStretchIsAnAbsentReviewNotAFailedTranslation() {
        // The unchanged direction: a wrong type in the notes is commentary that did not arrive, never
        // a message that is held. The translation is what was paid for and it still stands.
        enqueue(
                200,
                envelope(
                        "{\"lang\":\"de\",\"text\":\"[de] Mina opin suomea\","
                                + "\"notes\":[{\"note\":\"your Finnish is good\","
                                + "\"flagged\":\"suomea\"}]}",
                        150))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertFalse(
                "a wrong type in the reviewer's answer must not blank the translation",
                result.review.isPresent())
    }

    @Test
    fun anOldShapeAnswerOfBareStringsIsAnAbsentReview() {
        // The prompt changed, so an answer to the old one is not half of an answer to the new one: a
        // list of strings carries no positions, and reading it as notes without underlines would hide
        // that the model is answering a contract that no longer exists.
        enqueue(
                200,
                envelope(
                        "{\"lang\":\"de\",\"text\":\"[de] Mina opin suomea\","
                                + "\"notes\":[\"the inessive ending is -ssa\"]}",
                        150))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertFalse(result.review.isPresent())
    }

    @Test
    fun anAnswerWithoutNotesStillTranslates() {
        // json_object mode is a request, not a guarantee, and the notes are commentary: the model
        // answering the translation and forgetting the review must not cost the owner their message.
        enqueue(200, answer("de", "[de] Mina opin suomea", 150))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertFalse(result.review.isPresent())
        assertTrue(result.review.notes().isEmpty())
        assertTrue(result.review.items().isEmpty())
    }

    @Test
    fun malformedNotesAreAnAbsentReviewNotAFailedTranslation() {
        enqueue(
                200,
                envelope(
                        "{\"lang\":\"de\",\"text\":\"[de] Mina opin suomea\","
                                + "\"notes\":\"your Finnish is good\"}",
                        150))

        val result =
                client.translateWithReview("Mina opin suomea", "de", "en")

        assertEquals("[de] Mina opin suomea", result.text)
        assertFalse("a chatty reviewer must not blank the translation", result.review.isPresent())
    }

    @Test
    fun theTranslationCallNeverAsksForNotes() {
        // The receive path and the composer's suggestion use this call, and their text is not the
        // owner's own prose to be reviewed. The absence of the field in the prompt is what keeps the
        // notes input-side-only as a property of the code rather than of the model's goodwill.
        enqueue(200, answer("de", "[de] Hallo", 120))

        client.translate("Hallo", "de")

        val request = server.takeRequest()
        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val prompt =
                sent.getAsJsonArray("messages").get(0).asJsonObject.get("content").asString
        assertFalse(
                "only the send path may ask for notes: " + prompt, prompt.contains("notes"))
    }

    @Test
    fun anAnswerWithNotesIsStillACompleteTranslationForThePlainCall() {
        // The same body served to a call that did not ask: the extra field is ignored rather than
        // read, so a received message can never pick up notes that were never requested for it.
        enqueue(
                200,
                answerWithNotes(
                        "fi", "Moi", 100, note("a note about somebody else's words", "Moi")))

        val result = client.translate("Moi", "fi")

        assertEquals("Moi", result.text)
        assertFalse(result.review.isPresent())
    }

    @Test
    fun theReviewPromptKeepsTheTranslationContract() {
        val prompt = DeepSeekClient.reviewSystemPrompt("de", "en")

        assertTrue("the translation is still the message: " + prompt, prompt.contains("\"text\""))
        assertTrue(
                "nothing may be added to the translation: " + prompt,
                prompt.contains("In \"text\" never explain, never omit, never add anything"))
        assertTrue(
                "the notes are the input's own wording, not the translation's: " + prompt,
                prompt.contains("never remark on the translation"))
        assertTrue(
                "an empty list is the answer when there is nothing to say: " + prompt,
                prompt.contains("an empty list when the wording reads as it should"))
        assertTrue(
                "the notes are only for wording that is wrong or looks wrong: " + prompt,
                prompt.contains("only for wording that is wrong or looks wrong"))
        assertFalse(
                "a description of the language is not a correction of it: " + prompt,
                prompt.contains("its register"))
        assertTrue(
                "the contract's note count must not drift from the parser's: " + prompt,
                prompt.contains("at most " + Review.MAX_NOTES + " short remarks"))
    }

    @Test
    fun theReviewPromptAsksForTheSliceOfTheInputEachNoteIsAbout() {
        // A list of bare remarks carries no positions, so without this field there is nothing the
        // container could underline. The demand is a shape plus one sentence, and both halves are
        // pinned here: the field it names, that the copy is verbatim, and the count the parser will
        // accept before it calls the answer unusable.
        val prompt = DeepSeekClient.reviewSystemPrompt("de", "en")

        assertTrue(
                "the shape must carry the flagged field: " + prompt,
                prompt.contains("\"" + ReviewParser.FIELD_FLAGGED + "\""))
        assertTrue(
                "a note is an object, not a bare string: " + prompt,
                prompt.contains("{\"" + ReviewParser.FIELD_NOTE + "\":"))
        assertTrue(
                "the slice must be asked for as a verbatim copy: " + prompt,
                prompt.contains("a verbatim copy of the input's own words the note is about"))
        assertTrue(
                "the slice must be asked for with its case intact: " + prompt,
                prompt.contains("same characters, same case"))
        assertTrue(
                "the slice must be asked for so it can be found again: " + prompt,
                prompt.contains("so it can be found in the input again"))
        assertTrue(
                "the slice count must not drift from the parser's: " + prompt,
                prompt.contains("at most " + Review.MAX_FLAGGED + " per note"))
        assertTrue(
                "a note about the whole input names no slice: " + prompt,
                prompt.contains("is empty for a note about the whole input"))
    }

    @Test
    fun theNotesCostOneSentenceOnACallThatWasAlreadyBeingMade() {
        // Every word of the instruction is paid for on every outgoing message, so the delta is worth
        // a budget rather than a shrug. Measured from the compiled prompt: the review instruction
        // added 460 characters when the notes were bare remarks (plain 319 -> 779 for German/English),
        // and the flagged-slice demand - the note objects in the shape plus one sentence - took it to
        // 757 (319 -> 1076; the same 757 for Finnish), rather more than half of it the sentence. The
        // owner's narrowing of the notes to wording that is wrong or looks wrong then took it to 852
        // the first draft of that rule cost 260 characters and read like a paragraph of its own,
        // which is why the wording here is as short as the rule allows. That is roughly 215 tokens of
        // English, on a call the owner was already making, counted by the same counter as the
        // translation; the notes themselves are output tokens on top. (The narrowing itself is 95 of
        // the 852 - the first, wordier draft of it cost 260, which is what the budget caught.) The exact figure is
        // `usage.total_tokens` on the live call, which the usage screen shows; this test only refuses
        // to let the instruction quietly grow.
        //
        // The figure is now 953, and the budget was raised from 900 to 1000 deliberately rather than
        // trimmed around: the owner's rule that the notes must not punish puhekieli cost 101
        // characters, which is the "spoken or colloquial wording is not a mistake" sentence in the
        // middle of the notes clause. That sentence is the cheapest form of a rule the model will not
        // apply on its own - a model asked to correct a learner's Finnish reliably "fixes" mä into
        // minä unless it is told that a form which fits a chat message is not a mistake - so paying a
        // hundred characters to stop a card that is wrong every time the owner writes the way people
        // actually write is the trade. The headroom is 47 characters: a rephrasing of the same rules
        // fits, another rule does not, and the next raise is a decision like this one.
        //
        // The batch clause is not measured here and must not be: it is the app's own envelope text,
        // appended only to a request that carries several messages, so it is not part of the delta
        // between the one-message instruction and the review one - and the shipped template it is
        // appended to is unchanged, which is why the figure below did not move when batching landed.
        val plain = DeepSeekClient.systemPrompt("de").length
        val withReview = DeepSeekClient.reviewSystemPrompt("de", "en").length

        assertTrue("the review prompt must still be a prompt", withReview > plain)
        assertTrue(
                "the review instruction has grown past its budget (was 953 characters): "
                        + (withReview - plain),
                withReview - plain < 1000)
    }

    @Test
    fun theNotesDoNotPunishColloquialWording() {
        // The owner writes Finnish as people speak it, and the model's default is to treat a spoken
        // form as a mistake to repair - mä for minä, oon for olen, a dropped final vowel. A note like
        // that is a description of the language rather than a correction of it, which is the same
        // reason the owner narrowed the notes in the first place, so the rule is pinned here rather
        // than left to a wording that could lose it in the next shortening.
        val prompt = DeepSeekClient.reviewSystemPrompt("de", "en")

        assertTrue(
                "spoken wording must not be a note: " + prompt,
                prompt.contains("Spoken or colloquial wording is not a mistake"))
        assertTrue(
                "the reason matters as much as the rule: " + prompt,
                prompt.contains("a form that fits a chat message must never be a note"))
        assertFalse(
                "a register the owner chose is not a defect to report: " + prompt,
                prompt.contains("its register"))
    }

    // ---- a burst under one instruction: the batch call ----

    /** The content of a batch answer: its items, handed in as raw JSON so a test can break one. */
    private fun batchContent(vararg items: String): String {
        val builder = StringBuilder("{\"items\":[")
        for (i in 0 until items.size) {
            if (i > 0) {
                builder.append(',')
            }
            builder.append(items[i])
        }
        return builder.append("]}").toString()
    }

    /** One item of a batch answer. */
    private fun batchItem(id: Int, lang: String?, text: String?): String {
        val item = JsonObject()
        item.addProperty("id", id)
        item.addProperty("lang", lang)
        item.addProperty("text", text)
        return item.toString()
    }

    private fun sent(id: Int, text: String?): DeepSeekClient.BatchRequest {
        return DeepSeekClient.BatchRequest(id, text)
    }

    @Test
    fun aBatchAnswerIsReadItemByItem() {
        enqueue(
                200,
                envelope(
                        batchContent(batchItem(1, "de", "Hallo"), batchItem(2, "fi", "Moi")),
                        300))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei"), sent(2, "Moi")))

        assertEquals(2, result.items.size)
        assertEquals(1, result.items.get(0).id)
        assertEquals("de", result.items.get(0).detectedLanguage)
        assertEquals("Hallo", result.items.get(0).text)
        assertEquals(2, result.items.get(1).id)
        assertEquals("fi", result.items.get(1).detectedLanguage)
        assertEquals("Moi", result.items.get(1).text)
        assertEquals(300, result.totalTokens)
    }

    @Test
    fun anItemTheBatchAnswerOmitsIsSimplyAbsent() {
        // The parser reports what the answer carried and invents nothing: deciding what a missing
        // item means is a per-item decision, and it belongs to the caller.
        enqueue(200, envelope(batchContent(batchItem(1, "de", "Hallo")), 200))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei"), sent(2, "Moi")))

        assertEquals(1, result.items.size)
        assertEquals(1, result.items.get(0).id)
        assertEquals(200, result.totalTokens)
    }

    @Test
    fun anIdTheRequestNeverSentIsKeptForTheCallerToRefuse() {
        enqueue(
                200,
                envelope(
                        batchContent(batchItem(1, "de", "Hallo"), batchItem(7, "fi", "Seitsemäs")),
                        250))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei")))

        assertEquals("the parser does not decide whose id is whose", 2, result.items.size)
        assertEquals(7, result.items.get(1).id)
    }

    @Test
    fun anIdWrittenAsAStringIsStillAnId() {
        // A shape models fall into, and accepting it costs nothing: dropping the item instead would
        // cover a message that has a perfectly good answer sitting right there.
        enqueue(200, envelope(batchContent("{\"id\":\"1\",\"lang\":\"de\",\"text\":\"Hallo\"}"), 200))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei")))

        assertEquals(1, result.items.size)
        assertEquals(1, result.items.get(0).id)
        assertEquals("Hallo", result.items.get(0).text)
    }

    @Test
    fun anItemWithNoTextIsDroppedRatherThanFailingTheBatch() {
        enqueue(200, envelope(batchContent("{\"id\":1}", batchItem(2, "fi", "Moi")), 200))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei"), sent(2, "Moi")))

        assertEquals(1, result.items.size)
        assertEquals(2, result.items.get(0).id)
    }

    @Test
    fun proseInsteadOfABatchObjectIsAnErrorNotAGuess() {
        // The shape the owner's phone once recorded, moved to the batch call: the model wrapped its
        // answer in prose and quoted the message it was given, and a reason that repeated either
        // would put somebody's words into a stored field and onto the failures screen.
        val message = "muuten, vučić astuu tänään alas"
        enqueue(200, envelope("Sure! Here is your translation of " + message + ": Hallo", 200))

        try {
            client.translateBatch("fi", listOf(sent(1, message)))
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("parsing is not worth retrying", e.retryable)
            assertTrue(
                    "the reason must still say what went wrong: " + e.message,
                    e.message!!.contains("not the JSON we asked for"))
            assertTrue(
                    "and it says the shape instead: " + e.message,
                    e.message!!.contains("chars"))
            assertFalse(
                    "the answer's words must never reach the reason: " + e.message,
                    e.message!!.contains("Hallo"))
            assertFalse(
                    "nor the message the answer carried: " + e.message,
                    e.message!!.contains("vučić"))
        }
    }

    @Test
    fun aBatchAnswerWithoutAnItemsArrayIsAnError() {
        enqueue(200, envelope("{\"lang\":\"fi\",\"text\":\"Hei\"}", 200))

        try {
            client.translateBatch("fi", listOf(sent(1, "Hei")))
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse(e.retryable)
            assertTrue(
                    "the reason should name what was missing: " + e.message,
                    e.message!!.contains("items"))
        }
    }

    @Test
    fun anEmptyItemsArrayIsAnAnswerNotAnError() {
        // Every item is then missing, and that is the service's per-item failure - not a parse error,
        // because there is nothing wrong with the shape.
        enqueue(200, envelope("{\"items\":[]}", 120))

        val result =
                client.translateBatch("fi", listOf(sent(1, "Hei")))

        assertTrue(result.items.isEmpty())
        assertEquals(120, result.totalTokens)
    }

    @Test
    fun theBatchRequestIsOneInstructionWithEveryMessageAndTheAppOwnedClause() {
        enqueue(200, envelope(batchContent(batchItem(1, "de", "Hallo")), 200))

        client.translateBatch("fi", listOf(sent(1, "Hei"), sent(2, "Moi")))

        assertEquals("two messages, one request", 1, server.requestCount)
        val request = server.takeRequest()
        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("deepseek-flash", sent.get("model").asString)
        assertEquals("json_object", sent.getAsJsonObject("response_format").get("type").asString)

        val messages = sent.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        val instruction = messages.get(0).asJsonObject.get("content").asString
        assertTrue(
                "the owner's instruction is sent exactly as the one-message call sends it: "
                        + instruction,
                instruction.startsWith(DeepSeekClient.systemPrompt("fi")))
        assertTrue(
                "and the app's own envelope clause follows it: " + instruction,
                instruction.contains(DeepSeekClient.BATCH_CLAUSE))
        assertTrue(
                "the clause must spell out the answer shape: " + instruction,
                instruction.contains("\"items\""))

        val payload =
                JsonParser.parseString(
                                messages.get(1).asJsonObject.get("content").asString)
                        .asJsonObject
        val items = payload.getAsJsonArray("items")
        assertEquals(2, items.size())
        assertEquals(1, items.get(0).asJsonObject.get("id").asInt)
        assertEquals("Hei", items.get(0).asJsonObject.get("text").asString)
        assertEquals(2, items.get(1).asJsonObject.get("id").asInt)
        assertEquals("Moi", items.get(1).asJsonObject.get("text").asString)
    }

    @Test
    fun anEmptyBatchAsksNothing() {
        val result =
                client.translateBatch("fi", java.util.Collections.emptyList())

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalTokens)
        assertEquals("there is nothing to pay for", 0, server.requestCount)
    }

    // ---- the balance: how much is left, asked once and taken at face value ----

    /** A balance document. Every amount is a string in the real one, and stays one here. */
    private fun balance(available: Boolean, vararg currencyAndAmount: String): String {
        val infos = JsonArray()
        for (i in 0 until currencyAndAmount.size - 1 step 2) {
            val info = JsonObject()
            info.addProperty("currency", currencyAndAmount[i])
            info.addProperty("total_balance", currencyAndAmount[i + 1])
            info.addProperty("granted_balance", "0.00")
            info.addProperty("topped_up_balance", currencyAndAmount[i + 1])
            infos.add(info)
        }
        val root = JsonObject()
        root.addProperty("is_available", available)
        root.add("balance_infos", infos)
        return root.toString()
    }

    @Test
    fun balanceReadsEveryAmountAndTheCurrencyItIsIn() {
        enqueue(
                200,
                "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\","
                        + "\"total_balance\":\"110.00\",\"granted_balance\":\"10.00\","
                        + "\"topped_up_balance\":\"100.00\"}]}")

        val balance = client.fetchBalance()

        assertTrue(balance.isAvailable)
        assertEquals(1, balance.infos.size)
        val info = balance.first()
        assertEquals("CNY", info!!.currency)
        assertEquals("110.00", info.totalBalance)
        assertEquals("10.00", info.grantedBalance)
        assertEquals("100.00", info.toppedUpBalance)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/user/balance", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test
    fun balanceKeepsEveryCurrencyOnTheAccount() {
        enqueue(200, balance(true, "USD", "1.50", "CNY", "110.00"))

        val balance = client.fetchBalance()

        assertEquals(2, balance.infos.size)
        assertEquals("USD", balance.infos.get(0).currency)
        assertEquals("1.50", balance.infos.get(0).totalBalance)
        assertEquals("CNY", balance.infos.get(1).currency)
    }

    @Test
    fun anAccountThatCannotPayIsAnAnswerNotAnError() {
        enqueue(200, balance(false))

        val balance = client.fetchBalance()

        assertFalse(balance.isAvailable)
        assertTrue(balance.infos.isEmpty())
        assertNull(balance.first())
    }

    @Test
    fun aMalformedBalanceIsAnErrorNotAGuess() {
        enqueue(200, "<html>we are down for maintenance</html>")

        try {
            client.fetchBalance()
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("parsing is not worth retrying", e.retryable)
        }
    }

    @Test
    fun aBalanceWithoutTheExpectedFieldsIsAnError() {
        enqueue(200, "{\"is_available\":\"yes\",\"balance_infos\":[]}")
        enqueue(200, "{\"is_available\":true}")

        for (field in arrayOf("is_available", "balance_infos")) {
            try {
                client.fetchBalance()
                fail("expected a TranslationException for a missing " + field)
            } catch (e: DeepSeekClient.TranslationException) {
                assertFalse(e.retryable)
                assertTrue(
                        "the message should name what was missing: " + e.message,
                        e.message!!.contains(field))
            }
        }
    }

    @Test
    fun aRejectedKeyOnTheBalanceIsNotWorthRetrying() {
        enqueue(401, "{\"error\":{\"message\":\"no\"}}")

        try {
            client.fetchBalance()
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertFalse("a bad key must not be retried forever", e.retryable)
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun anUnreachableBalanceIsWorthRetrying() {
        server.shutdown()

        try {
            client.fetchBalance()
            fail("expected a TranslationException")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue(e.retryable)
        }
    }

    /**
     * One client for the whole process, and the timeouts it has always had.
     *
     * <p>Both halves are the point of the hoist: a getter that built a fresh client would still pass
     * every other test here, because every one of them injects its own, and nothing else in the tree
     * names these three numbers.
     */
    @Test
    fun defaultHttpIsOneSharedClientWithTheTimeoutsItAlwaysHad() {
        val first = DeepSeekClient.defaultHttp()

        assertSame("defaultHttp() must not build a client per call", first, DeepSeekClient.defaultHttp())
        assertEquals(15000, first.connectTimeoutMillis)
        assertEquals(60000, first.readTimeoutMillis)
        assertEquals(30000, first.writeTimeoutMillis)
    }
}
