package uk.xa0.tulkki.ui.projection

import androidx.annotation.DrawableRes
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.R

/**
 * The notification mark a row's trailing strip draws, from `ConversationAdapter`'s own three-way read of
 * the mute and its call case.
 *
 * <p>[NONE] is the state the tree drew nothing for - a conversation that always notifies - and it is not a
 * bell: the tree's `getAdditionalStatusInfo` decision was that a row which cannot be silenced needs no
 * mark. [SILENT] is the tree's `ic_notifications_none_24dp`: not muted, but not a conversation that always
 * speaks either.
 */
enum class UiNotification {
    /** A call with this row is running: the tree's `ic_phone_in_talk_24dp`. */
    CALL,

    /** Muted with no end (`muted_till` at the field's ceiling): the crossed bell. */
    MUTED,

    /** Muted until an instant that has not passed: the paused bell. */
    MUTED_UNTIL,

    /** Not muted, and not a conversation that always notifies: the hollow bell. */
    SILENT,

    /** Nothing to draw: the conversation always notifies. */
    NONE;

    companion object {

        /**
         * The tree's rule, as one function of the facts rather than of the entity:
         * `ConversationAdapter.onBindViewHolder` asked the ongoing call first, then the raw `muted_till`
         * attribute twice - the ceiling means "forever", anything not yet past means "for now" - and only
         * then the conversation's own `alwaysNotify`.
         */
        @JvmStatic
        fun of(ongoingCall: Boolean, mutedTill: Long, now: Long, alwaysNotify: Boolean): UiNotification =
            when {
                ongoingCall -> CALL
                mutedTill == Long.MAX_VALUE -> MUTED
                mutedTill >= now -> MUTED_UNTIL
                alwaysNotify -> NONE
                else -> SILENT
            }
    }
}

/**
 * The delivery tick - `ConversationAdapter`'s `message_status`, which
 * `MessageAdapter.getMessageStatusAsDrawable` mapped from the row's status.
 *
 * <p>It is a row fact, not a live one: the status, the type and the failure text are all columns of the
 * message the pointer names. The one thing the tree asked that a snapshot cannot answer is the
 * `UNSEND` case, where it drew an upload arrow only while a transferable existed - and that question is
 * [PerProcess.transfer]'s, which is why it is an argument here rather than a second read.
 */
object ConversationRowMark {

    /**
     * The tick's own mark, or `null` when the tree drew none. A message that was received, a call row and
     * a status row all answer `null`: the tree hid the mark for each of them.
     */
    @JvmStatic
    @DrawableRes
    fun tick(message: MessageSnapshot?, transfer: UiTransferState): Int? {
        if (message == null || message.type == Message.TYPE_RTP_SESSION.toLong()) {
            return null
        }
        val status = message.status?.toInt() ?: return null
        return when (status) {
            Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp
            Message.STATUS_UNSEND ->
                if (transfer is UiTransferState.None) null else R.drawable.ic_upload_24dp
            Message.STATUS_SEND -> R.drawable.ic_done_24dp
            Message.STATUS_SEND_RECEIVED,
            Message.STATUS_SEND_DISPLAYED -> R.drawable.ic_done_all_24dp
            Message.STATUS_SEND_FAILED ->
                if (Message.ERROR_MESSAGE_CANCELLED == message.errorMsg) {
                    R.drawable.ic_cancel_24dp
                } else {
                    R.drawable.ic_error_24dp
                }
            Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp
            else -> null
        }
    }
}
