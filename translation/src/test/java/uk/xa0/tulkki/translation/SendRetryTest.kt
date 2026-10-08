package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * One send's request, retried in place: the loop [OutgoingTranslation.requestForSendWithRetry] runs,
 * and the policy [SendRetry] that decides whether there is a next attempt.
 *
 * <p>The bug this pins is the owner's own report: a single blip - a phone's radio dropping the
 * socket, a 429, a 503 - painted the composer's bar with "DeepSeek could not translate this message"
 * and wrote the row as a failed attempt, although the very next request would have answered. So a
 * retryable failure is asked again, a couple of seconds apart, and a non-retryable one never is: an
 * answer that is not the contract, a rejected key and an empty balance reproduce themselves.
 *
 * <p>No clock: the waiting is a parameter, so the cell drives the loop with a recorder and asserts the
 * schedule rather than sleeping through it. The requests themselves are real, against a local server.
 */
class SendRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient

    /** The waits the loop asked for, in order. */
    private val waits = ArrayList<Long>()

    @Before
    fun setUp() {
        waits.clear()
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

    /** A well-formed chat completion whose content is the JSON contract. */
    private fun enqueueAnswer(text: String) {
        val contract = JsonObject()
        contract.addProperty("lang", "en")
        contract.addProperty("text", text)

        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", contract.toString())

        val choice = JsonObject()
        choice.addProperty("index", 0)
        choice.add("message", message)

        val choices = JsonArray()
        choices.add(choice)

        val root = JsonObject()
        root.add("choices", choices)
        server.enqueue(MockResponse().setBody(root.toString()))
    }

    private fun send(): DeepSeekClient.Result =
            OutgoingTranslation.requestForSendWithRetry(
                    client,
                    "Moi! Mennäänkö huomenna kahville?",
                    "en",
                    null,
                    false,
                    false,
                    { }) { millis -> waits.add(millis) }

    /**
     * The reported bug, as a cell: the first two requests fail retryably and the third answers. The
     * answer is the send's, and the loop waited the two short delays - nothing reached the bar, and
     * the row was never written as failed, because the failure never left this method.
     */
    @Test
    fun aBlipIsWaitedOutAndTheSendIsNotLost() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"busy\"}"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"slow down\"}"))
        enqueueAnswer("Hey! Shall we go for coffee tomorrow?")

        val result = send()

        assertEquals("Hey! Shall we go for coffee tomorrow?", result.text)
        assertEquals("three requests: the first two gave up, the third answered",
                3, server.requestCount)
        assertEquals(listOf(2_000L, 5_000L), waits)
    }

    /**
     * A failure that a retry cannot fix is not waited on: an answer that is not the contract would be
     * asked for in exactly the same words and come back exactly as unusable.
     */
    @Test
    fun anAnswerThatIsNotTheContractIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))

        try {
            send()
            fail("a body that is not the contract is not a translation")
        } catch (e: DeepSeekClient.TranslationException) {
            assertTrue("the caller is told, and nothing was waited on", waits.isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    /**
     * The budget is finite: a server that is down stays down, and after the three waits the failure is
     * the send's - the row is held, the bar says the reason, and the owner's tap is the retry.
     */
    @Test
    fun aServerThatStaysDownIsGivenUpOnAfterTheBudget() {
        repeat(SendRetry.ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"busy\"}"))
        }

        try {
            send()
            fail("a server that never answers must not produce a translation")
        } catch (e: DeepSeekClient.TranslationException) {
            assertEquals("the budget is the whole of it", SendRetry.ATTEMPTS, server.requestCount)
            assertEquals(listOf(2_000L, 5_000L, 15_000L), waits)
        }
    }

    /** The policy itself, so the numbers above have one home. */
    @Test
    fun theScheduleIsThreeShortWaitsAndThenNothing() {
        assertEquals(4, SendRetry.ATTEMPTS)
        assertEquals(2_000L, SendRetry.delayMillis(1))
        assertEquals(5_000L, SendRetry.delayMillis(2))
        assertEquals(15_000L, SendRetry.delayMillis(3))
        assertTrue("the first failure may be asked again", SendRetry.retryable(1))
        assertTrue("and the second", SendRetry.retryable(2))
        assertTrue("and the third", SendRetry.retryable(3))
        assertTrue("but not a fourth", !SendRetry.retryable(4))
    }
}
