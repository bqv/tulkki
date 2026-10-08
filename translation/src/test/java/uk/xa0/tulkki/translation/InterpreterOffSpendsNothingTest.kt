package uk.xa0.tulkki.translation

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayList
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The step's real gate: <strong>with the interpreter off, no code path can spend.</strong>
 *
 * <p>Every other test in this step pins one surface's off answer. This one is the conjunction, and it
 * is written so that it cannot pass by having quietly stopped driving anything:
 *
 * <ul>
 *   <li>the client is a <em>counting and failing</em> one - every method increments a counter and
 *       throws - so a call is both visible in the final count and fatal the moment it happens;
 *   <li>the ledger, the token counter and the activity are the <em>real</em> ones on
 *       {@code inMemory()} settings, so "zero" is a fact about the production objects rather than
 *       about a double that never had a write path;
 *   <li>the interpreter is off by the sentinel on the app side ({@code appLanguage = NONE}) with a
 *       real study language beside it, a key configured and a due message queued - the off state is
 *       not "no key", not "nothing queued" and not "no study language";
 *   <li>and the same drive is run once with the interpreter <em>on</em> in
 *       {@link #theSameDriveWithTheInterpreterOnIsNotSilent()}: the client is reached and throws,
 *       which is the proof that the off drive's zero is a real zero and not an unexplored path.
 * </ul>
 *
 * <p>The rules driven are §5's list plus the ones this step's own sweeps found it had missed:
 * {@code LanguageSample.shouldSample} and {@code read}, {@code GlossLookups.mayBegin},
 * {@code ReviewStore.record}, {@code ReplyQuote.unresolved},
 * {@code StartupBacklog.eligible}, {@code DisplayedBody.needsTranslation} and
 * {@code EnglishRow.defaults}, plus S4-16's own clear. Each of the rules is asserted beside an
 * on-state control, so a rule that stopped being asked would fail here rather than pass it.
 *
 * <p>{@code HeldSend.maySend} used to be driven here and is not any more: S4-16 deleted it - it had
 * no production caller, and {@code OutgoingTranslation.sendVerdict} is the decision the send path
 * actually makes. The interpreter-off answer it used to pin is pinned where it lives now, in
 * {@code OutgoingTranslationHoldTest}.
 *
 * <p><strong>Two seams §5 names are not here, and cannot be.</strong> {@code TranslationHooks
 * .shouldQueue} and {@code TranslationWork.cancel} are both {@code :app}, which {@code :translation}
 * does not depend on - the direction is {@code :app -> :translation}. They are pinned where they
 * live: the receive path's off cells in {@code TranslationHooksTest} and the scheduled-rows list in
 * {@code TranslationWorkTest}. This class states that rather than reaching for them.
 */
class InterpreterOffSpendsNothingTest {

    private val ZONE = ZoneId.of("UTC")
    private val NOW =
            ZonedDateTime.parse("2026-01-01T12:00:00Z").toInstant().toEpochMilli()

    private val APP = "fi"
    private val ROOM = "de"

    /** Ordinary German prose: confidently a language nobody asked us to translate into. */
    private val GERMAN = "Guten Morgen! Wie geht es dir heute?"

    /** The app language's own prose, for the shapes that are already in the target. */
    private val FINNISH = "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    /**
     * The off state by the sentinel on the app side: off whatever the study language says, which is
     * what makes it the strongest form of the predicate (§1.2's "none" row).
     */
    private val OFF = Interpreter.of(Interpreter.NONE, Interpreter.NONE)

    /** The same settings with the interpreter running, for every control below. */
    private val ON = Interpreter.of(APP, ROOM)

    /**
     * A client that may not be built and may not be called: every method counts itself and then
     * throws, so a leaked call is visible in {@link #calls} and fatal at the same moment. An
     * {@code AssertionError} and not a {@code TranslationException}, on purpose: the service catches
     * the latter on its ordinary failure path, and a leak has to escape it.
     */
    private class CountingClient : TranslationService.Client {

        var calls = 0

        override fun translate(text: String?, targetLanguage: String?): DeepSeekClient.Result {
            calls++
            throw AssertionError("the API was called while the interpreter is off: " + text)
        }

        override fun translate(
                text: String?,
                targetLanguage: String?,
                reAsk: Boolean
        ): DeepSeekClient.Result {
            calls++
            throw AssertionError("the API was called while the interpreter is off: " + text)
        }

        override fun translateBatch(
                targetLanguage: String?,
                items: List<DeepSeekClient.BatchRequest>
        ): DeepSeekClient.BatchResult {
            calls++
            throw AssertionError("the API was asked for a batch while the interpreter is off")
        }
    }

    private lateinit var settings: TranslationSettings
    private lateinit var queueStore: TranslationDoubles.MemoryQueueStore
    private lateinit var cacheStore: TranslationDoubles.MemoryCacheStore
    private lateinit var writer: TranslationDoubles.RecordingWriter
    private lateinit var log: TranslationDoubles.RecordingLog
    private lateinit var queue: TranslationQueue
    private lateinit var cache: TranslationCache
    private lateinit var service: TranslationService
    private lateinit var client: CountingClient
    private var clientCreations = 0

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
        // Off by the app side, with everything else in the state that would otherwise spend: a real
        // study language, a configured key and an untouched cap.
        settings.setAppLanguage(Interpreter.NONE)
        settings.setStudyLanguage(ROOM)
        settings.setApiKey("test-key")

        queueStore = TranslationDoubles.MemoryQueueStore()
        cacheStore = TranslationDoubles.MemoryCacheStore()
        writer = TranslationDoubles.RecordingWriter()
        log = TranslationDoubles.RecordingLog()
        queue = TranslationQueue(queueStore)
        cache = TranslationCache(cacheStore)
        client = CountingClient()
        service =
                TranslationService(
                        queue,
                        cache,
                        settings,
                        // The production activity, so "nothing was counted" is about the real object.
                        settings.activity(),
                        TranslationService.ClientFactory {
                            clientCreations++
                            client
                        },
                        writer,
                        ZONE,
                        log)
    }

    private fun enqueue() {
        queue.enqueue("m1", "conversation-1", GERMAN, APP, NOW)
    }

    /** The day the counters are denominated in, as {@code DailyTokenCounter} spells it. */
    private fun day(): String {
        return DailyTokenCounter.dayOf(NOW, ZONE)
    }

    private fun peer(body: String): TranslationDecision.Candidate {
        val candidate = TranslationDecision.Candidate()
        candidate.live(true, false, false)
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = body
        return candidate
    }

    private fun row(uuid: String, timeSent: Long): StartupBacklog.Row {
        return StartupBacklog.Row(
                uuid, "conversation-1", timeSent, Message.TRANSLATION_NONE, peer(GERMAN))
    }

    private fun aReview(): Review {
        return Review.of(
                Collections.singletonList(
                        Review.Note.of("the ending is wrong", Collections.singletonList("opin"))))
    }

    /**
     * The property itself. One enqueued, due, spendable message; the whole off drive; and then every
     * zero the step promises - no client, no message write, no cache row, no ledger row, no tokens,
     * no translated-today and no remembered failure - with the queued row still exactly where it was.
     */
    @Test
    fun theWholeOffDriveSpendsNothing() {
        enqueue()
        assertEquals(1, queueStore.items.size)

        // -- the pass, which is where the money is
        val outcome = service.pump(NOW)
        assertEquals(0, outcome.translated)
        assertEquals(0, outcome.fromCache)
        assertEquals(0, outcome.sameLanguage)
        assertEquals(0, outcome.skipped)
        assertEquals(0, outcome.failed)
        assertFalse("off is not the keyless state", outcome.noApiKey)
        assertFalse(outcome.capReached)

        // -- the gates and the classifier
        assertEquals(
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify(GERMAN, APP, null, OFF))
        assertFalse(TranslationDecision.isEligible(peer(GERMAN), OFF))
        assertFalse(TranslationDecision.isRequestable(peer(GERMAN), OFF))

        // -- the send path
        assertEquals(ComposerGate.Verdict.SEND, ComposerGate.verdict(GERMAN, APP, ROOM, null, OFF))
        assertEquals(
                HeldSend.Action.SEND,
                HeldSend.decide(GERMAN, APP, ROOM, null, HeldSend.Source.ELSEWHERE, OFF))
        assertFalse(HeldSend.needsTranslation(GERMAN, APP, ROOM, null, OFF))
        assertEquals(
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        GERMAN, Message.TRANSLATION_NONE, null, null, APP, ROOM, null, OFF))

        // -- the display decisions
        assertEquals(
                DisplayedBody.Kind.ORIGINAL,
                DisplayedBody.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, true, OFF).kind())
        assertEquals(GERMAN, DisplayedBody.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, true, OFF).text())
        assertFalse(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, GERMAN, null, OFF))
        assertFalse(
                BubbleHalves.of(
                                GERMAN, FINNISH, Message.TRANSLATION_DONE, Message.STATUS_RECEIVED, null, OFF)
                        .isDivided())
        assertEquals(
                SecondHalf.Kind.NONE,
                SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, true, true, OFF).kind())
        assertFalse(ReplyQuote.unresolved(OFF, "some quoted text").isCovered())

        // -- the English row, the reading aid and the notes
        assertEquals(
                EnglishRow.Kind.NONE,
                EnglishRow.of(true, true, APP, ROOM, "en", false, true, OFF).kind())
        assertEquals(EnglishRow.Kind.NONE, EnglishRow.ofSent(true, ROOM, true, true, OFF).kind())
        assertFalse(EnglishRow.buysOnArrival(true, false, APP, OFF))
        assertFalse(EnglishRow.needsBuying(GERMAN, null, APP, OFF))
        assertTrue(GlossText.words(GERMAN, ROOM, OFF).isEmpty())
        assertNull(ReviewKey.forBubble(GERMAN, Message.TRANSLATION_DONE, Message.STATUS_SEND, ROOM, OFF))
        assertFalse(GlossLookups.mayBegin(OFF))
        assertFalse(LanguageSample.shouldSample(null, null, OFF))
        assertNull(LanguageSample.read(listOf(peer(GERMAN), peer(GERMAN)), APP, OFF))
        ReviewStore.record(cache, GERMAN, ROOM, aReview(), OFF)

        // -- the gap sweep: nothing chosen, and nothing decided about the gap either
        val selection =
                StartupBacklog.everything(listOf(row("h1", 1L), row("h2", 2L)), APP, OFF)
        assertTrue("the gap is not chosen", selection.isEmpty())
        assertFalse(StartupBacklog.eligible(row("h1", 1L), APP, OFF))

        // -- the property, in one place
        assertEquals("no client was built", 0, clientCreations)
        assertEquals("and none was called", 0, client.calls)
        assertTrue("no message row was written", writer.writes.isEmpty())
        assertTrue("no answer was cached", cacheStore.entries.isEmpty())
        assertTrue("no ledger row was filed", settings.usageLedger().recentDays(5).isEmpty())
        assertEquals("no tokens were counted", 0, settings.tokenCounter().used(day()))
        assertEquals(
                "nothing was counted as translated today", 0, settings.activity().translatedToday(day()))
        assertNull("and no failure was remembered", settings.activity().lastFailure())
        assertEquals("the queued row is still queued", 1, queueStore.items.size)
        assertNotNull("and still due", queue.nextDue(NOW))
    }

    /**
     * The control that stops the cell above from being a green nothing: the same setUp, the same
     * enqueued message, the same key and the same queue, with the interpreter on. The pass reaches the
     * client, the client throws, and the counter moves - so a leaked call in the off drive would be
     * just as visible.
     */
    @Test
    fun theSameDriveWithTheInterpreterOnIsNotSilent() {
        settings.setAppLanguage(APP)
        enqueue()

        try {
            service.pump(NOW)
            fail("the drive must reach the client while the interpreter is on")
        } catch (expected: AssertionError) {
            // The counting client's own refusal: the call happened, which is the point.
        }

        assertEquals(1, clientCreations)
        assertEquals("the client was reached, so the off drive's zero is a real zero", 1, client.calls)
    }

    /**
     * The other moment the step is about: the <em>flip</em>. Every cell of
     * {@link #theWholeOffDriveSpendsNothing} is a pass or a decision made while off; this is the
     * transition into it, which is when the interpreter's carried work is dropped. The queue's row
     * still holds
     * the target language it captured while the interpreter was on, so it goes; the last failure is
     * the interpreter's own record, so it goes too; and the owner's own state - the history horizon,
     * the day's count, the two languages the flip just wrote - stays exactly where it was.
     *
     * <p>What the clear costs is half the point. It is a delete and four preference removals, never a
     * pass in disguise. The counting client is fatal as well as counting, so a clear that reached the
     * service would throw here rather than merely be counted.
     *
     * <p><strong>A clause that is deliberately not asserted here.</strong> The failure record is four
     * stored keys; removing only the one that the reader tests for ({@code last_failure_reason}) would
     * be unobservable, because {@code failure()} is null either way and the next {@code saveFailure}
     * rewrites the rest. That was tried as a mutant - the three removals deleted, this test and the
     * whole suite still green - so no cell here claims it, and the three extra removals are justified
     * in {@code TranslationSettings.ActivityStore} by the residue they take rather than by an
     * assertion that would pass either way.
     */
    @Test
    fun theFlipToOffDropsTheCarriedWorkAndBuysNothing() {
        // On, with a due message queued, a horizon and a remembered failure: the state a flip clears.
        settings.setAppLanguage(APP)
        enqueue()
        val day = day()
        settings.activity()
                .recordFailure(
                        HeldSend.HoldReason.UNREACHABLE,
                        "deepseek is unreachable: timeout",
                        "m1",
                        NOW)
        assertEquals(1, queueStore.items.size)

        // The flip, and the clear the transition observes it with.
        settings.setAppLanguage(Interpreter.NONE)
        TranslationSettings.clearInterpreterState(queueStore, settings.activity())

        // The interpreter's carried work is gone.
        assertEquals("no pending queue row survives the flip", 0, queueStore.items.size)
        assertNull("nor the queue's due time", queue.nextDueAt())
        assertNull("and no failure is remembered", settings.activity().lastFailure())
        assertNull("not even for the message it happened to", settings.activity().reasonFor("m1"))

        // The owner's own state is not.
        assertEquals("the clear did not rewrite the languages", Interpreter.NONE, settings.appLanguage())
        assertEquals(ROOM, settings.studyLanguage())

        // And it spent nothing: every zero the off drive promises, on a queue the flip emptied.
        val outcome = service.pump(NOW)
        assertEquals(0, outcome.translated)
        assertEquals(0, outcome.failed)
        assertEquals("no client was built", 0, clientCreations)
        assertEquals("and none was called", 0, client.calls)
        assertTrue("no message row was written", writer.writes.isEmpty())
        assertTrue("no answer was cached", cacheStore.entries.isEmpty())
        assertTrue("no ledger row was filed", settings.usageLedger().recentDays(5).isEmpty())
        assertEquals("no tokens were counted", 0, settings.tokenCounter().used(day))
        assertEquals(
                "nothing was counted as translated today",
                0,
                settings.activity().translatedToday(day))
    }

    /**
     * The clear's own surface, pinned where the production stores cannot be read from a JVM: both
     * halves refuse every call but the one the clear is allowed to make, so the statement is exactly
     * "drop the owed rows and forget the failure" and cannot quietly grow into ordinary queue work -
     * an {@code insert}, a {@code due} snapshot, an {@code update} or a write to the day's count.
     *
     * <p>What this cannot reach, and does not pretend to, is the {@code messages} table: the clear's
     * seam is typed as a {@link TranslationQueue.Store}, which has no statement that could name a
     * message row, a held {@code STATUS_WAITING} row included. That is the type's guarantee, not this
     * test's.
     */
    @Test
    fun theFlipTouchesNothingButTheQueueAndTheFailure() {
        val strictQueue = ClearOnlyQueueStore()
        TranslationQueue(strictQueue.delegate).enqueue("m1", "conversation-1", GERMAN, APP, NOW)
        val strictActivity = ClearOnlyActivityStore()
        strictActivity.failureRecord =
                TranslationActivity.Failure(
                        HeldSend.HoldReason.UNREACHABLE, "deepseek is unreachable: timeout", "m1", NOW)

        TranslationSettings.clearInterpreterState(strictQueue, TranslationActivity(strictActivity))

        assertEquals("the owed row went", 0, strictQueue.delegate.items.size)
        assertNull("and the failure went", strictActivity.failureRecord)
    }

    /**
     * The sequence the defect was found in, driven end to end on the queue and the settings.
     *
     * <p>On with app Finnish, a message queues with target {@code fi} and its call fails for good, so
     * the row is {@code FAILED} - the state the flip deliberately keeps. The owner then flips the
     * interpreter off and back on with a different app language and taps the covered message. That tap
     * is a decision made in the language in force now, so the revived row must carry {@code sv}, not
     * the {@code fi} it captured - and it was the flip's keeping the failed row that made the stale
     * target survive to be reused at all.
     */
    @Test
    fun theFailedRowKeptByTheFlipIsReRequestedInTheLanguageInForceNow() {
        // On, app Finnish: queued in the language then in force, and failed for good.
        settings.setAppLanguage(APP)
        val failed =
                queue.enqueue("m1", "conversation-1", GERMAN, settings.appLanguage(), NOW)
        queue.failed(failed, false, "deepseek returned 401", NOW)
        assertEquals(TranslationQueue.Item.STATE_FAILED, failed.state)

        // The flip: the interpreter's carried work goes, and this row deliberately does not.
        settings.setAppLanguage(Interpreter.NONE)
        TranslationSettings.clearInterpreterState(queueStore, settings.activity())
        assertNotNull(
                "a failed row is the failures screen's history, so the flip keeps it",
                queueStore.items.get("m1"))

        // Back on in another language, and the owner taps the covered message.
        settings.setAppLanguage("sv")
        val asked =
                queue.enqueue("m1", "conversation-1", GERMAN, settings.appLanguage(), NOW + 60_000)
        queue.makeDue(asked, NOW + 60_000)

        assertEquals(TranslationQueue.Item.STATE_PENDING, failed.state)
        assertEquals("the re-request speaks the language in force now", "sv", failed.targetLanguage)
    }

    /** A queue store that refuses everything but the clear: only {@code clearPending} may be reached. */
    private class ClearOnlyQueueStore : TranslationQueue.Store {
        val delegate = TranslationDoubles.MemoryQueueStore()

        override fun clearPending() {
            delegate.clearPending()
        }

        override fun insert(item: TranslationQueue.Item) {
            throw AssertionError("the flip inserted a queue row")
        }

        override fun nextDue(now: Long): TranslationQueue.Item? {
            throw AssertionError("the flip read the queue")
        }

        override fun due(now: Long, limit: Int): List<TranslationQueue.Item> {
            throw AssertionError("the flip took a due snapshot")
        }

        override fun failedDue(now: Long, limit: Int): List<TranslationQueue.Item> {
            throw AssertionError("the flip read the rows the queue gave up on")
        }

        override fun update(item: TranslationQueue.Item) {
            throw AssertionError("the flip updated a queue row")
        }

        override fun pendingCount(): Int {
            throw AssertionError("the flip counted the queue")
        }

        override fun nextDueAt(): Long? {
            throw AssertionError("the flip asked when the queue is next due")
        }

        override fun makeDue(item: TranslationQueue.Item, now: Long) {
            throw AssertionError("the flip made a row due")
        }
    }

    /** The failure half of the same: only {@code clearFailure} may be reached. */
    private class ClearOnlyActivityStore : TranslationActivity.Store {
        var failureRecord: TranslationActivity.Failure? = null

        override fun clearFailure() {
            failureRecord = null
        }

        override fun day(): String? {
            throw AssertionError("the flip read the activity's day")
        }

        override fun translated(): Int {
            throw AssertionError("the flip read the day's count")
        }

        override fun saveTranslated(day: String?, translated: Int) {
            throw AssertionError("the flip wrote the day's count")
        }

        override fun failure(): TranslationActivity.Failure? {
            throw AssertionError("the flip read the failure record")
        }

        override fun saveFailure(failure: TranslationActivity.Failure) {
            throw AssertionError("the flip wrote a failure record")
        }
    }

    /**
     * Every rule the off state turns a decision into, beside the on-state answer it replaces. These
     * are the cells that make the list above a list and not a claim: if a rule stopped consulting the
     * mode, the on control would still pass and the off assertion would fail.
     */
    @Test
    fun everyRuleAnswersOffAndTheOnControlDiffers() {
        // The classifier: no verdict but NO_LANGUAGE, so nothing downstream sees a translate.
        assertEquals(
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify(GERMAN, APP, null, ON))
        assertEquals(
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify(GERMAN, APP, null, OFF))
        assertTrue(TranslationDecision.isEligible(peer(GERMAN), ON))
        assertFalse(TranslationDecision.isEligible(peer(GERMAN), OFF))
        assertTrue(TranslationDecision.isRequestable(peer(GERMAN), ON))
        assertFalse(TranslationDecision.isRequestable(peer(GERMAN), OFF))

        // The composer's gate: a confidently foreign draft is refused on and sent as typed off, and an
        // app-language draft into a foreign room is translated on and also sent as typed off. Both
        // answers the gate can give on are here, so neither can be the only one exercised.
        assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE, ComposerGate.verdict(GERMAN, APP, ROOM, null, ON))
        assertEquals(ComposerGate.Verdict.TRANSLATE, ComposerGate.verdict(FINNISH, APP, ROOM, null, ON))
        assertEquals(ComposerGate.Verdict.SEND, ComposerGate.verdict(GERMAN, APP, ROOM, null, OFF))
        assertEquals(ComposerGate.Verdict.SEND, ComposerGate.verdict(FINNISH, APP, ROOM, null, OFF))

        // The held send. The draft that needs translating on is the app language written into a room
        // that speaks another one - a confidently foreign draft is a *prompt* on this path, not a
        // hold, so it would be the wrong control. Off, the same app-language draft is sent as typed.
        assertEquals(
                HeldSend.Action.TRANSLATE,
                HeldSend.decide(FINNISH, APP, ROOM, null, HeldSend.Source.ELSEWHERE, ON))
        assertEquals(
                HeldSend.Action.SEND,
                HeldSend.decide(FINNISH, APP, ROOM, null, HeldSend.Source.ELSEWHERE, OFF))
        assertTrue(HeldSend.needsTranslation(FINNISH, APP, ROOM, null, ON))
        assertFalse(HeldSend.needsTranslation(FINNISH, APP, ROOM, null, OFF))

        // The send verdict: SEND and deliberately not SEND_AND_MARK, because the mark is a write for a
        // rule that is not running.
        assertEquals(
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_NONE, null, null, APP, ROOM, null, OFF))
        assertEquals(
                "on, the same row is held",
                OutgoingTranslation.SendVerdict.HOLD,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_NONE, null, null, APP, ROOM, null, ON))
    }

    /** The display half, the English row, the reading aid and the notes, each with its control. */
    @Test
    fun everyLookAndEveryRowAnswersOffAndTheOnControlDiffers() {
        // The display decision: the stored translation is not shown, above the DONE check.
        assertEquals(
                DisplayedBody.Kind.TRANSLATION,
                DisplayedBody.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, true, ON).kind())
        assertEquals(
                DisplayedBody.Kind.ORIGINAL,
                DisplayedBody.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, true, OFF).kind())
        assertEquals(GERMAN, DisplayedBody.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, true, OFF).text())
        assertTrue(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, GERMAN, null, ON))
        assertFalse(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, GERMAN, null, OFF))

        // One half, never two, and never a readable received original.
        assertTrue(
                BubbleHalves.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, Message.STATUS_RECEIVED, null, ON)
                        .isDivided())
        assertFalse(
                BubbleHalves.of(GERMAN, FINNISH, Message.TRANSLATION_DONE, Message.STATUS_RECEIVED, null, OFF)
                        .isDivided())
        assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, true, true, ON).kind())
        assertEquals(
                SecondHalf.Kind.NONE, SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, true, true, OFF).kind())

        // The reply quote: covered on, the sender's own fallback copy off.
        assertTrue(ReplyQuote.unresolved(ON, "some quoted text").isCovered())
        assertFalse(ReplyQuote.unresolved(OFF, "some quoted text").isCovered())

        // The English row: no row at all off, including the original-was-English exception.
        assertEquals(
                EnglishRow.Kind.BLURRED, EnglishRow.of(true, true, APP, ROOM, "en", false, false, ON).kind())
        assertEquals(
                EnglishRow.Kind.NONE, EnglishRow.of(true, true, APP, ROOM, "en", false, false, OFF).kind())
        assertEquals(EnglishRow.Kind.READABLE, EnglishRow.ofSent(true, ROOM, true, true, ON).kind())
        assertEquals(EnglishRow.Kind.NONE, EnglishRow.ofSent(true, ROOM, true, true, OFF).kind())
        assertEquals(
                EnglishRow.Kind.NONE, EnglishRow.defaults(true, APP, ROOM, "en", true, true, OFF).kind())
        assertTrue(EnglishRow.buysOnArrival(true, false, APP, ON))
        assertFalse(EnglishRow.buysOnArrival(true, false, APP, OFF))
        assertTrue(EnglishRow.needsBuying(GERMAN, null, APP, ON))
        assertFalse(EnglishRow.needsBuying(GERMAN, null, APP, OFF))

        // The reading aid: no token off, so no tap target and no popup.
        assertFalse(GlossText.words(GERMAN, ROOM, ON).isEmpty())
        assertTrue(GlossText.words(GERMAN, ROOM, OFF).isEmpty())
        assertTrue(GlossLookups.mayBegin(ON))
        assertFalse(GlossLookups.mayBegin(OFF))

        // The notes: no key off, and nothing kept.
        assertNotNull(ReviewKey.forBubble(GERMAN, Message.TRANSLATION_DONE, Message.STATUS_SEND, ROOM, ON))
        assertNull(ReviewKey.forBubble(GERMAN, Message.TRANSLATION_DONE, Message.STATUS_SEND, ROOM, OFF))
        ReviewStore.record(cache, GERMAN, ROOM, aReview(), ON)
        assertEquals("the on control writes the row the off cell above did not", 1, cacheStore.entries.size)

        // The language sample: nothing is asked, and nothing is learned from a window.
        assertTrue(LanguageSample.shouldSample(null, null, ON))
        assertFalse(LanguageSample.shouldSample(null, null, OFF))
        assertEquals(
                ROOM, LanguageSample.read(listOf(peer(GERMAN), peer(GERMAN)), APP, ON))
        assertNull(LanguageSample.read(listOf(peer(GERMAN), peer(GERMAN)), APP, OFF))
    }

    /** The gap sweep: nothing chosen, and the same shape on is chosen. */
    @Test
    fun theHistoryPassChoosesNothingAndDecidesNothingOff() {
        val rows = ArrayList(listOf(row("h1", 1L), row("h2", 2L)))

        val on = StartupBacklog.everything(rows, APP, ON)
        assertEquals("the on control chooses the gap", 2, on.size)

        val off = StartupBacklog.everything(rows, APP, OFF)
        assertTrue("off, nothing is chosen", off.isEmpty())

        assertTrue(StartupBacklog.eligible(row("h1", 1L), APP, ON))
        assertFalse(StartupBacklog.eligible(row("h1", 1L), APP, OFF))
    }
}
