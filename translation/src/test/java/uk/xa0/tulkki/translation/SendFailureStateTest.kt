package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/**
 * The send-failure state, and the one thing that makes it a state rather than a moment: it survives
 * the process.
 *
 * <p>The owner's ruling on the held-send dead end is that a row which cannot send stops being an
 * ambiguous hold and is recorded and drawn as a send failure. Two columns of the message row already
 * carry it - upstream's {@code STATUS_SEND_FAILED} and this app's {@code TRANSLATION_FAILED} - and
 * {@link HeldSend#isSendFailure} is the one place that answers the question, so the resting bar and a
 * future reader cannot each invent their own test for it.
 *
 * <p>The reason is the half the app used to keep only for the newest failure ({@code
 * TranslationActivity} says so itself), so it is kept per message here: the row the bar finds after a
 * restart is not necessarily the failure that happened last. Rebuilding the settings over the same
 * store is how "survives a restart" is pinned without a device.
 */
class SendFailureStateTest {

    @Test
    fun theStateIsUpstreamsNotSentStatusBesideThisAppsFailedTranslation() {
        Assert.assertTrue(
                "nothing was sent and the translation failed: that is the send failure",
                HeldSend.isSendFailure(Message.STATUS_SEND_FAILED, Message.TRANSLATION_FAILED))
        Assert.assertFalse(
                "a held row is still waiting, not failed",
                HeldSend.isSendFailure(Message.STATUS_WAITING, Message.TRANSLATION_FAILED))
        Assert.assertFalse(
                "a sent-as-written row is not a failure",
                HeldSend.isSendFailure(Message.STATUS_SEND_FAILED, Message.TRANSLATION_SAME_LANGUAGE))
        Assert.assertFalse(
                "and neither is a row that was never attempted",
                HeldSend.isSendFailure(Message.STATUS_WAITING, Message.TRANSLATION_NONE))
    }

    @Test
    fun theReasonOutlivesTheProcessAndIsTheRowsOwn() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        settings.setSendFailure("m1", HeldSend.HoldReason.UNREACHABLE)
        settings.setSendFailure("m2", HeldSend.HoldReason.FAILED)

        // A second settings object over the same store is the next process's read.
        val reopened = TranslationSettings(store, store)
        Assert.assertEquals(
                "this row's reason, not the newest failure's",
                HeldSend.HoldReason.UNREACHABLE,
                reopened.sendFailure("m1"))
        Assert.assertEquals(HeldSend.HoldReason.FAILED, reopened.sendFailure("m2"))
        Assert.assertNull("no record, no reason", reopened.sendFailure("m3"))
        Assert.assertNull("and no uuid, no read", reopened.sendFailure(null))
    }

    @Test
    fun aRowThatStopsBeingAFailureDropsItsReason() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        settings.setSendFailure("m1", HeldSend.HoldReason.NO_CREDIT)
        Assert.assertEquals(
                HeldSend.HoldReason.NO_CREDIT, settings.sendFailure("m1"))

        settings.setSendFailure("m1", null)
        Assert.assertNull(
                "a blank value is not a record: the row is no longer a send failure",
                TranslationSettings(store, store).sendFailure("m1"))
        // A stored but unusable value reads as no reason rather than throwing on a later read.
        store.putString("send-failure:m1", "not-a-reason")
        Assert.assertNull(TranslationSettings(store, store).sendFailure("m1"))
    }

    @Test
    fun theStoredFormIsTheEnumsOwnName() {
        Assert.assertEquals("FAILED", HeldSend.reasonName(HeldSend.HoldReason.FAILED))
        Assert.assertEquals(
                HeldSend.HoldReason.DOUBT, HeldSend.parseReason(HeldSend.reasonName(HeldSend.HoldReason.DOUBT)))
        Assert.assertEquals("", HeldSend.reasonName(null))
        Assert.assertNull(HeldSend.parseReason(null))
        Assert.assertNull(HeldSend.parseReason(""))
    }
}
