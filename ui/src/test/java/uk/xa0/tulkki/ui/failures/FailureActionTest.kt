package uk.xa0.tulkki.ui.failures

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.TranslationFailures

/**
 * The failures row's action decision, which is the whole of "a row affords the action its kind
 * permits" (docs/MIGRATION.md item 17) that can be exercised on the JVM: [FailureAction] is a pure
 * function of the row's own disposition, so no device, no `Context` and no Composable are needed.
 *
 * <p>The two halves want opposite things - a received message is the queue's and may be asked for
 * now, a held send is the owner's and has the bar's retry and its one exception - so the cells pin
 * each disposition separately rather than only the union.
 */
class FailureActionTest {

    /** A received row the queue still means to retry: the cover's tap is still the owner's. */
    @Test
    fun aReceivedFailureTheQueueWillRetryOffersTranslateNow() {
        Assert.assertEquals(
            listOf(FailureAction.TRANSLATE_NOW),
            FailureAction.forFailure(TranslationFailures.Next.RETRY),
        )
    }

    /** A received row the schedule ran out on is exactly the one a tap exists for. */
    @Test
    fun aReceivedFailureTheQueueGaveUpOnOffersTranslateNow() {
        Assert.assertEquals(
            listOf(FailureAction.TRANSLATE_NOW),
            FailureAction.forFailure(TranslationFailures.Next.GAVE_UP),
        )
    }

    /** A held send offers the bar's two, retry first: sending the original is the exception. */
    @Test
    fun aHeldSendOffersTheBarsRetryAndItsOneException() {
        Assert.assertEquals(
            listOf(FailureAction.RETRY, FailureAction.SEND_AS_WRITTEN),
            FailureAction.forFailure(TranslationFailures.Next.HELD),
        )
    }

    /** A disposition no factory produces must not become a button. */
    @Test
    fun anUnknownDispositionOffersNothing() {
        Assert.assertTrue(FailureAction.forFailure(null).isEmpty())
    }

    /**
     * And every disposition the deciding type can produce is answered, so a `Next` added later is a
     * red cell rather than a row that draws and does nothing.
     */
    @Test
    fun everyDispositionTheRowCanCarryOffersSomething() {
        for (next in TranslationFailures.Next.values()) {
            Assert.assertFalse("$next offers no action at all", FailureAction.forFailure(next).isEmpty())
        }
    }
}
