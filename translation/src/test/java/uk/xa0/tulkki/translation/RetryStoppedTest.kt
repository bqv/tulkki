package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * Item 17's self-retry, end to end and without a device: a received row a cause stopped is revived
 * when the cause observably clears, and a row the check refused is revived by nothing.
 *
 * <p>The pass is where the trigger lives, deliberately: every event the owner can produce ends in a
 * pass - the key write kicks one, the cap write is picked up by the next one, a successful call
 * happens inside this one - so the causes that have cleared are read off the world at the top of the
 * pass rather than wired as four separate hooks. Both rules are the cells below, and the refusal's is
 * the one that must never move: the same request reproduces the same refusal, so only the owner's tap
 * and its literal re-ask answer it.
 */
class RetryStoppedTest {

    private val ZONE = ZoneId.of("UTC")
    private val NOW =
            ZonedDateTime.parse("2026-01-01T12:00:00Z").toInstant().toEpochMilli()

    private val ENGLISH = "Hey, are we still meeting tomorrow for coffee?"
    /** A second, different body, so a revived row is a fresh purchase rather than a cache hit. */
    private val ENGLISH_AGAIN = "Another English sentence that needs translating."
    private val FINNISH = "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    private lateinit var server: MockWebServer
    private lateinit var settings: TranslationSettings
    private lateinit var queueStore: TranslationDoubles.MemoryQueueStore
    private lateinit var activity: TranslationDoubles.MemoryActivityPort
    private lateinit var writer: TranslationDoubles.RecordingWriter
    private lateinit var log: TranslationDoubles.RecordingLog
    private lateinit var queue: TranslationQueue
    private lateinit var service: TranslationService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        settings = TranslationSettings.inMemory()
        settings.setAppLanguage("fi")
        // Two different languages, so the interpreter is on and the pass classifies against a target.
        settings.setStudyLanguage("de")

        queueStore = TranslationDoubles.MemoryQueueStore()
        activity = TranslationDoubles.MemoryActivityPort()
        writer = TranslationDoubles.RecordingWriter()
        log = TranslationDoubles.RecordingLog()
        queue = TranslationQueue(queueStore)
        service = newService()
    }

    /**
     * A service over this test's store and settings. A cell builds a *second* one to stand for a
     * process that starts after the settings were written - the case an in-memory trigger cannot see.
     */
    private fun newService(): TranslationService {
        return TranslationService(
                queue,
                TranslationCache(TranslationDoubles.MemoryCacheStore()),
                settings,
                activity,
                { apiKey ->
                    val client =
                            DeepSeekClient(
                                    OkHttpClient(),
                                    apiKey,
                                    server.url("/chat/completions").toString(),
                                    "deepseek-flash")
                    TranslationService.Client.over(client)
                },
                writer,
                ZONE,
                log)
    }

    @After
    fun tearDown() {
        server.shutdown()
        TranslationSettings.inMemory()
    }

    /**
     * A row that a cause stopped, seeded directly: the queue has no way to produce one without a
     * real API, and this class is about what happens to a row that already carries a cause.
     */
    private fun stopped(
            uuid: String, body: String, state: Int, cause: FailureCause?
    ): TranslationQueue.Item {
        val item =
                queue.enqueue(uuid, "conversation-1", body, "fi", false, NOW - 1_000)
        item.state = state
        item.failureCause = cause
        if (state == TranslationQueue.Item.STATE_FAILED) {
            item.attempts = 1
            item.failedAt = NOW - 1_000
        }
        queueStore.update(item)
        return item
    }

    /** A row that is simply waiting to be translated. */
    private fun waiting(uuid: String): TranslationQueue.Item {
        return stopped(uuid, ENGLISH, TranslationQueue.Item.STATE_PENDING, null)
    }

    private fun answer(lang: String, text: String, totalTokens: Int) {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)

        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", contract.toString())

        val choice = JsonObject()
        choice.addProperty("index", 0)
        choice.add("message", message)

        val choices = JsonArray()
        choices.add(choice)

        val usage = JsonObject()
        usage.addProperty("prompt_tokens", Math.max(0, totalTokens - 10))
        usage.addProperty("completion_tokens", 10)
        usage.addProperty("total_tokens", totalTokens)

        val root = JsonObject()
        root.add("choices", choices)
        root.add("usage", usage)
        server.enqueue(MockResponse().setBody(root.toString()))
    }

    /**
     * The exclusion, which is the whole reason the refusal is its own cause: no trigger may revive a
     * row the local check refused, because the same request asks the same question and gets the same
     * refusal. All three clearing conditions are true at once here - a key is present, it changed, and
     * the cap has headroom - and still nothing may touch the row.
     */
    @Test
    fun aRefusedRowIsNotRevivedByAnyTrigger() {
        val refused =
                stopped(
                        "m-refused",
                        ENGLISH,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.CHECK_REFUSED)

        settings.setApiKey("first-key")
        settings.setApiKey("another-key")
        val outcome = service.pump(NOW)

        assertEquals("a refusal is not worth a request", 0, server.requestCount)
        assertEquals(0, outcome.translated)
        assertEquals(
                "the row the check refused stays given up on",
                TranslationQueue.Item.STATE_FAILED,
                refused.state)
        assertEquals(FailureCause.CHECK_REFUSED, refused.failureCause)
    }

    /**
     * The retry-on-cleared-cause: a row stopped for want of a key waits for the key and is bought the
     * moment it arrives, not before.
     */
    @Test
    fun aNoKeyRowIsBoughtWhenTheKeyArrivesAndNotBefore() {
        val waiting =
                stopped(
                        "m-no-key",
                        ENGLISH,
                        TranslationQueue.Item.STATE_PENDING,
                        FailureCause.NO_KEY)

        // Not before: no key, no request, and the row is left exactly where it was.
        val before = service.pump(NOW)
        assertTrue("the pass could not spend", before.noApiKey)
        assertEquals(0, server.requestCount)
        assertEquals(TranslationQueue.Item.STATE_PENDING, waiting.state)
        assertEquals(FailureCause.NO_KEY, waiting.failureCause)

        // The key arrives: the row is re-enqueued - cause cleared, due now - and bought.
        settings.setApiKey("test-key")
        answer("en", FINNISH, 150)
        val after = service.pump(NOW)

        assertEquals(1, after.translated)
        assertEquals(1, server.requestCount)
        assertEquals(
                Message.TRANSLATION_DONE, writer.writeFor("m-no-key")!!.state)
        assertNull("the cleared cause goes with the revival", waiting.failureCause)
    }

    /**
     * The other half of that trigger: a key that is still rejected answers 401 again, the row is
     * marked again, and the pass ends. The trigger buys one attempt per pass while the key is bad -
     * a request and no tokens - which is what "observable at all" costs, and it is bounded the same
     * way the credit probe is.
     */
    @Test
    fun aStillRejectedKeyRowIsAttemptedOnceAndMarkedAgain() {
        settings.setApiKey("still-the-rejected-key")
        val rejected =
                stopped(
                        "m-rejected",
                        ENGLISH_AGAIN,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.REJECTED_KEY)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))

        val outcome = service.pump(NOW)

        assertEquals("one attempt, not a loop", 1, server.requestCount)
        assertEquals(0, outcome.translated)
        assertEquals(TranslationQueue.Item.STATE_FAILED, rejected.state)
        assertEquals(
                "and the reason is the same rejection again",
                FailureCause.REJECTED_KEY,
                rejected.failureCause)
    }

    /**
     * Credit is inferred from an observed success, never polled: a request that came back with a
     * translation is the proof, and every row the empty account stopped is revived in that same pass.
     */
    @Test
    fun aSuccessfulCallRevivesTheRowsAnEmptyAccountStopped() {
        val stoppedByCredit =
                stopped(
                        "m-no-credit",
                        ENGLISH_AGAIN,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.NO_CREDIT)
        val live = waiting("m-live")
        settings.setApiKey("test-key")

        answer("en", FINNISH, 150)
        answer("en", FINNISH, 150)
        val outcome = service.pump(NOW)

        assertEquals("the waiting row, then the one the success revived", 2, outcome.translated)
        assertEquals(2, server.requestCount)
        assertNull(stoppedByCredit.failureCause)
        assertEquals(TranslationQueue.Item.STATE_DONE, stoppedByCredit.state)
        assertEquals(TranslationQueue.Item.STATE_DONE, live.state)
    }

    /**
     * A cap-reached row is stamped while the pass cannot spend and is cleared by the pass that next
     * finds headroom - the cap raised, or the day rolled over.
     */
    @Test
    fun aCapStoppedRowCarriesTheCapUntilThePassCanSpendAgain() {
        settings.setApiKey("test-key")
        settings.setDailyTokenCap(100)
        val first = waiting("m-first")
        answer("en", FINNISH, 150)
        assertEquals(1, service.pump(NOW).translated)

        // The cap is spent, so a row that arrives after it is not bought - and it says why.
        val capped = stopped(
                        "m-capped",
                        ENGLISH_AGAIN,
                        TranslationQueue.Item.STATE_PENDING,
                        null)
        answer("en", FINNISH, 150)
        val atTheCap = service.pump(NOW)

        assertTrue(atTheCap.capReached)
        assertEquals("nothing is bought at the cap", 1, server.requestCount)
        assertNull("and nothing is written for the row", writer.writeFor("m-capped"))
        assertEquals(
                "the cap is why this row is still here",
                FailureCause.CAP_REACHED,
                capped.failureCause)

        // The cap is raised: the next pass finds headroom, clears the cause and buys the row.
        settings.setDailyTokenCap(100_000)
        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(2, server.requestCount)
        assertNull(capped.failureCause)
        assertEquals(TranslationQueue.Item.STATE_DONE, capped.state)
        assertEquals(TranslationQueue.Item.STATE_DONE, first.state)
    }
    /**
     * The hole item 17's credit trigger had: the observation is "a request came back", and a queue
     * whose only rows the empty account stopped makes no request at all - nothing is due, so nothing
     * is asked, so no success ever clears it. The row itself is now the request: with nothing else due,
     * one such row is tried, the account answers, and the state is reachable by itself.
     */
    @Test
    fun aRowTheEmptyAccountStoppedIsBoughtWhenNothingElseIsDue() {
        val stoppedByCredit =
                stopped(
                        "m-only-no-credit",
                        ENGLISH,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.NO_CREDIT)
        settings.setApiKey("test-key")
        answer("en", FINNISH, 150)

        val outcome = service.pump(NOW)

        assertEquals("the probe was the pass's one request", 1, server.requestCount)
        assertEquals(1, outcome.translated)
        assertEquals(TranslationQueue.Item.STATE_DONE, stoppedByCredit.state)
        assertNull("and the cause that stopped it is gone", stoppedByCredit.failureCause)
    }

    /**
     * The other half of that trigger: an account that is still empty is probed once, not in a loop. One
     * request, the row is marked again, and the pass ends - the next pass is where a fresh observation
     * is worth buying.
     */
    @Test
    fun aStillEmptyAccountIsProbedOnceAndTheRowStaysGivenUpOn() {
        val stoppedByCredit =
                stopped(
                        "m-still-no-credit",
                        ENGLISH,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.NO_CREDIT)
        settings.setApiKey("test-key")
        server.enqueue(
                MockResponse()
                        .setResponseCode(402)
                        .setBody("{\"error\":{\"message\":\"Insufficient Balance\"}}"))

        val outcome = service.pump(NOW)

        assertEquals("exactly one probe, not a loop", 1, server.requestCount)
        assertEquals(0, outcome.translated)
        assertEquals("and the row is given up on again", TranslationQueue.Item.STATE_FAILED,
                stoppedByCredit.state)
        assertEquals(FailureCause.NO_CREDIT, stoppedByCredit.failureCause)
    }
    /**
     * The restart hole item 17's key trigger had. The trigger used to be "the key generation moved",
     * and the generation lived in memory: a service first built *after* the key was written seeded its
     * baseline from the settings and never saw a change, so a row a rejected key stopped waited for
     * ever. The trigger is now the presence of a key, which is the one observation that survives a
     * restart - and this cell builds the service only after the key is in place, which is exactly the
     * shape that used to fail.
     */
    @Test
    fun aRejectedKeyRowIsTriedByAServiceBuiltAfterTheKeyWasWritten() {
        settings.setApiKey("the-key-that-was-rejected")
        val rejected =
                stopped(
                        "m-restart-rejected",
                        ENGLISH_AGAIN,
                        TranslationQueue.Item.STATE_FAILED,
                        FailureCause.REJECTED_KEY)
        // The process starts here: this service has never observed a key change.
        service = newService()
        answer("en", FINNISH, 150)

        val outcome = service.pump(NOW)

        assertEquals("a key is present, so the rejected one may have been replaced", 1,
                server.requestCount)
        assertEquals(1, outcome.translated)
        assertEquals(TranslationQueue.Item.STATE_DONE, rejected.state)
        assertNull(rejected.failureCause)
    }
}
