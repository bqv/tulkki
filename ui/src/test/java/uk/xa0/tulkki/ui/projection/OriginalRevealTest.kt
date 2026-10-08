package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.HeldSend

/**
 * Item 17's decision five as a rule: which rows may offer their own original.
 *
 * <p>The two cells that carry the design are `aPendingRowIsNeverEligible` and
 * `theClearableCausesAreAboutToBeRetriedAndAreNot`. Everything else is the direction and the fact that
 * the translation is not drawn.
 */
class OriginalRevealTest {

    private fun incoming(reason: HeldSend.HoldReason?) =
        OriginalReveal.eligible(Direction.INCOMING, needsTranslation = true, reason = reason)

    @Test
    fun aFailedReceivedRowMayOfferItsOriginal() {
        Assert.assertTrue(incoming(HeldSend.HoldReason.FAILED))
    }

    @Test
    fun aPendingRowIsNeverEligible() {
        Assert.assertFalse("nothing was attempted: there is no failure to be the deciding fact", incoming(null))
    }

    @Test
    fun theClearableCausesAreAboutToBeRetriedAndAreNot() {
        // Each of these is cleared by an event outside the row (`FailureCause.clearable`), and the
        // received row re-enqueues itself when it clears - so the app is already going to answer, and the
        // original is not a dead end yet.
        for (reason in
            listOf(
                HeldSend.HoldReason.NO_KEY,
                HeldSend.HoldReason.CAP_REACHED,
                HeldSend.HoldReason.NO_CREDIT,
                HeldSend.HoldReason.UNREACHABLE,
                HeldSend.HoldReason.REJECTED_KEY,
            )) {
            Assert.assertFalse("$reason is cleared by an event, so the row will be retried", incoming(reason))
        }
    }

    @Test
    fun theSendsOwnHoldIsNeverAReceivedFailure() {
        Assert.assertFalse(
            "the conversation's language is a question for what is sent, never for what arrived",
            incoming(HeldSend.HoldReason.UNKNOWN_LANGUAGE),
        )
    }

    @Test
    fun anOwnRowNeverOffersSomebodyElsesOriginal() {
        Assert.assertFalse(
            "the owner's own half is readable by `SecondHalf`'s ordinary rule, not by this exception",
            OriginalReveal.eligible(Direction.OUTGOING, needsTranslation = true, reason = HeldSend.HoldReason.FAILED),
        )
    }

    @Test
    fun aRowWhoseTranslationIsDrawnIsNotEligible() {
        Assert.assertFalse(
            "there is nothing missing to make up for",
            OriginalReveal.eligible(Direction.INCOMING, needsTranslation = false, reason = HeldSend.HoldReason.FAILED),
        )
    }
}
