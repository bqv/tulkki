package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/** The queue's state machine: what is retried, what is not, and what is never looked at again. */
class TranslationQueueTest {

    private val NOW = 1_800_000_000_000L

    private val store = TranslationDoubles.MemoryQueueStore()
    private val queue = TranslationQueue(store)

    private fun enqueue(body: String): TranslationQueue.Item {
        return queue.enqueue("message-" + body, "conversation-1", body, "fi", NOW)
    }

    @Test
    fun aNewItemIsPendingAndDueImmediately() {
        val item = enqueue("Hello")
        Assert.assertTrue(item.isPending())
        Assert.assertTrue(item.due(NOW))
        Assert.assertEquals(0, item.attempts)
        Assert.assertEquals(1, queue.pendingCount())
        Assert.assertEquals(NOW, queue.nextDueAt())
    }

    @Test
    fun theItemCarriesTheCacheKeyOfItsOwnText() {
        val item = enqueue("Hello")
        Assert.assertEquals(CacheKey.of("Hello", "fi"), item.cacheKey)
    }

    @Test
    fun successIsDoneAndNeverDueAgain() {
        val item = enqueue("Hello")
        queue.succeeded(item, NOW)
        Assert.assertEquals(TranslationQueue.Item.STATE_DONE, item.state)
        Assert.assertFalse(item.due(NOW))
        Assert.assertNull(queue.nextDue(NOW))
        Assert.assertEquals(0, queue.pendingCount())
        Assert.assertNull(queue.nextDueAt())
    }

    @Test
    fun aFailureKeepsTheMomentItHappened() {
        val item = enqueue("Hello")
        Assert.assertEquals("nothing has failed yet", 0L, item.failedAt)
        queue.failed(item, true, "deepseek is unreachable", NOW)
        Assert.assertEquals(NOW, item.failedAt)
        // The next failed attempt is the one the queue is timing, not the first.
        queue.failed(item, true, "deepseek is unreachable", NOW + 60_000L)
        Assert.assertEquals(NOW + 60_000L, item.failedAt)
    }

    @Test
    fun aRetryableFailureWaitsAndThenComesBack() {
        val item = enqueue("Hello")
        queue.failed(item, true, "deepseek returned 429", NOW)

        Assert.assertTrue("a retryable failure stays pending", item.isPending())
        Assert.assertEquals(1, item.attempts)
        Assert.assertEquals("deepseek returned 429", item.lastError)
        Assert.assertFalse("not due again this instant", item.due(NOW))
        Assert.assertEquals(NOW + TranslationBackoff.delayMillis(1), item.nextAttemptAt)
        Assert.assertEquals(1, queue.pendingCount())

        val later = item.nextAttemptAt
        Assert.assertNull("nothing is due before its time", queue.nextDue(later - 1))
        Assert.assertSame(item, queue.nextDue(later))
    }

    @Test
    fun retryableFailuresGiveUpAtTheLimit() {
        val item = enqueue("Hello")
        for (attempt in 1 until TranslationBackoff.MAX_ATTEMPTS) {
            queue.failed(item, true, "deepseek is unreachable", NOW)
            Assert.assertTrue("attempt " + attempt + " should still be pending", item.isPending())
        }
        queue.failed(item, true, "deepseek is unreachable", NOW)
        Assert.assertEquals(
                "the last attempt makes it a failure, not an eternal retry",
                TranslationQueue.Item.STATE_FAILED,
                item.state)
        Assert.assertEquals(TranslationBackoff.MAX_ATTEMPTS, item.attempts)
        Assert.assertNull(queue.nextDue(NOW + 10L * TranslationBackoff.delayMillis(1)))
    }

    @Test
    fun aNonRetryableFailureIsFinalImmediately() {
        val item = enqueue("Hello")
        queue.failed(item, false, "deepseek returned 401", NOW)
        Assert.assertEquals(TranslationQueue.Item.STATE_FAILED, item.state)
        Assert.assertEquals(1, item.attempts)
        Assert.assertFalse(item.due(NOW))
        Assert.assertNull(queue.nextDue(NOW))
        Assert.assertEquals(0, queue.pendingCount())
    }

    @Test
    fun theOldestDueItemComesFirst() {
        val first =
                queue.enqueue("one", "conversation-1", "one", "fi", NOW - 1_000)
        queue.enqueue("two", "conversation-1", "two", "fi", NOW)
        Assert.assertSame(first, queue.nextDue(NOW))
    }

    @Test
    fun aNotYetDueItemDoesNotBlockTheRest() {
        val blocked = queue.enqueue("a", "c", "a", "fi", NOW)
        queue.failed(blocked, true, "429", NOW)
        queue.enqueue("b", "c", "b", "fi", NOW)
        val next = queue.nextDue(NOW)
        Assert.assertNotNull(next)
        Assert.assertEquals("b", next!!.messageUuid)
    }

    @Test
    fun enqueueingTheSameMessageTwiceKeepsOneRowAndItsProgress() {
        val item = enqueue("Hello")
        queue.failed(item, true, "429", NOW)
        val attempts = item.attempts
        val due = item.nextAttemptAt

        queue.enqueue(item.messageUuid, "conversation-1", "Hello", "fi", NOW + 5_000)

        Assert.assertEquals(1, store.items.size)
        val stored = store.items.get(item.messageUuid)!!
        Assert.assertEquals("a duplicate insert must not reset the backoff", attempts, stored.attempts)
        Assert.assertEquals(due, stored.nextAttemptAt)
    }

    @Test
    fun theNextDueTimeIsTheEarliestPendingOne() {
        val item = queue.enqueue("Hello", "c", "Hello", "fi", NOW)
        queue.failed(item, true, "429", NOW)
        Assert.assertEquals(item.nextAttemptAt, queue.nextDueAt())
    }

    // -- asking for one message that gave up --------------------------------------------------------

    @Test
    fun askingAgainMakesAFailedItemDueWithFreshAttempts() {
        val item = enqueue("Hello")
        for (attempt in 0 until TranslationBackoff.MAX_ATTEMPTS) {
            queue.failed(item, true, "deepseek is unreachable", NOW)
        }
        Assert.assertEquals(TranslationQueue.Item.STATE_FAILED, item.state)
        Assert.assertNull(queue.nextDue(NOW))

        val later = NOW + 60_000
        queue.makeDue(item, later)

        Assert.assertEquals(TranslationQueue.Item.STATE_PENDING, item.state)
        Assert.assertEquals(0, item.attempts)
        Assert.assertNull(item.lastError)
        Assert.assertEquals(later, item.nextAttemptAt)
        Assert.assertSame(item, queue.nextDue(later))
    }

    @Test
    fun askingAgainStopsWaitingOutABackoff() {
        val item = enqueue("Hello")
        queue.failed(item, true, "429", NOW)
        Assert.assertNull("still waiting out its backoff", queue.nextDue(NOW))

        queue.makeDue(item, NOW)

        Assert.assertEquals(NOW, item.nextAttemptAt)
        Assert.assertSame(item, queue.nextDue(NOW))
    }

    @Test
    fun askingAgainDoesNothingForAFinishedOrUnknownMessage() {
        val done = enqueue("Hello")
        queue.succeeded(done, NOW)
        queue.makeDue(done, NOW)
        Assert.assertEquals(TranslationQueue.Item.STATE_DONE, done.state)
        Assert.assertNull(queue.nextDue(NOW))

        // An item that was never inserted: the store has no row for this uuid, so there is nothing
        // to revive and nothing is added.
        queue.makeDue(
                TranslationQueue.Item(
                        "no-such-message",
                        "conversation-1",
                        "Hello",
                        "fi",
                        CacheKey.of("Hello", "fi"),
                        NOW),
                NOW)
        queue.makeDue(null, NOW)
        Assert.assertEquals(1, store.items.size)
    }

    /**
     * The queue exists to hold work that is owed, and the body it holds is the message's own text -
     * for everything the automatic pass touches, the concealed original. A done row is never read
     * again, so keeping the text would be a second permanent copy of it next to the message row. The
     * cache key stays: what was paid for is still identified, and nothing is re-derived.
     */
    @Test
    fun aHandledRowStopsCarryingTheMessage() {
        val item = enqueue("Hallo, wie geht es dir?")
        queue.succeeded(item, NOW)
        Assert.assertEquals("", item.body)
        Assert.assertEquals("", store.items.get(item.messageUuid)!!.body)
        Assert.assertEquals(CacheKey.of("Hallo, wie geht es dir?", "fi"), item.cacheKey)
    }

    /**
     * The defect this change closes. A row that failed keeps the target language it captured when it
     * was queued; the automatic insert is {@code CONFLICT_IGNORE}, so asking for the message again
     * used to revive the row still pointed at that old language, and the buyer answered in it. The
     * owner's tap is a new decision, so the revival repoints the row at the language in force now.
     */
    @Test
    fun askingAgainRepointsTheRowAtTheLanguageInForceNow() {
        val failed = enqueue("Hallo")
        queue.failed(failed, false, "deepseek returned 401", NOW)
        Assert.assertEquals(TranslationQueue.Item.STATE_FAILED, failed.state)

        // The app language has moved on since the row was queued, and the owner taps the message.
        val asked =
                queue.enqueue(failed.messageUuid, "conversation-1", "Hallo", "sv", NOW + 60_000)
        queue.makeDue(asked, NOW + 60_000)

        Assert.assertEquals(TranslationQueue.Item.STATE_PENDING, failed.state)
        Assert.assertEquals(0, failed.attempts)
        Assert.assertNull(failed.lastError)
        Assert.assertEquals(NOW + 60_000, failed.nextAttemptAt)
        Assert.assertEquals(
                "the language in force now, not the one the row captured when it was queued",
                "sv",
                failed.targetLanguage)
        Assert.assertEquals(CacheKey.of("Hallo", "sv"), failed.cacheKey)
        Assert.assertSame(failed, queue.nextDue(NOW + 60_000))
    }

    /**
     * The automatic path is not what changed. {@code enqueue}'s insert is still
     * {@code CONFLICT_IGNORE}, so a message the automatic pass offers again - a catch-up re-offering
     * a row the queue already holds - neither revives one that has given up nor repoints one that is
     * waiting out a backoff at the language in force now. Only the owner's tap does that.
     */
    @Test
    fun theAutomaticEnqueueStillLeavesAnExistingRowAlone() {
        val failed = enqueue("Hallo")
        queue.failed(failed, false, "deepseek returned 401", NOW)
        val waiting = queue.enqueue("waiting", "c", "waiting", "fi", NOW)
        queue.failed(waiting, true, "deepseek returned 429", NOW)
        val waitingUntil = waiting.nextAttemptAt

        queue.enqueue(failed.messageUuid, "conversation-1", "Hallo", "sv", NOW + 5_000)
        queue.enqueue(waiting.messageUuid, "c", "waiting", "sv", NOW + 5_000)

        Assert.assertEquals(2, store.items.size)
        Assert.assertEquals(
                "a row that gave up is not revived by an automatic offer",
                TranslationQueue.Item.STATE_FAILED,
                failed.state)
        Assert.assertEquals("nor repointed", "fi", failed.targetLanguage)
        Assert.assertEquals("a waiting row keeps the target it captured", "fi", waiting.targetLanguage)
        Assert.assertEquals("and keeps waiting out its backoff", waitingUntil, waiting.nextAttemptAt)
    }

    /**
     * A done row is neither revived nor repointed, so a translation that already succeeded is not
     * bought a second time even if something asks for the message again. The fix must not weaken this
     * half of the rule.
     */
    @Test
    fun askingAgainDoesNotTouchADoneRow() {
        val done = enqueue("Hallo")
        queue.succeeded(done, NOW)
        Assert.assertEquals(TranslationQueue.Item.STATE_DONE, done.state)

        val asked =
                queue.enqueue(done.messageUuid, "conversation-1", "Hallo", "sv", NOW + 60_000)
        queue.makeDue(asked, NOW + 60_000)

        Assert.assertEquals(TranslationQueue.Item.STATE_DONE, done.state)
        Assert.assertEquals(
                "a translation that already succeeded keeps its language", "fi", done.targetLanguage)
        Assert.assertNull("and it is never due again", queue.nextDue(NOW + 60_000))
        Assert.assertEquals(1, store.items.size)
    }

    // -- the clear a flip to off performs ------------------------------------------------------------

    /**
     * The clear takes the work the queue still owes - a row waiting out a backoff included, because
     * that is owed work and not a finished one - and leaves alone the two states that are not owed:
     * a done row, which no longer carries the message, and a failed row, which is the failures
     * screen's own list and the owner's to read.
     *
     * <p>Every pending row goes, not merely the ones due at the moment of the flip: the thing the
     * clear exists for is the target language a row captured while the interpreter was on, and a
     * backoff does not make that value any less stale.
     */
    @Test
    fun clearingDropsTheOwedRowsAndKeepsTheFinishedOnes() {
        val owed = enqueue("Hello")
        val waiting = queue.enqueue("waiting", "c", "waiting", "fi", NOW)
        queue.failed(waiting, true, "deepseek returned 429", NOW)
        val done = queue.enqueue("done", "c", "done", "fi", NOW)
        queue.succeeded(done, NOW)
        val givenUp = queue.enqueue("failed", "c", "failed", "fi", NOW)
        queue.failed(givenUp, false, "deepseek returned 401", NOW)
        Assert.assertEquals(4, store.items.size)

        store.clearPending()

        Assert.assertNull("nothing is owed any more", queue.nextDue(NOW))
        Assert.assertEquals(0, queue.pendingCount())
        Assert.assertNull(queue.nextDueAt())
        Assert.assertNull("the due row is gone from the store", store.items.get(owed.messageUuid))
        Assert.assertNull(
                "and so is the one waiting out its backoff", store.items.get(waiting.messageUuid))
        Assert.assertNotNull(
                "a handled row is not work the queue owes", store.items.get(done.messageUuid))
        Assert.assertNotNull(
                "nor is a failure: the failures screen reads it",
                store.items.get(givenUp.messageUuid))
    }
}
