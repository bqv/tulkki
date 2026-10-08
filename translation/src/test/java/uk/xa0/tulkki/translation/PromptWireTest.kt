package uk.xa0.tulkki.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What actually goes on the wire when an instruction has been edited.
 *
 * <p>{@code PromptBookTest} proves the identity side - an edit cannot be served the old answer. This
 * proves the other half, which is the one the owner sees: the sentence the app sends is the sentence
 * they wrote. It goes through a real request against a local server rather than through
 * {@code PromptBook}, because a settings screen that saves and a client that does not read is exactly
 * the shape of a feature that looks like it works.
 */
class PromptWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient
    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
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
        // The settings instance is process-wide on purpose; leave it as a fresh install.
        TranslationSettings.inMemory()
    }

    @Test
    fun anEditedTranslateInstructionIsTheOneSent() {
        settings.setTranslatePrompt(
                "Rewrite every message into %1\$s, keeping the line breaks. Answer with JSON only, "
                        + "exactly this shape: {\"lang\":\"<code>\",\"text\":\"<the message>\"}.")
        enqueue("fi", "Hei!")

        client.translate("Hello!", "fi")

        val sent = systemContent(server.takeRequest())
        assertTrue(
                "the owner's wording must be what is sent, got: " + sent,
                sent.startsWith("Rewrite every message into Finnish, keeping the line breaks."))
    }

    @Test
    fun anEditedNotesInstructionIsTheOneSent() {
        settings.setReviewPrompt(
                "Translate into %1\$s and answer with JSON only, exactly this shape: "
                        + "{\"lang\":\"<code>\",\"text\":\"<the input in %1\$s>\",\"notes\":[]}. "
                        + "Write the notes in %2\$s.")
        enqueue("de", "Hallo!")

        client.translateWithReview("Hei!", "de", "en")

        val sent = systemContent(server.takeRequest())
        assertTrue(sent, sent.startsWith("Translate into German and answer with JSON only"))
        assertTrue(sent, sent.contains("Write the notes in English."))
        // Both placeholders were filled, not just the first one.
        assertTrue(sent, !sent.contains("%1\$s") && !sent.contains("%2\$s"))
    }

    @Test
    fun anEditedGlossInstructionIsTheOneSent() {
        settings.setGlossPrompt(
                "Give one word's meaning in %1\$s. Answer with JSON only: "
                        + "{\"dictionary\":\"x\",\"ending\":\"y\",\"case\":\"z\",\"gloss\":\"w\"}")
        server.enqueue(
                MockResponse()
                        .setBody(
                                content(
                                        "{\"dictionary\":\"talo\",\"ending\":\"no ending\","
                                                + "\"case\":\"nominative\",\"gloss\":\"house\"}",
                                        9)))

        client.gloss("talo", "en")

        val sent = systemContent(server.takeRequest())
        assertEquals(
                "Give one word's meaning in English. Answer with JSON only: "
                        + "{\"dictionary\":\"x\",\"ending\":\"y\",\"case\":\"z\",\"gloss\":\"w\"}",
                sent)
    }

    @Test
    fun anUntouchedInstallSendsTheShippedWording() {
        enqueue("fi", "Hei!")

        client.translate("Hello!", "fi")

        val sent = systemContent(server.takeRequest())
        assertEquals(
                DeepSeekClient.systemPrompt(DeepSeekClient.DEFAULT_SYSTEM_PROMPT, "fi"), sent)
    }

    /**
     * The re-ask goes out as the owner's wording plus the app's own clause, and only for that one
     * request: the batching envelope and this clause are the same mechanism on opposite sides of the
     * cache identity, so a re-ask must carry its own clause and must not carry the batch's.
     */
    @Test
    fun aReAskIsTheOwnersWordingWithTheAppsClauseAfterIt() {
        enqueue("fi", "Hei!")

        client.translate("Hello!", "fi", true)

        val sent = systemContent(server.takeRequest())
        val rendered = DeepSeekClient.systemPrompt(DeepSeekClient.DEFAULT_SYSTEM_PROMPT, "fi")
        assertTrue(
                "the owner's template stays a byte-for-byte prefix: " + sent,
                sent.startsWith(rendered))
        assertEquals(rendered + "\n\n" + DeepSeekClient.RE_ASK_CLAUSE, sent)
        assertTrue(sent, !sent.contains(DeepSeekClient.BATCH_CLAUSE))
    }

    @Test
    fun anEditedReAskIsStillTheOwnersWordingWithTheAppsClause() {
        settings.setTranslatePrompt(
                "Rewrite every message into %1\$s, keeping the line breaks. Answer with JSON only, "
                        + "exactly this shape: {\"lang\":\"<code>\",\"text\":\"<the message>\"}.")
        enqueue("fi", "Hei!")

        client.translate("Hello!", "fi", true)

        val sent = systemContent(server.takeRequest())
        assertEquals(
                "Rewrite every message into Finnish, keeping the line breaks. Answer with JSON only, "
                        + "exactly this shape: {\"lang\":\"<code>\",\"text\":\"<the message>\"}."
                        + "\n\n"
                        + DeepSeekClient.RE_ASK_CLAUSE,
                sent)
    }

    /** A chat completion whose content is some JSON of the caller's choosing. */
    private fun content(json: String, totalTokens: Int): String {
        val message = JsonObject()
        message.addProperty("content", json)

        val choice = JsonObject()
        choice.add("message", message)

        val choices = com.google.gson.JsonArray()
        choices.add(choice)

        val usage = JsonObject()
        usage.addProperty("total_tokens", totalTokens)

        val root = JsonObject()
        root.add("choices", choices)
        root.add("usage", usage)
        return root.toString()
    }

    /** A chat completion whose content is the translation contract. */
    private fun enqueue(lang: String, text: String) {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)
        server.enqueue(MockResponse().setBody(content(contract.toString(), 12)))
    }

    /** The system instruction of the one request that was made. */
    private fun systemContent(request: RecordedRequest): String {
        val root = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        return root.getAsJsonArray("messages").get(0).asJsonObject.get("content").asString
    }
}
