package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/** Backoff: growing waits, and a hard stop so nothing is retried forever. */
class TranslationBackoffTest {

    @Test
    fun theDelaysGrow() {
        var previous = 0L
        for (attempt in 1..TranslationBackoff.MAX_ATTEMPTS) {
            val delay = TranslationBackoff.delayMillis(attempt)
            Assert.assertTrue("delay " + attempt + " did not grow: " + delay, delay > previous)
            previous = delay
        }
    }

    @Test
    fun theFirstDelayIsLongEnoughToNotHammerTheApi() {
        Assert.assertTrue(TranslationBackoff.delayMillis(1) >= 1_000L)
    }

    @Test
    fun attemptZeroAndNegativeUseTheFirstDelay() {
        Assert.assertEquals(
                TranslationBackoff.delayMillis(1), TranslationBackoff.delayMillis(0))
        Assert.assertEquals(
                TranslationBackoff.delayMillis(1), TranslationBackoff.delayMillis(-3))
    }

    @Test
    fun beyondTheTableTheLastDelayIsKept() {
        val last = TranslationBackoff.delayMillis(TranslationBackoff.MAX_ATTEMPTS)
        Assert.assertEquals(last, TranslationBackoff.delayMillis(TranslationBackoff.MAX_ATTEMPTS + 10))
        Assert.assertTrue("a delay must stay a delay, not grow without bound", last <= 24 * 3_600_000L)
    }

    @Test
    fun retryableUntilTheLimitAndNotAfter() {
        for (attempts in 0 until TranslationBackoff.MAX_ATTEMPTS) {
            Assert.assertTrue(
                    "attempt " + attempts + " should still be retryable",
                    TranslationBackoff.retryable(attempts))
        }
        Assert.assertFalse(
                TranslationBackoff.retryable(TranslationBackoff.MAX_ATTEMPTS))
    }
}
