package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.Collections
import org.junit.Assert
import org.junit.Test

import uk.xa0.tulkki.data.TranslationTables

/**
 * The list behind the failures screen: which rows still stand, in what order, how many, and what each
 * one's reason and time are - for the received messages the queue owes an answer for and for the sends
 * that never left.
 *
 * <p>{@link #bothProjectionsCarryTheMessageText()} is the one that changed meaning, and it is the
 * owner's reversal rather than a drift: the screen now shows the message a failure is about, so the
 * projections select the body and a row carries it. What has not changed is that the cache key - which
 * is derived from the text - has no business in either read.
 */
class TranslationFailuresTest {

    private val CONVERSATION = "conversation-1"
    private val JID = "matti@example.test"

    /** The text a failure is about, which the screen now shows. */
    private val BODY = "the message that failed"

    /** The app's one kept failure, belonging to {@code messageUuid}. */
    private fun recorded(
            messageUuid: String,
            reason: HeldSend.HoldReason,
            at: Long): TranslationActivityPort.RecordedFailure {
        return TranslationDoubles.RecordedFailure(
                reason, "deepseek said: " + reason, messageUuid, at)
    }

    private fun received(
            uuid: String,
            state: Int,
            attempts: Int,
            createdAt: Long,
            failedAt: Long,
            error: String?): TranslationFailures.Failure {
        return TranslationFailures.received(
                uuid, CONVERSATION, JID, BODY, state, attempts, createdAt, failedAt, error, null,
                null)
    }

    /** The ordinary case since schema 73: the queue recorded when the failure happened. */
    private fun pending(
            uuid: String, attempts: Int, at: Long, error: String?): TranslationFailures.Failure {
        return received(uuid, TranslationQueue.Item.STATE_PENDING, attempts, 1L, at, error)
    }

    private fun failed(
            uuid: String, attempts: Int, at: Long, error: String?): TranslationFailures.Failure {
        return received(uuid, TranslationQueue.Item.STATE_FAILED, attempts, 1L, at, error)
    }

    /** A row written before schema 73, or one from a database where that migration did not land. */
    private fun arrived(
            uuid: String,
            attempts: Int,
            createdAt: Long,
            error: String?): TranslationFailures.Failure {
        return received(
                uuid, TranslationQueue.Item.STATE_PENDING, attempts, createdAt, 0L, error)
    }

    // -- what the rows carry ----------------------------------------------------------------------

    /** And the text arrives on the row, both ways, or the projections are decoration. */
    @Test
    fun theMessageTextArrivesOnTheRow() {
        Assert.assertEquals(
                "a received failure carries the words it is about",
                BODY,
                pending("m-1", 1, 10L, "timeout").body)
        Assert.assertEquals(
                "and so does a held send",
                BODY,
                TranslationFailures.send("m-9", CONVERSATION, JID, BODY, 500L, null).body)
    }

    @Test
    fun theQueueProjectionAsksForTheFactsARowNeeds() {
        // Kept as a statement about the *deciding* type: the projections themselves moved to `:data`
        // (TranslationFailureQueries) in S5-6, and their column pins moved with them.
        Assert.assertEquals(CONVERSATION, pending("m-1", 1, 10L, "timeout").conversationUuid)
        Assert.assertEquals(JID, pending("m-1", 1, 10L, "timeout").conversationJid)
    }

    // -- what still stands ------------------------------------------------------------------------

    @Test
    fun aMessageThatNeverFailedIsNotAFailure() {
        Assert.assertFalse(pending("m-1", 0, 10L, null).outstanding)
        Assert.assertTrue(
                TranslationFailures.recent(
                                Collections.singletonList(pending("m-1", 0, 10L, null)))
                        .isEmpty())
    }

    @Test
    fun aFailureThatWasTranslatedLaterIsHistory() {
        // The queue item keeps its attempt count, so the state is the only thing that says whether
        // the bubble is still covered - and a row claiming otherwise would be a lie about the app.
        val done =
                received(
                        "m-1",
                        TranslationQueue.Item.STATE_DONE,
                        3,
                        10L,
                        20L,
                        "deepseek is unreachable")
        Assert.assertFalse(done.outstanding)
        Assert.assertTrue(TranslationFailures.recent(listOf(done)).isEmpty())
    }

    @Test
    fun aPendingRetryAndAGivenUpFailureBothStand() {
        val waiting = pending("m-1", 2, 10L, "timeout")
        val givenUp = failed("m-2", 1, 10L, "timeout")
        Assert.assertTrue(waiting.outstanding)
        Assert.assertEquals(TranslationFailures.Next.RETRY, waiting.next)
        Assert.assertTrue(givenUp.outstanding)
        Assert.assertEquals(
                "nothing more will be tried for this one",
                TranslationFailures.Next.GAVE_UP,
                givenUp.next)
    }

    @Test
    fun aSendThatFailedIsAFailureToo() {
        val send =
                TranslationFailures.send("m-9", CONVERSATION, JID, BODY, 500L, null)
        Assert.assertTrue(send.outstanding)
        Assert.assertEquals(
                "a send is held, and the retry is the owner's - never an attempt the app promises",
                TranslationFailures.Next.HELD,
                send.next)
        Assert.assertEquals(TranslationFailures.When.WRITTEN, send.`when`)
        Assert.assertEquals(500L, send.at)
    }

    // -- the time a row shows ---------------------------------------------------------------------

    @Test
    fun aReceivedRowIsTimedByTheFailureWhenTheQueueRecordedOne() {
        // Schema 73: the queue writes the moment the attempt failed, and that is what the row shows.
        val row =
                received(
                        "m-1",
                        TranslationQueue.Item.STATE_PENDING,
                        2,
                        100L,
                        777L,
                        "timeout")
        Assert.assertEquals(TranslationFailures.When.FAILED, row.`when`)
        Assert.assertEquals(777L, row.at)
    }

    @Test
    fun aReceivedRowWithNoFailureTimeSaysWhenItArrived() {
        // A row written before schema 73 has no failure time, so it must not claim one: it says which
        // time it is showing instead, and the message's own arrival is what it has.
        val row = arrived("m-1", 2, 1234L, "timeout")
        Assert.assertEquals(TranslationFailures.When.ARRIVED, row.`when`)
        Assert.assertEquals(1234L, row.at)
    }

    @Test
    fun aFreshInstallGetsTheFailureTimeColumnWithoutAMigration() {
        // The migrated path cannot be tested here - it is SQLite and a real database file - but the
        // fresh-install path can, and both halves have to exist or one kind of install has no column.
        Assert.assertTrue(
                "the queue table is created with the failure time: "
                        + TranslationTables.CREATE_QUEUE_TABLE,
                TranslationTables.CREATE_QUEUE_TABLE.contains(TranslationTables.QUEUE_FAILED_AT))
    }

    @Test
    fun aSendIsTimedByItsFailureWhenTheRecordReachesIt() {
        val row =
                TranslationFailures.send(
                        "m-9",
                        CONVERSATION,
                        JID,
                        BODY,
                        500L,
                        recorded("m-9", HeldSend.HoldReason.UNREACHABLE, 900L))
        Assert.assertEquals(TranslationFailures.When.SEND_FAILED, row.`when`)
        Assert.assertEquals(900L, row.at)
    }

    @Test
    fun aSendTheRecordDoesNotReachSaysWhenItWasWritten() {
        // The app keeps one failure, not a list of them: an older send says which time it is showing
        // and that its reason was not kept, rather than borrowing the newest failure's words.
        for (other in listOf<TranslationActivityPort.RecordedFailure?>(
                null, recorded("some-other-message", HeldSend.HoldReason.NO_KEY, 900L))) {
            val row =
                    TranslationFailures.send("m-9", CONVERSATION, JID, BODY, 500L, other)
            Assert.assertEquals(TranslationFailures.When.WRITTEN, row.`when`)
            Assert.assertEquals(500L, row.at)
            Assert.assertNull(row.reason)
            Assert.assertNull(row.detail)
        }
    }

    @Test
    fun aRecordedFailureWithNoReasonIsNotThisMessagesReason() {
        // A stored failure that names no reason is not a reason to hand out.
        val nameless =
                TranslationDoubles.RecordedFailure(null, "words", "m-9", 900L)
        val row =
                TranslationFailures.send("m-9", CONVERSATION, JID, BODY, 500L, nameless)
        Assert.assertNull(row.reason)
        Assert.assertEquals(TranslationFailures.When.WRITTEN, row.`when`)
    }

    // -- order and size ---------------------------------------------------------------------------

    @Test
    fun theNewestFailureIsFirst() {
        val rows =
                TranslationFailures.recent(
                        listOf(
                                pending("old", 1, 100L, "timeout"),
                                pending("newest", 1, 300L, "timeout"),
                                pending("middle", 1, 200L, "timeout")))
        Assert.assertEquals(3, rows.size)
        Assert.assertEquals("newest", rows[0].messageUuid)
        Assert.assertEquals("middle", rows[1].messageUuid)
        Assert.assertEquals("old", rows[2].messageUuid)
    }

    @Test
    fun receivedAndSentFailuresShareTheOneOrder() {
        val rows =
                TranslationFailures.recent(
                        listOf(
                                pending("received", 1, 100L, "timeout"),
                                TranslationFailures.send("sent", CONVERSATION, JID, BODY, 300L, null),
                                pending("middle", 1, 200L, "timeout")))
        Assert.assertEquals(listOf("sent", "middle", "received"), uuids(rows))
    }

    @Test
    fun twoFailuresInTheSameMillisecondKeepOneOrder() {
        val a = pending("aaa", 1, 500L, "timeout")
        val b = pending("bbb", 1, 500L, "timeout")
        val first = uuids(TranslationFailures.recent(listOf(a, b)))
        val second = uuids(TranslationFailures.recent(listOf(b, a)))
        Assert.assertEquals("the same two rows must not swap between two glances", first, second)
    }

    @Test
    fun onlyTheMostRecentAreKept() {
        val candidates = ArrayList<TranslationFailures.Failure>()
        for (i in 0 until TranslationFailures.RECENT_LIMIT + 5) {
            candidates.add(pending("m-" + i, 1, 1000L + i, "timeout"))
        }
        val rows = TranslationFailures.recent(candidates)
        Assert.assertEquals(TranslationFailures.RECENT_LIMIT, rows.size)
        Assert.assertEquals(
                "the newest survive the cut",
                "m-" + (TranslationFailures.RECENT_LIMIT + 4),
                rows[0].messageUuid)
    }

    @Test
    fun nothingIsNotACrash() {
        Assert.assertTrue(TranslationFailures.recent(null).isEmpty())
        Assert.assertTrue(
                TranslationFailures.recent(ArrayList<TranslationFailures.Failure>()).isEmpty())
        Assert.assertTrue(
                TranslationFailures.recent(
                                listOf<TranslationFailures.Failure?>(null))
                        .isEmpty())
    }

    private fun uuids(rows: List<TranslationFailures.Failure>): List<String?> {
        val result = ArrayList<String?>()
        for (row in rows) {
            result.add(row.messageUuid)
        }
        return result
    }

    // -- why --------------------------------------------------------------------------------------

    @Test
    fun aRefusedFailureIsNamedByTheErrorItself() {
        // Failed on the first attempt and not retried: the client said retrying cannot help, so the
        // words it used are what names the reason - the same classifier the send path uses.
        Assert.assertEquals(
                HeldSend.HoldReason.REJECTED_KEY,
                failed("m-1", 1, 10L, "deepseek returned 401: unauthorized").reason)
        Assert.assertEquals(
                HeldSend.HoldReason.NO_CREDIT,
                failed("m-2", 1, 10L, "deepseek returned 402: insufficient balance").reason)
        Assert.assertEquals(
                HeldSend.HoldReason.FAILED,
                failed("m-3", 1, 10L, "the answer is not the JSON we asked for").reason)
    }

    @Test
    fun aFailureWorthWaitingForSaysSoAndIsRetriedAgain() {
        // The retry schedule ran out: the queue's own table of delays is what says so.
        Assert.assertEquals(
                HeldSend.HoldReason.UNREACHABLE,
                failed(
                                "m-1",
                                TranslationBackoff.MAX_ATTEMPTS,
                                10L,
                                "deepseek is unreachable: timeout")
                        .reason)
        // Still waiting for its next attempt.
        Assert.assertEquals(
                HeldSend.HoldReason.UNREACHABLE, pending("m-2", 2, 10L, "timeout").reason)
    }

    @Test
    fun theReasonTheBubbleShowsWins() {
        // The bubble quotes the reason kept for this very message; the row must not contradict it.
        val row =
                TranslationFailures.received(
                        "m-1",
                        CONVERSATION,
                        JID,
                        BODY,
                        TranslationQueue.Item.STATE_PENDING,
                        2,
                        10L,
                        900L,
                        "timeout",
                        FailureCause.CHECK_REFUSED.stored(),
                        recorded("m-1", HeldSend.HoldReason.NO_KEY, 99L))
        Assert.assertEquals(HeldSend.HoldReason.NO_KEY, row.reason)
        Assert.assertEquals("deepseek said: NO_KEY", row.detail)
        // Item 17's cause is a separate fact from the reason: the reason is the app's own word for
        // why an attempt failed, the cause is what the retry rule and the tap turn on.
        Assert.assertEquals(FailureCause.CHECK_REFUSED, row.cause)
    }

    @Test
    fun theRecordsOwnWordsComeWithItsReason() {
        val row =
                TranslationFailures.send(
                        "m-9",
                        CONVERSATION,
                        JID,
                        BODY,
                        500L,
                        recorded("m-9", HeldSend.HoldReason.NO_CREDIT, 900L))
        Assert.assertEquals(HeldSend.HoldReason.NO_CREDIT, row.reason)
        Assert.assertEquals("deepseek said: NO_CREDIT", row.detail)
    }
}
