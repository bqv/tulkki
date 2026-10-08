package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.R

/**
 * `ui-8`'s cells over the trailing strip's two live-ish marks: the notification mark, which
 * `ConversationAdapter` read from the raw mute and the call manager, and the delivery tick, which
 * `MessageAdapter.getMessageStatusAsDrawable` read from the row's status.
 *
 * <p>What the screen draws each state in is the screen's and no JVM test reaches a Composable; what these
 * hold is which state a row carries, which is the part that was arithmetic in the deleted adapter.
 */
class UiRowMarkTest {

    @Test
    fun theNotificationMarkIsTheTreesOwnThreeWayReadOfTheMute() {
        Assert.assertEquals(
            "a running call is the tree's phone mark, before the mute is even read",
            UiNotification.CALL,
            UiNotification.of(ongoingCall = true, mutedTill = 0L, now = 1_000L, alwaysNotify = true),
        )
        Assert.assertEquals(
            "the ceiling of the field means muted with no end",
            UiNotification.MUTED,
            UiNotification.of(false, Long.MAX_VALUE, 1_000L, true),
        )
        Assert.assertEquals(
            "an instant that has not passed means muted until then",
            UiNotification.MUTED_UNTIL,
            UiNotification.of(false, 2_000L, 1_000L, true),
        )
        Assert.assertEquals(
            "a mute that has passed is not a mute",
            UiNotification.NONE,
            UiNotification.of(false, 500L, 1_000L, alwaysNotify = true),
        )
        Assert.assertEquals(
            "and a conversation that does not always notify gets the hollow bell",
            UiNotification.SILENT,
            UiNotification.of(false, 0L, 1_000L, alwaysNotify = false),
        )
    }

    @Test
    fun theDeliveryTickIsTheStatusesOwnMark() {
        Assert.assertNull("a received row has nothing to report", tick(status = Message.STATUS_RECEIVED))
        Assert.assertEquals(
            R.drawable.ic_done_24dp,
            tick(status = Message.STATUS_SEND),
        )
        Assert.assertEquals(
            "sent and received and displayed are both the double tick",
            R.drawable.ic_done_all_24dp,
            tick(status = Message.STATUS_SEND_RECEIVED),
        )
        Assert.assertEquals(
            R.drawable.ic_done_all_24dp,
            tick(status = Message.STATUS_SEND_DISPLAYED),
        )
        Assert.assertEquals(R.drawable.ic_more_horiz_24dp, tick(status = Message.STATUS_WAITING))
        Assert.assertEquals(R.drawable.ic_p2p_24dp, tick(status = Message.STATUS_OFFERED))
        Assert.assertEquals(
            "a cancelled send is the cancel mark, not the error one",
            R.drawable.ic_cancel_24dp,
            tick(status = Message.STATUS_SEND_FAILED, error = Message.ERROR_MESSAGE_CANCELLED),
        )
        Assert.assertEquals(
            R.drawable.ic_error_24dp,
            tick(status = Message.STATUS_SEND_FAILED),
        )
        Assert.assertNull(
            "a send waiting on a transfer with no transferable draws nothing",
            ConversationRowMark.tick(
                message(status = Message.STATUS_UNSEND),
                UiTransferState.None,
            ),
        )
        Assert.assertEquals(
            "and the upload arrow once there is one",
            R.drawable.ic_upload_24dp,
            ConversationRowMark.tick(
                message(status = Message.STATUS_UNSEND),
                UiTransferState.Downloading(progress = 1, sizeBytes = null),
            ),
        )
        Assert.assertNull(
            "a call row never carries a tick",
            ConversationRowMark.tick(
                message(status = Message.STATUS_SEND, type = Message.TYPE_RTP_SESSION),
                UiTransferState.None,
            ),
        )
    }

    private fun tick(status: Int, error: String? = null): Int? =
        ConversationRowMark.tick(message(status = status, error = error), UiTransferState.None)

    private fun message(
        status: Int,
        type: Int = Message.TYPE_TEXT,
        error: String? = null,
    ): MessageSnapshot =
        MessageSnapshot(
            id = "m1",
            conversationId = "c1",
            timeSent = 0L,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = type.toLong(),
            status = status.toLong(),
            encryption = Message.ENCRYPTION_NONE.toLong(),
            delivery = 0L,
            read = 0L,
            deleted = 0L,
            fileDeleted = 0L,
            markable = 0L,
            oob = 0L,
            carbon = 0L,
            retractId = null,
            edited = null,
            serverMsgId = null,
            remoteMsgId = null,
            axolotlFingerprint = null,
            occupantId = null,
            relativeFilePath = null,
            fileParams = null,
            oobUri = null,
            errorMsg = error,
            bodyLanguage = null,
            reactions = null,
            readByMarkers = null,
            translationState = Message.TRANSLATION_NONE.toLong(),
            translationLang = null,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = null, original = "Hei"),
        )
}
