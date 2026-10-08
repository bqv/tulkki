package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.stream.Collectors
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The whole inbound pass, against a local server: no key, no network, no device.
 *
 * <p>These are the tests that matter for money - that a Finnish message is never sent, that the same
 * text is never bought twice, and that the cap stops the calls without dropping anything.
 */
class TranslationServiceTest {

    private val ZONE = ZoneId.of("UTC")
    private val NOW =
            ZonedDateTime.parse("2026-01-01T12:00:00Z").toInstant().toEpochMilli()
    private val ONE_DAY = 24L * 60L * 60L * 1000L

    private val ENGLISH = "Hey, are we still meeting tomorrow for coffee?"
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
        settings.setApiKey("test-key")
        settings.setAppLanguage("fi")
        // The interpreter has to be on for the pump to translate anything at all: two different
        // languages, and with it off classify answers NO_LANGUAGE for every item. This class pins
        // the pass the interpreter runs, not its off state.
        settings.setStudyLanguage("de")

        queueStore = TranslationDoubles.MemoryQueueStore()
        activity = TranslationDoubles.MemoryActivityPort()
        writer = TranslationDoubles.RecordingWriter()
        log = TranslationDoubles.RecordingLog()
        queue = TranslationQueue(queueStore)
        val cache =
                TranslationCache(TranslationDoubles.MemoryCacheStore())
        service =
                TranslationService(
                        queue,
                        cache,
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
    }

    private fun enqueue(uuid: String, body: String) {
        queue.enqueue(uuid, "conversation-1", body, "fi", NOW)
    }

    private fun answer(lang: String, text: String, totalTokens: Int) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatCompletion(lang, text, totalTokens)))
    }

    /** One well-formed batch answer: the envelope, the request's token total, and its items. */
    private fun batchCompletion(totalTokens: Int, vararg items: String): String {
        val content = StringBuilder("{\"items\":[")
        for (i in 0 until items.size) {
            if (i > 0) {
                content.append(',')
            }
            content.append(items[i])
        }
        content.append("]}")

        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", content.toString())

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

    /** One item of a batch answer, handed in as raw JSON so a test can break one on purpose. */
    private fun batchItem(id: Int, lang: String, text: String): String {
        val item = JsonObject()
        item.addProperty("id", id)
        item.addProperty("lang", lang)
        item.addProperty("text", text)
        return item.toString()
    }

    private fun batchAnswer(totalTokens: Int, vararg items: String) {
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(batchCompletion(totalTokens, *items)))
    }

    /** A well-formed chat completion whose content is the JSON contract the client expects. */
    private fun chatCompletion(lang: String, text: String, totalTokens: Int): String {
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
        return root.toString()
    }

    private fun tokensUsedToday(): Int {
        return settings.tokenCounter().used(DailyTokenCounter.dayOf(NOW, ZONE))
    }

    @Test
    fun aCallsTokenSplitReachesThePerDayLedger() {
        enqueue("m1", ENGLISH)
        answer("fi", FINNISH, 150)

        service.pump(NOW)

        val ledger = settings.usageLedger()
        val days = ledger.recentDays(5)
        assertEquals(1, days.size)
        assertEquals(
                "the ledger day is the cap's own day boundary", ledger.dayOf(NOW), days.get(0).day)
        val usage = days.get(0).usage
        // NOW is 2026-01-01T12:00Z: a Thursday, and outside both peak windows.
        assertEquals(0, usage.peakTokens())
        assertEquals(
                "the response reported no cache split, so the whole prompt is a miss",
                140,
                usage.offPeakCacheMiss)
        assertEquals(10, usage.offPeakOutput)
        assertEquals(150, usage.totalTokens())
    }

    @Test
    fun anEmptyQueueDoesNothing() {
        val outcome = service.pump(NOW)
        assertEquals(0, server.requestCount)
        assertEquals(0, outcome.translated)
        assertEquals(0, outcome.pending)
        assertEquals(-1L, service.nextWakeUpMillis(NOW, outcome))
    }

    @Test
    fun withoutAKeyNothingIsCalledAndNothingIsLost() {
        settings.setApiKey("")
        enqueue("m1", ENGLISH)

        val outcome = service.pump(NOW)

        assertTrue(outcome.noApiKey)
        assertEquals(0, server.requestCount)
        assertEquals(1, outcome.pending)
        assertTrue(writer.writes.isEmpty())
        assertEquals(
                "waiting for a key is not a reason to wake the queue up",
                -1L,
                service.nextWakeUpMillis(NOW, outcome))
    }

    @Test
    fun anEnglishMessageIsTranslatedAndTheRoomGetsALanguage() {
        enqueue("m1", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(1, server.requestCount)
        val write = writer.writeFor("m1")!!
        assertNotNull(write)
        assertEquals(Message.TRANSLATION_DONE, write.state)
        assertEquals("Hei, tapaammeko huomenna kahvilla?", write.translatedBody)
        assertEquals("en", write.language)
        assertEquals("en", writer.conversationLanguages.get("conversation-1"))
        assertEquals(150, tokensUsedToday())
        assertEquals(0, outcome.pending)
    }

    @Test
    fun aFinnishMessageIsNeverSentAnywhere() {
        enqueue("m1", FINNISH)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.sameLanguage)
        assertEquals("no request may be made for a message already in the app language", 0, server.requestCount)
        val write = writer.writeFor("m1")!!
        assertEquals(Message.TRANSLATION_SAME_LANGUAGE, write.state)
        assertNull(write.translatedBody)
        assertTrue(
                "a message that needed no translation is not evidence of the room's language",
                writer.conversationLanguages.isEmpty())
        assertEquals(0, tokensUsedToday())
    }

    @Test
    fun theSameTextInTheSameLanguageIsBoughtOnce() {
        enqueue("m1", ENGLISH)
        enqueue("m2", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(1, outcome.fromCache)
        assertEquals("the second message must come from the cache", 1, server.requestCount)
        assertEquals(150, tokensUsedToday())
        assertEquals(
                "Hei, tapaammeko huomenna kahvilla?", writer.writeFor("m2")!!.translatedBody)
    }

    @Test
    fun theCapStopsTheCallsAndKeepsEverythingElseQueued() {
        settings.setDailyTokenCap(100)
        enqueue("m1", ENGLISH)
        answer("en", "Hei.", 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(1, server.requestCount)
        assertEquals(0, outcome.failed)
        assertEquals(
                "the first message is still translated",
                Message.TRANSLATION_DONE,
                writer.writeFor("m1")!!.state)

        // The cap is spent, and a message that arrives after it is not failed and not bought: it
        // stays queued, and the pass says the cap is what stopped it.
        enqueue("m2", "Another English sentence that needs translating.")
        val again = service.pump(NOW + 60_000)

        assertTrue(again.capReached)
        assertEquals(1, server.requestCount)
        assertEquals("nothing is dropped at the cap", 1, again.pending)
        assertNull("the later message is untouched, not failed", writer.writeFor("m2"))

        val wakeUp = service.nextWakeUpMillis(NOW + 60_000, again)
        assertTrue("the queue resumes when the day does", wakeUp > 0 && wakeUp <= ONE_DAY)
    }

    @Test
    fun aNewDayLetsTheQueueCarryOn() {
        settings.setDailyTokenCap(100)
        enqueue("m1", ENGLISH)
        answer("en", "Hei.", 150)

        assertEquals(1, service.pump(NOW).translated)
        assertEquals(1, server.requestCount)

        // The message that arrived after the cap was spent waits for the day rather than being
        // bought, and the new day is what buys it.
        enqueue("m2", "Another English sentence that needs translating.")
        val stillCapped = service.pump(NOW + 60_000)
        assertTrue(stillCapped.capReached)
        assertEquals(1, server.requestCount)
        assertNull(writer.writeFor("m2"))

        answer("en", "Toinen.", 150)
        val tomorrow = service.pump(NOW + ONE_DAY)
        assertFalse(tomorrow.capReached)
        assertEquals(2, server.requestCount)
        assertEquals(Message.TRANSLATION_DONE, writer.writeFor("m2")!!.state)
    }

    @Test
    fun aRateLimitIsRetriedLaterNotNow() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.failed)
        assertEquals(1, outcome.pending)
        assertFalse(outcome.capReached)
        val item = queueStore.items.get("m1")!!
        assertTrue(item.isPending())
        assertEquals(1, item.attempts)
        assertEquals(NOW + TranslationBackoff.delayMillis(1), item.nextAttemptAt)
        assertEquals(TranslationBackoff.delayMillis(1), service.nextWakeUpMillis(NOW, outcome))
        assertNull("a retry does not write a state onto the message", writer.writeFor("m1"))
    }

    @Test
    fun aRejectedKeyIsNotRetriedAtAll() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.pending)
        val item = queueStore.items.get("m1")!!
        assertEquals(TranslationQueue.Item.STATE_FAILED, item.state)
        assertEquals(
                "a message we cannot translate has to say so",
                Message.TRANSLATION_FAILED,
                writer.writeFor("m1")!!.state)
        assertEquals(-1L, service.nextWakeUpMillis(NOW, outcome))
    }

    @Test
    fun anUnreachableApiIsRetryable() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.failed)
        assertEquals(1, outcome.pending)
        assertEquals(0, tokensUsedToday())
        assertTrue(queueStore.items.get("m1")!!.isPending())
    }

    @Test
    fun theRequestTotalIsCountedOnceForTheWholeBatch() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        batchAnswer(321, batchItem(1, "en", "Hei."), batchItem(2, "en", "Toinen."))

        val outcome = service.pump(NOW)

        assertEquals(2, outcome.translated)
        assertEquals(
                "the counter sees the request's total once, not N times it",
                321,
                tokensUsedToday())
        assertEquals(settings.dailyTokenCap() - 321, outcome.remainingTokens)
    }

    // -- a burst is one purchase: the receive queue's batching --------------------------------------

    @Test
    fun dueMessagesGoOutAsOneRequestAndAllOfThemAreWritten() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        enqueue("m3", "A third English sentence that needs translating.")
        batchAnswer(
                400,
                batchItem(1, "en", "Hei."),
                batchItem(2, "en", "Toinen."),
                batchItem(3, "en", "Kolmas."))

        val outcome = service.pump(NOW)

        assertEquals("a burst is one purchase, not three", 1, server.requestCount)
        assertEquals(3, outcome.translated)
        assertEquals(0, outcome.failed)
        assertEquals("Hei.", writer.writeFor("m1")!!.translatedBody)
        assertEquals("Toinen.", writer.writeFor("m2")!!.translatedBody)
        assertEquals("Kolmas.", writer.writeFor("m3")!!.translatedBody)
    }

    @Test
    fun theRequestCarriesEveryMessageWithItsIdAndTheAppOwnedClause() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        batchAnswer(400, batchItem(1, "en", "Hei."), batchItem(2, "en", "Toinen."))

        service.pump(NOW)

        val sent =
                JsonParser.parseString(server.takeRequest().body.readUtf8()).getAsJsonObject()
        val messages = sent.getAsJsonArray("messages")

        val instruction = messages.get(0).getAsJsonObject().get("content").getAsString()
        assertTrue(
                "the app's batch clause must be on the wire: " + instruction,
                instruction.contains(DeepSeekClient.BATCH_CLAUSE))
        assertTrue(
                "the owner's instruction must still be there, unchanged: " + instruction,
                instruction.startsWith(DeepSeekClient.systemPrompt("fi")))

        val payload =
                JsonParser.parseString(
                                messages.get(1).getAsJsonObject().get("content").getAsString())
                        .getAsJsonObject()
        val items = payload.getAsJsonArray("items")
        assertEquals(2, items.size())
        assertEquals(1, items.get(0).getAsJsonObject().get("id").getAsInt())
        assertEquals(ENGLISH, items.get(0).getAsJsonObject().get("text").getAsString())
        assertEquals(2, items.get(1).getAsJsonObject().get("id").getAsInt())
        assertEquals(
                "Another English sentence that needs translating.",
                items.get(1).getAsJsonObject().get("text").getAsString())
    }

    @Test
    fun aSingleDueItemIsStillOneRequestWithNoEnvelope() {
        enqueue("m1", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        val outcome = service.pump(NOW)

        assertEquals(1, server.requestCount)
        assertEquals(1, outcome.translated)
        val sent =
                JsonParser.parseString(server.takeRequest().body.readUtf8()).getAsJsonObject()
        val messages = sent.getAsJsonArray("messages")
        assertFalse(
                "a batch of one must not pay for an envelope it does not need",
                messages.get(0).getAsJsonObject().get("content").getAsString()
                        .contains(DeepSeekClient.BATCH_CLAUSE))
        assertEquals(
                "the user's message is the message, not a one-item envelope",
                ENGLISH,
                messages.get(1).getAsJsonObject().get("content").getAsString())
    }

    @Test
    fun anItemTheAnswerOmitsFailsAlone() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        enqueue("m3", "A third English sentence that needs translating.")
        // The answer never mentions id 2. That is m2's problem and no one else's.
        batchAnswer(400, batchItem(1, "en", "Hei."), batchItem(3, "en", "Kolmas."))

        val outcome = service.pump(NOW)

        assertEquals(2, outcome.translated)
        assertEquals(1, outcome.failed)
        assertEquals("Hei.", writer.writeFor("m1")!!.translatedBody)
        assertEquals("Kolmas.", writer.writeFor("m3")!!.translatedBody)
        assertEquals(
                "the omitted message is the only one that failed",
                Message.TRANSLATION_FAILED,
                writer.writeFor("m2")!!.state)
    }

    @Test
    fun anIdTheRequestNeverSentIsIgnored() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        batchAnswer(
                400,
                batchItem(1, "en", "Hei."),
                batchItem(2, "en", "Toinen."),
                batchItem(99, "en", "Ylimääräinen."))

        val outcome = service.pump(NOW)

        assertEquals(2, outcome.translated)
        assertEquals(0, outcome.failed)
        assertEquals("Hei.", writer.writeFor("m1")!!.translatedBody)
        assertEquals("Toinen.", writer.writeFor("m2")!!.translatedBody)
        assertNull("an id nobody sent is not a message", writer.writeFor("99"))
    }

    @Test
    fun aDuplicatedIdTakesTheFirstAndIgnoresTheRest() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        batchAnswer(
                400,
                batchItem(1, "en", "Ensimmäinen."),
                batchItem(2, "en", "Toinen."),
                batchItem(2, "en", "Myöhempi."))

        val outcome = service.pump(NOW)

        assertEquals(2, outcome.translated)
        assertEquals("Ensimmäinen.", writer.writeFor("m1")!!.translatedBody)
        assertEquals(
                "the first answer for an id is the one that stands",
                "Toinen.",
                writer.writeFor("m2")!!.translatedBody)
    }

    @Test
    fun oneWrongLanguageAnswerCoversThatMessageAlone() {
        enqueue("m1", GERMAN)
        enqueue("m2", "Another English sentence that needs translating.")
        // Item 1 comes back in Greek - a writing system no language here uses, so LanguageCheck
        // refuses it on its own; item 2's Finnish answer is untouched by that refusal.
        batchAnswer(
                400,
                batchItem(1, "de", "Καλημέρα, τι κάνεις σήμερα;"),
                batchItem(2, "en", "Toinen."))

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(1, outcome.failed)
        assertEquals("Toinen.", writer.writeFor("m2")!!.translatedBody)
        assertEquals(
                "only the message whose answer was not a translation is covered",
                Message.TRANSLATION_FAILED,
                writer.writeFor("m1")!!.state)
    }

    @Test
    fun aBatchThatFailsAsAWholeFallsBackToTheQueueRetry() {
        enqueue("m1", ENGLISH)
        enqueue("m2", "Another English sentence that needs translating.")
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val outcome = service.pump(NOW)

        assertEquals("one request, one failure decision for the batch", 1, server.requestCount)
        assertEquals(2, outcome.failed)
        assertEquals(2, outcome.pending)
        assertNull("a retry does not write a state onto the message", writer.writeFor("m1"))
        assertNull(writer.writeFor("m2"))
        for (uuid in arrayOf("m1", "m2")) {
            val item = queueStore.items.get(uuid)!!
            assertTrue(uuid + " must still be pending", item.isPending())
            assertEquals("the queue's own backoff takes it from here", 1, item.attempts)
            assertEquals(NOW + TranslationBackoff.delayMillis(1), item.nextAttemptAt)
        }
    }

    @Test
    fun aCachedAnswerIsAppliedWithoutACall() {
        enqueue("m1", ENGLISH)
        val cache =
                TranslationCache(TranslationDoubles.MemoryCacheStore())
        cache.store(CacheKey.of(ENGLISH, "fi"), "en", "Hei, tapaammeko huomenna kahvilla?", 150, NOW - 1)
        // Rebuild the service around that cache, so the entry looks like an earlier purchase, and
        // count the calls: there must be none.
        val calls =
                java.util.concurrent.atomic.AtomicInteger()
        val withWarmCache =
                TranslationService(
                        queue,
                        cache,
                        settings,
                        activity,
                        { apiKey ->
                            calls.incrementAndGet()
                            TranslationDoubles.NoCallClient()
                        },
                        writer,
                        ZONE,
                        log)

        val outcome = withWarmCache.pump(NOW)

        assertEquals("the client must not even be built for a cache hit", 0, calls.get())
        assertEquals(1, outcome.fromCache)
        assertEquals(0, server.requestCount)
        val write = writer.writeFor("m1")!!
        assertEquals(Message.TRANSLATION_DONE, write.state)
        assertEquals("Hei, tapaammeko huomenna kahvilla?", write.translatedBody)
        assertEquals("en", writer.conversationLanguages.get("conversation-1"))
    }

    @Test
    fun aModelThatCallsAGermanMessageFinnishDoesNotPutTheGermanOnScreen() {
        // The leak this replaced: the model's `lang` alone decided that nothing needed translating,
        // so a German message the model mislabelled Finnish was stored as "already the app language"
        // and rendered raw - and leaked into the conversation-list preview. The offline reading is
        // the verdict, so the answer is stored as a translation and the original stays covered.
        enqueue("m1", GERMAN)
        answer("fi", GERMAN_IN_FINNISH, 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        val write = writer.writeFor("m1")!!
        assertEquals(Message.TRANSLATION_DONE, write.state)
        assertEquals(GERMAN_IN_FINNISH, write.translatedBody)
        assertEquals("de", writer.conversationLanguages.get("conversation-1"))
        assertEquals(
                "one line for the contradiction: " + log.lines,
                1L,
                log.lines.stream().filter { text -> text.contains("de(1.00)") }.count())
        assertTrue(
                "and it is worth a line: " + log.lines,
                log.lines.stream().anyMatch { text -> text.contains("de(1.00)") })
    }

    @Test
    fun theModelsAppLanguageIsBelievedWhenTheDetectorHasNoOpinion() {
        // The one case the model's answer is still allowed to decide: the offline detector really
        // has nothing to say about "Ok", so its `lang` is the only evidence there is.
        enqueue("m1", "Ok")
        answer("fi", "[fi] Ok", 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        val write = writer.writeFor("m1")!!
        assertEquals(Message.TRANSLATION_SAME_LANGUAGE, write.state)
        assertNull(write.translatedBody)
        assertTrue("the app language is never a room's language", writer.conversationLanguages.isEmpty())
    }

    @Test
    fun aWeakReadingIsTranslatedEvenWhenTheModelClaimsTheAppLanguage() {
        // Below the bar the detector is not "sure enough to skip", and the safe direction is to
        // translate: a needless call costs tokens, a raw message costs the design.
        assertTrue(
                TextLanguage.detect(WEAK_ENGLISH).confidence < TextLanguage.TRUSTWORTHY_CONFIDENCE)
        enqueue("m1", WEAK_ENGLISH)
        answer("fi", "Nähdään huomenna", 150)

        service.pump(NOW)

        val write = writer.writeFor("m1")!!
        assertEquals(Message.TRANSLATION_DONE, write.state)
        assertNotNull(write.translatedBody)
    }

    @Test
    fun aDisagreeingModelLanguageIsNotWhatTheRowRecords() {
        // translation_lang on a received row is inert today, but it should not repeat the model's
        // mistake when the offline reading is in hand.
        enqueue("m1", GERMAN)
        answer("en", GERMAN_IN_FINNISH, 150)

        service.pump(NOW)

        assertEquals("de", writer.writeFor("m1")!!.language)
    }

    // -- the conversation's language comes from the offline detector, not from the model ----------

    private val GERMAN = "Gestern war es sehr schön."
    /** What DeepSeek would answer for GERMAN when the target is Finnish. */
    private val GERMAN_IN_FINNISH = "Eilen oli erittäin kaunis ilma."
    private val WEAK_ENGLISH = "See you tomorrow"

    @Test
    fun theDetectorsGermanBeatsTheModelsEnglish() {
        // The failure this test exists for: the model is a bad detector, and a conversation whose
        // language is taken from it silently sends the owner's next message in the wrong language.
        enqueue("m1", GERMAN)
        answer("en", GERMAN_IN_FINNISH, 150)

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.translated)
        assertEquals(
                "the conversation is German because the message is German, whatever the model said",
                "de",
                writer.conversationLanguages.get("conversation-1"))
    }

    @Test
    fun theDisagreementIsLoggedNamingBothReadings() {
        enqueue("m1", GERMAN)
        answer("en", GERMAN_IN_FINNISH, 150)

        service.pump(NOW)

        // The log now also carries the request's start and finish, so this cell looks for the line it
        // is about instead of counting the whole log: one disagreement line, and it names both readings.
        val disagreement: List<String> =
                log.lines.stream().filter { text -> text.contains("said") }.collect(Collectors.toList())
        assertEquals("one line for the disagreement: " + log.lines, 1, disagreement.size)
        val line = disagreement.get(0)
        assertTrue("the offline reading is named: " + line, line.contains("de(1.00)"))
        assertTrue("the model's answer is named: " + line, line.contains("said en;"))
    }

    @Test
    fun theModelsAnswerNamesTheConversationOnlyWhenTheDetectorHasNoOpinion() {
        // "Ok" is too short for the offline detector, so the model's answer is the only evidence
        // there is - which is exactly the case the fallback exists for.
        enqueue("m1", "Ok")
        answer("en", "[fi] Ok", 150)

        service.pump(NOW)

        assertEquals("en", writer.conversationLanguages.get("conversation-1"))
    }

    @Test
    fun aWeakLocalReadingDoesNotReplaceAnEstablishedLanguage() {
        assertTrue(
                "the fixture must really be a weak reading, got " + TextLanguage.detect(WEAK_ENGLISH),
                TextLanguage.detect(WEAK_ENGLISH).confidence
                        < TextLanguage.TRUSTWORTHY_CONFIDENCE)
        writer.conversationLanguages.put("conversation-1", "de")
        enqueue("m1", WEAK_ENGLISH)
        answer("en", "[fi] See you tomorrow", 150)

        service.pump(NOW)

        assertEquals(
                "a guess below the bar keeps what the conversation already had",
                "de",
                writer.conversationLanguages.get("conversation-1"))
    }

    @Test
    fun aConfidentReadingOfOneTokenDoesNotEstablishALanguage() {
        // "Matti" is read as Maltese at 0.96: confidence alone is not enough for a bare name.
        enqueue("m1", "Matti")
        answer("de", "[fi] Matti", 150)

        service.pump(NOW)

        assertTrue(
                "one token is not prose and cannot name the conversation",
                writer.conversationLanguages.isEmpty())
    }

    @Test
    fun agreeingReadingsAreNotLoggedAsADisagreement() {
        enqueue("m1", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        service.pump(NOW)

        assertTrue(
                "nothing disagreed: " + log.lines,
                log.lines.stream().noneMatch { text -> text.contains("said") })
    }

    @Test
    fun anUnparseableAnswerFailsImmediatelyAndIsNotRetried() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setResponseCode(200).setBody("I am not JSON"))

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.pending)
        assertEquals(
                Message.TRANSLATION_FAILED, writer.writeFor("m1")!!.state)
    }

    @Test
    fun aLinkIsMarkedHandledWithoutACallAndWithoutAState() {
        enqueue("m1", "https://example.com/some/interesting/page")

        val outcome = service.pump(NOW)

        assertEquals(1, outcome.skipped)
        assertEquals(0, server.requestCount)
        assertEquals(0, outcome.pending)
        assertNull("nothing to translate is not a failure", writer.writeFor("m1"))
    }

    // -- what the app can say about itself ----------------------------------------------------------

    private fun today(): String {
        return DailyTokenCounter.dayOf(NOW, ZONE)
    }

    @Test
    fun aTranslationIsCountedAgainstToday() {
        enqueue("m1", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        service.pump(NOW)

        assertEquals(1, activity.translatedToday(today()))
    }

    @Test
    fun anAnswerFromTheCacheIsCountedToo() {
        // The message did get its translation today; it just did not cost anything today.
        enqueue("m1", ENGLISH)
        enqueue("m2", ENGLISH)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)

        service.pump(NOW)

        assertEquals(2, activity.translatedToday(today()))
    }

    @Test
    fun nothingTranslatedIsNotCounted() {
        enqueue("m1", FINNISH)
        enqueue("m2", "https://example.com/a/b")

        service.pump(NOW)

        assertEquals(0, activity.translatedToday(today()))
    }

    @Test
    fun aFailureIsRememberedWithItsReasonAndItsMessage() {
        enqueue("m1", ENGLISH)
        server.enqueue(
                MockResponse()
                        .setResponseCode(402)
                        .setBody("{\"error\":{\"message\":\"Insufficient Balance\"}}"))

        service.pump(NOW)

        assertEquals(HeldSend.HoldReason.NO_CREDIT, activity.reasonFor("m1"))
        assertNotNull(activity.lastFailure())
        assertTrue(
                "DeepSeek's own words are the diagnosable part",
                activity.lastFailure()!!.detail()!!.contains("402"))
    }

    @Test
    fun aRejectedKeyIsRememberedAsARejectedKey() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))

        service.pump(NOW)

        assertEquals(HeldSend.HoldReason.REJECTED_KEY, activity.reasonFor("m1"))
    }

    @Test
    fun anUnreachableApiIsRememberedAndStillWorthRetrying() {
        enqueue("m1", ENGLISH)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcome = service.pump(NOW)

        assertEquals(HeldSend.HoldReason.UNREACHABLE, activity.reasonFor("m1"))
        assertEquals("a retryable failure is still waiting, not given up on", 1, outcome.pending)
    }

    @Test
    fun askingForAMessageThatHadGivenUpTriesItAgain() {
        // The tap path: a message whose retries were used up goes back in and is bought now.
        enqueue("m1", ENGLISH)
        val item = queue.nextDue(NOW)!!
        for (attempt in 0 until TranslationBackoff.MAX_ATTEMPTS) {
            queue.failed(item, true, "deepseek is unreachable", NOW)
        }
        assertEquals(TranslationQueue.Item.STATE_FAILED, item.state)
        assertEquals(0, queue.pendingCount())

        service.enqueueExplicit("m1", "conversation-1", ENGLISH, NOW + 60_000)
        answer("en", "Hei, tapaammeko huomenna kahvilla?", 150)
        val outcome = service.pump(NOW + 60_000)

        assertEquals(1, outcome.translated)
        assertEquals(Message.TRANSLATION_DONE, writer.writeFor("m1")!!.state)
        assertEquals(0, outcome.pending)
    }

    @Test
    fun askingForAMessageThatIsWaitingStopsWaitingOutItsBackoff() {
        // One row per message, and the owner asking for it is what "now" means.
        enqueue("m1", ENGLISH)
        val item = queue.nextDue(NOW)!!
        queue.failed(item, true, "deepseek returned 429", NOW)
        assertNull("still waiting out its backoff", queue.nextDue(NOW))

        service.enqueueExplicit("m1", "conversation-1", ENGLISH, NOW)

        assertEquals("asking again does not add a second row", 1, queueStore.items.size)
        assertNotNull(queue.nextDue(NOW))
    }

    /**
     * The defect, end to end: a row that failed keeps the target language it captured when it was
     * queued, and the explicit re-request used to revive it still pointed there, because the insert
     * that followed was {@code CONFLICT_IGNORE}. The owner's tap is a decision made now, so it has to
     * speak the language in force now.
     *
     * <p>The app language is moved while the interpreter stays on, which is the shape the fix is for:
     * the settings write site only clears state on an on&rarr;off flip, so a failed row survives the
     * change untouched and is exactly what the tap meets. The wire is asserted as well as the write,
     * because the instruction is where the target language actually appears.
     */
    @Test
    fun aFailedMessageReRequestedIsTranslatedIntoTheLanguageInForceNow() {
        // Queued while the app language was Swedish, so that is the target the row captures.
        settings.setAppLanguage("sv")
        service.enqueue("m1", "conversation-1", ENGLISH, NOW)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))
        service.pump(NOW)
        assertEquals(
                "the failure is final, which is the state the failures screen keeps",
                Message.TRANSLATION_FAILED,
                writer.writeFor("m1")!!.state)
        assertNull(queue.nextDue(NOW))

        // The owner switches to Finnish - the interpreter stays on, so nothing clears the row - and
        // taps the covered message.
        settings.setAppLanguage("fi")
        answer("en", FINNISH, 150)
        service.enqueueExplicit("m1", "conversation-1", ENGLISH, NOW + 60_000)
        val outcome = service.pump(NOW + 60_000)

        assertEquals(1, outcome.translated)
        assertEquals(0, outcome.failed)
        assertEquals(
                Message.TRANSLATION_DONE,
                writer.writes.get(writer.writes.size - 1).state)
        assertEquals(FINNISH, writer.writes.get(writer.writes.size - 1).translatedBody)

        server.takeRequest()
        val sent =
                JsonParser.parseString(server.takeRequest().body.readUtf8()).getAsJsonObject()
        val instruction =
                sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()
        assertEquals(
                "the re-request must go out in the language in force now",
                DeepSeekClient.systemPrompt("fi"),
                instruction)
    }

    /**
     * The whole of docs/MIGRATION.md item 17's sixth decision, end to end: the check refuses the answer,
     * and the owner's tap on the covered message must ask again <em>differently</em> - the owner's
     * template plus the app's own clause - instead of reproducing the question that was refused.
     */
    @Test
    fun aTapOnARefusedMessageAsksAgainWithTheAppsClause() {
        // The refused attempt: the model hands the input back, and the local check refuses that.
        enqueue("m1", ENGLISH)
        answer("en", ENGLISH, 150)
        service.pump(NOW)
        assertEquals(Message.TRANSLATION_FAILED, writer.writeFor("m1")!!.state)
        assertEquals(
                "the refused answer is not kept, or the tap would be handed it back",
                HeldSend.HoldReason.FAILED,
                activity.reasonFor("m1"))
        server.takeRequest()

        // The tap: the same text, asked again with the app's own literal clause.
        answer("en", FINNISH, 150)
        service.enqueueExplicit("m1", "conversation-1", ENGLISH, true, NOW + 60_000)
        val outcome = service.pump(NOW + 60_000)

        assertEquals(1, outcome.translated)
        assertEquals(0, outcome.failed)
        // The first write for this uuid is the refusal; the newest is the answer the re-ask bought.
        assertEquals(
                Message.TRANSLATION_DONE,
                writer.writes.get(writer.writes.size - 1).state)
        assertEquals(FINNISH, writer.writes.get(writer.writes.size - 1).translatedBody)

        val sent =
                JsonParser.parseString(server.takeRequest().body.readUtf8()).getAsJsonObject()
        val instruction =
                sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()
        assertEquals(
                "the owner's wording, byte for byte, with the app's clause after it",
                DeepSeekClient.systemPrompt("fi") + "\n\n" + DeepSeekClient.RE_ASK_CLAUSE,
                instruction)
    }

    /**
     * The other half of that rule: an ordinary explicit request - a message that never had an answer
     * refused, or one being asked again for another reason - carries no clause. If this ever sends
     * the app's clause too, the flag has stopped being about the refusal.
     */
    @Test
    fun anOrdinaryExplicitRequestCarriesNoClause() {
        enqueue("m1", ENGLISH)
        service.enqueueExplicit("m1", "conversation-1", ENGLISH, false, NOW)
        answer("en", FINNISH, 150)
        service.pump(NOW)

        val sent =
                JsonParser.parseString(server.takeRequest().body.readUtf8()).getAsJsonObject()
        val instruction =
                sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()
        assertEquals(DeepSeekClient.systemPrompt("fi"), instruction)
        assertFalse(instruction.contains(DeepSeekClient.RE_ASK_CLAUSE))
    }

    /**
     * The settled half the fix must not weaken: a row that is done holds the translation that already
     * succeeded, so asking for the message again buys nothing. Only a row that still owes work is
     * revived.
     */
    @Test
    fun aReRequestForADoneMessageBuysNothing() {
        enqueue("m1", ENGLISH)
        answer("en", FINNISH, 150)
        service.pump(NOW)
        assertEquals(Message.TRANSLATION_DONE, writer.writeFor("m1")!!.state)
        assertEquals(1, server.requestCount)

        // The app language moves on; the message already has its answer for the language it was
        // translated into, and a translation that already succeeded is not bought again.
        settings.setAppLanguage("sv")
        service.enqueueExplicit("m1", "conversation-1", ENGLISH, NOW + 60_000)
        val outcome = service.pump(NOW + 60_000)

        assertEquals(0, outcome.translated)
        assertEquals("nothing was bought again", 1, server.requestCount)
        assertEquals(
                "the row keeps the language it was answered in",
                "fi",
                queueStore.items.get("m1")!!.targetLanguage)
        assertEquals(TranslationQueue.Item.STATE_DONE, queueStore.items.get("m1")!!.state)
    }

    /**
     * The pass's off state, and the ledger's own cell: with the interpreter off, a pump files
     * nothing at all.
     *
     * <p>{@code setUp} leaves the interpreter on deliberately - this class pins the pass it runs -
     * so the off state is asked for here. The same language on both sides is the off pair, and the
     * below is the ordinary spendable shape: German prose, a key configured, the cap untouched, the
     * queue holding a row that <em>is</em> due. On it would be translated (the cells above show
     * exactly that); off the answer is an empty outcome and nothing downstream of the guard happens:
     * no request, no cache write, no message write, no {@code translation_usage} row, no tokens
     * against the day's cap, no "translated today" and no remembered failure - and the row stays in
     * the queue rather than being failed or dropped, so turning the interpreter back on picks up the
     * same gap.
     */
    @Test
    fun anOffInterpreterFilesNoLedgerRowAndCountsNothing() {
        // The off pair: app Finnish with study Finnish, the same language on both sides.
        settings.setStudyLanguage("fi")
        enqueue("m1", ENGLISH)

        val outcome = service.pump(NOW)

        assertEquals("nothing was translated", 0, outcome.translated)
        assertEquals(0, outcome.fromCache)
        assertEquals(0, outcome.sameLanguage)
        assertEquals(0, outcome.skipped)
        assertEquals(0, outcome.failed)
        assertFalse("off is not the same state as a missing key", outcome.noApiKey)
        assertFalse(outcome.capReached)

        assertEquals("no request was made at all", 0, server.requestCount)
        assertTrue("no message row was written", writer.writes.isEmpty())
        assertTrue("no ledger row was filed", settings.usageLedger().recentDays().isEmpty())
        assertEquals("no tokens were counted against the cap", 0, tokensUsedToday())
        assertEquals(
                "nothing was counted as translated today",
                0,
                activity.translatedToday(DailyTokenCounter.dayOf(NOW, ZONE)))
        assertNull("and no failure was remembered", activity.lastFailure())
        assertEquals("the message is still waiting, not failed", 1, queueStore.items.size)
        assertNotNull("still due, for whenever the interpreter is back on", queue.nextDue(NOW))

        // The guard's position, which the shape above cannot see: it is above the key read, so a
        // keyless account off gets the same empty outcome and never the keyless one. That is what
        // makes "before reading the key" a fact rather than a comment - a mutant that moves the guard
        // below the key read answers `noApiKey` here and reports the queue's pending count.
        settings.setApiKey("")
        val keyless = service.pump(NOW)
        assertFalse("off is not the keyless state", keyless.noApiKey)
        assertEquals("and it reports no pending work it never looked at", 0, keyless.pending)
        assertEquals(0, server.requestCount)
        assertTrue(settings.usageLedger().recentDays().isEmpty())
    }
}
