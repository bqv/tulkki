package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The app's account of itself: how many messages were translated today, and the last thing that
 * stopped one. This is what the usage screen reads and what a tapped message's cover quotes, so the
 * day rollover and the "whose failure was it" rule are the parts that have to be right.
 *
 * <p>It lives in {@code :translation} because that is where the class lives: pair 8b moved the record
 * down into {@code :data} to kill D9, and 3.7 pair 12 moved it home, so the test follows it out of
 * {@code :data}'s test source. The store is in-file rather than borrowed from this module's own test
 * doubles, so the arithmetic is tested against nothing but the record.
 */
class TranslationActivityTest {

    private val TODAY = "2026-02-01"
    private val TOMORROW = "2026-02-02"

    /** The store, in memory: the whole of what the class persists. */
    private class MemoryStore : TranslationActivity.Store {
        var day: String? = null
        var translated = 0
        var failure: TranslationActivity.Failure? = null

        override fun day(): String? {
            return day
        }

        override fun translated(): Int {
            return translated
        }

        override fun saveTranslated(day: String?, translated: Int) {
            this.day = day
            this.translated = translated
        }

        override fun failure(): TranslationActivity.Failure? {
            return failure
        }

        override fun saveFailure(failure: TranslationActivity.Failure) {
            this.failure = failure
        }

        override fun clearFailure() {
            failure = null
        }
    }

    private val store = MemoryStore()
    private val activity = TranslationActivity(store)

    @Test
    fun nothingHasHappenedYet() {
        Assert.assertEquals(0, activity.translatedToday(TODAY))
        Assert.assertNull(activity.lastFailure())
        Assert.assertNull(activity.reasonFor("message-1"))
    }

    @Test
    fun countingAddsUpWithinTheDay() {
        activity.addTranslated(TODAY, 3)
        activity.addTranslated(TODAY, 2)
        Assert.assertEquals(5, activity.translatedToday(TODAY))
    }

    @Test
    fun yesterdaysCountIsNotTodays() {
        activity.addTranslated(TODAY, 7)
        Assert.assertEquals(7, activity.translatedToday(TODAY))
        Assert.assertEquals(0, activity.translatedToday(TOMORROW))
    }

    @Test
    fun theFirstMessageOfANewDayStartsFromZero() {
        // A counter that has to be reset by something else is a counter that can fail to be reset.
        activity.addTranslated(TODAY, 7)
        activity.addTranslated(TOMORROW, 1)
        Assert.assertEquals(1, activity.translatedToday(TOMORROW))
        Assert.assertEquals(0, activity.translatedToday(TODAY))
    }

    @Test
    fun countingNothingIsNotAnEvent() {
        activity.addTranslated(TODAY, 0)
        activity.addTranslated(TODAY, -4)
        activity.addTranslated(null, 5)
        Assert.assertEquals(0, activity.translatedToday(TODAY))
    }

    @Test
    fun theLastFailureKeepsItsReasonAndItsOwnWords() {
        activity.recordFailure(
                HeldSend.HoldReason.UNREACHABLE, "deepseek is unreachable: timeout", "m-1", 1000L)
        val failure = activity.lastFailure()!!
        Assert.assertNotNull(failure)
        Assert.assertEquals(HeldSend.HoldReason.UNREACHABLE, failure.reason)
        Assert.assertEquals("deepseek is unreachable: timeout", failure.detail)
        Assert.assertEquals("m-1", failure.messageUuid)
        Assert.assertEquals(1000L, failure.at)
    }

    @Test
    fun theRecordedFailureIsAlsoTheEnginesPortValue() {
        // The engine never sees this class, only the port it declares; the two readings must agree.
        activity.recordFailure(HeldSend.HoldReason.NO_CREDIT, null, "m-2", 2000L)
        val recorded: TranslationActivityPort.RecordedFailure = activity.lastFailure()!!
        Assert.assertEquals(HeldSend.HoldReason.NO_CREDIT, recorded.reason())
        Assert.assertNull(recorded.detail())
        Assert.assertEquals("m-2", recorded.messageUuid())
        Assert.assertEquals(2000L, recorded.at())
    }

    @Test
    fun onlyTheNewestFailureIsKept() {
        activity.recordFailure(HeldSend.HoldReason.UNREACHABLE, null, "m-1", 1000L)
        activity.recordFailure(HeldSend.HoldReason.NO_CREDIT, "insufficient balance", "m-2", 2000L)
        Assert.assertEquals(HeldSend.HoldReason.NO_CREDIT, activity.lastFailure()!!.reason)
        Assert.assertEquals("m-2", activity.lastFailure()!!.messageUuid)
    }

    @Test
    fun aFailureBelongsToTheMessageItHappenedTo() {
        activity.recordFailure(HeldSend.HoldReason.REJECTED_KEY, null, "m-1", 1000L)
        Assert.assertEquals(HeldSend.HoldReason.REJECTED_KEY, activity.reasonFor("m-1"))
        Assert.assertNull(
                "another message's failure is not this message's to show",
                activity.reasonFor("m-2"))
        Assert.assertNull(activity.reasonFor(null))
    }

    @Test
    fun aReasonThatWasNeverSetIsNotAFailure() {
        activity.recordFailure(null, "nothing to report", null, 1000L)
        Assert.assertNull(activity.lastFailure())
    }

    @Test
    fun theCountAndTheFailureSurviveTheProcess() {
        // Two instances over one store: what the app writes, the usage screen can read.
        activity.addTranslated(TODAY, 4)
        activity.recordFailure(HeldSend.HoldReason.CAP_REACHED, null, "m-9", 42L)

        val reopened = TranslationActivity(store)
        Assert.assertEquals(4, reopened.translatedToday(TODAY))
        Assert.assertEquals(HeldSend.HoldReason.CAP_REACHED, reopened.reasonFor("m-9"))
    }

    // -- forgetting the failure when the interpreter goes off -------------------------------------

    /**
     * The clear takes the record entirely: no last failure, and so nothing left to put on the cover
     * of the message the failure happened to. That record is the interpreter's - it describes a
     * DeepSeek call - and off there is no call for it to describe.
     */
    @Test
    fun clearingTheFailureForgetsTheReasonAndTheMessageItHappenedTo() {
        activity.recordFailure(
                HeldSend.HoldReason.UNREACHABLE, "deepseek is unreachable: timeout", "m-1", 1000L)

        activity.clearFailure()

        Assert.assertNull(activity.lastFailure())
        Assert.assertNull(activity.reasonFor("m-1"))
    }

    /**
     * The day's count survives the clear: it is an account of work already done, not state the
     * interpreter was still carrying, and taking it would erase what the usage screen exists to show.
     */
    @Test
    fun clearingTheFailureLeavesTheDaysCountAlone() {
        activity.addTranslated(TODAY, 4)
        activity.recordFailure(HeldSend.HoldReason.NO_CREDIT, "insufficient balance", "m-2", 2000L)

        activity.clearFailure()

        Assert.assertEquals(4, activity.translatedToday(TODAY))
        Assert.assertNull(activity.lastFailure())
    }
}
