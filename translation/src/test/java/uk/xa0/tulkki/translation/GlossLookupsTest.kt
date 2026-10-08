package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which gloss question is the current one, and what a superseded answer is allowed to keep.
 *
 * <p>These are the decisions {@link GlossLookup} cannot make for itself: it posts through a `Handler`
 * and reads a `Context`, neither of which exists in a unit test. The parts that do not need a device
 * live in {@link GlossLookups}, and this is where they are pinned.
 */
class GlossLookupsTest {

    /** A stand-in for the socket call: counts how often it was actually stopped. */
    private class Socket : GlossLookups.Cancellable {
        var stopped = 0

        override fun cancel() {
            this.stopped++
        }
    }

    /** A stand-in for the two effects of a finished lookup: the shared counter, and the cache. */
    private class Effects : GlossLookups.Settlement {
        val counted = ArrayList<Int>()
        var kept = 0

        override fun count(usage: DeepSeekClient.Usage) {
            this.counted.add(usage.totalTokens)
        }

        override fun keep() {
            this.kept++
        }
    }

    /** What the API reported for a call, in the shape the settlement takes. */
    private fun reported(totalTokens: Int): DeepSeekClient.Usage {
        return DeepSeekClient.Usage(totalTokens - 10, 10, 0, Math.max(0, totalTokens - 10), totalTokens)
    }

    @Test
    fun aNewQuestionTakesThePlaceOfTheOldOne() {
        val lookups = GlossLookups()

        val first = lookups.begin()
        assertSame(first, lookups.current())
        assertFalse(first.isCancelled())

        val second = lookups.begin()

        assertSame(second, lookups.current())
        assertTrue("the question the owner moved on from is abandoned", first.isCancelled())
        assertFalse("the one just asked is the one being asked", second.isCancelled())
    }

    @Test
    fun theCallThatIsAlreadyOutIsCancelledWhenTheNextTapArrives() {
        val lookups = GlossLookups()
        val socket = Socket()

        val first = lookups.begin()
        first.attach(socket)
        assertEquals("nothing to stop yet", 0, socket.stopped)

        lookups.begin()

        assertEquals("the superseded call has to stop, or its tokens are spent anyway", 1, socket.stopped)
    }

    @Test
    fun aCallHandedInAfterTheCancellationIsStoppedOnTheSpot() {
        // The window this pins is the common one: the worker is still reading the cache when the next
        // word is tapped, so the cancellation arrives before the call exists. Attaching must not
        // resurrect the work - the request has to be stopped before it is ever executed.
        val lookups = GlossLookups()
        val first = lookups.begin()
        lookups.begin()

        val socket = Socket()
        first.attach(socket)

        assertEquals(1, socket.stopped)
    }

    @Test
    fun cancellingTwiceStopsTheCallOnce() {
        val lookups = GlossLookups()
        val socket = Socket()
        val attempt = lookups.begin()
        attempt.attach(socket)

        attempt.cancel()
        attempt.cancel()
        lookups.begin()

        assertEquals(1, socket.stopped)
    }

    @Test
    fun aCancelAndAnAttachInEitherOrderStillStopTheCall() {
        // Both orders are real: the tap can land while the worker is building the call, or after it
        // is on the wire. Whichever wins the lock, the socket ends up stopped exactly once.
        for (round in 0 until 200) {
            val lookups = GlossLookups()
            val attempt = lookups.begin()
            val socket = Socket()
            val go = CountDownLatch(1)

            val cancel =
                    Thread {
                        await(go)
                        lookups.begin()
                    }
            val attach =
                    Thread {
                        await(go)
                        attempt.attach(socket)
                    }
            cancel.start()
            attach.start()
            go.countDown()
            cancel.join()
            attach.join()

            assertEquals("round " + round, 1, socket.stopped)
        }
    }

    @Test
    fun aSupersededAnswerIsDroppedAndOnlyWhatWasSpentIsCounted() {
        val lookups = GlossLookups()
        val first = lookups.begin()
        lookups.begin()
        val effects = Effects()

        assertFalse(first.settle(reported(120), effects))

        assertEquals("the tokens were spent and follow the invoice, not the attention",
                java.util.Collections.singletonList(120), effects.counted)
        assertEquals("a question the owner abandoned writes nothing", 0, effects.kept)
    }

    @Test
    fun aCurrentAnswerIsCountedOnceAndKeptOnce() {
        val lookups = GlossLookups()
        val attempt = lookups.begin()
        val effects = Effects()

        assertTrue(attempt.settle(reported(120), effects))

        assertEquals(java.util.Collections.singletonList(120), effects.counted)
        assertEquals(1, effects.kept)
    }

    @Test
    fun nothingIsCountedWhenTheApiReportedNothing() {
        // A request cancelled before the API answered has no usage to report, and a zero that was
        // invented would spend the owner's cap on a call that never happened.
        val lookups = GlossLookups()
        val attempt = lookups.begin()
        val effects = Effects()

        attempt.settle(DeepSeekClient.Usage.none(), effects)

        assertTrue(effects.counted.isEmpty())
        assertEquals(1, effects.kept)
    }

    @Test
    fun finishingDoesNotDisturbTheQuestionThatTookItsPlace() {
        val lookups = GlossLookups()
        val first = lookups.begin()
        val second = lookups.begin()

        lookups.finish(first)

        assertSame("a slow lookup's tidying must not clear the tap that superseded it",
                second, lookups.current())
        assertFalse(second.isCancelled())

        lookups.finish(second)
        assertNull(lookups.current())
    }

    @Test
    fun aFinishedQuestionIsNotCancelled() {
        // Finishing is not abandoning: the answer arrived and was delivered, so nothing about it is
        // a cancellation, and the failed-send vocabulary must not claim otherwise.
        val lookups = GlossLookups()
        val attempt = lookups.begin()

        lookups.finish(attempt)

        assertFalse(attempt.isCancelled())
    }

    // ---- whether a question may start at all ----

    /**
     * The off state's whole decision, and the reason it is a static here rather than an `if` in
     * {@link GlossLookup}: that class reads a `Context` and answers through a `Handler`,
     * so an off interpreter cannot be handed to it in a JVM test, let alone heard. The value can, and
     * this is where the couple of lines it takes are pinned.
     */
    @Test
    fun anOffInterpreterMayNotBeginAQuestion() {
        assertTrue("fi x de: interpreting", GlossLookups.mayBegin(Interpreter.of("fi", "de")))
        assertTrue(
                "fi x en: English is a language like any other now, so this pair interprets",
                GlossLookups.mayBegin(Interpreter.of("fi", "en")))
        assertFalse(
                "fi x fi: one language on both sides, so nothing to gloss into",
                GlossLookups.mayBegin(Interpreter.of("fi", "fi")))
        assertFalse(
                "the null sentinel is not a licence either",
                GlossLookups.mayBegin(Interpreter.of("fi", Interpreter.NONE)))
        assertFalse("and no interpreter at all is off", GlossLookups.mayBegin(null))
    }

    /** Wait for the latch, turning an interrupt into a failure the test can see. */
    private fun await(latch: CountDownLatch) {
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError(e)
        }
    }
}
