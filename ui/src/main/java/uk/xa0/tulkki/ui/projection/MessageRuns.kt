package uk.xa0.tulkki.ui.projection

import java.time.Instant
import java.time.ZoneId
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message

/**
 * The runs of one conversation's rows, `Design: the Compose UI` §4.1: "**group by direction and occupant,
 * tail on the last of the run**" - "the decision Tulkki must make *before* it can draw a two-half bubble,
 * and 'tail on the last of the run' is the version that survives a MUC".
 *
 * <p>**The tree's own rule, and it was named nowhere until it was read.** `MessageAdapter.merge(a, b)`
 * (`:3490`) is what decided whether two rows were one run, and this is it: the same kind of row, the same
 * direction, and - in a room - the same occupant and counterpart; and then `b.timeSent - a.timeSent <=
 * Config.MESSAGE_MERGE_WINDOW`, **ninety seconds**. That last number is an island constant
 * (`uk.xa0.tulkki.xmpp.Config`), so it arrives as an argument from a file that may name it, exactly as the
 * clock's zone does.
 *
 * <p>§4.1's own sentence - "Nine view types fold to incoming/outgoing/action" - is why [merge] compares a
 * three-way direction rather than `getItemViewType`'s twenty branches: the design folds them, so the run key
 * is direction, occupant and time.
 *
 * <p>**The avatar is the tree's asymmetry, kept.** `MessageAdapter.setRequiresAvatar` was called with
 * `viewHolder instanceof StartBubbleMessageItemViewHolder ? !mergeIntoTop : !mergeIntoBottom` - a
 * start-aligned (incoming) bubble shows the avatar when it does **not** merge with the row above, an
 * end-aligned one when it does not merge with the row below. So the avatar sits at the run's tail on the
 * owner's side and at its head on the other's.
 *
 * <p>**One condition is not here, and it is named rather than guessed.** The tree's display-name test is
 * `mForceNames || multiReceived || showUserNickname || (trueCounterpart != null && message.getContact() !=
 * null)`. The first three are arguments ([forceNames], [group]); the fourth needs the **contact entity**, and
 * a snapshot carries the counterpart but not the contact, so a carbon with no room behind it draws no name
 * here where the tree could. It is the kind of hole this module records instead of inventing an answer for.
 */
object MessageRuns {

    /**
     * One flag per row, in the list's own order.
     *
     * @param mergeWindow the tree's `Config.MESSAGE_MERGE_WINDOW`, in milliseconds: two rows further apart
     *     than this are two runs however alike they look.
     * @param group whether this conversation is a room (`Conversation.MODE_MULTI`): the occupant test and
     *     the display name are both room facts, and the whole list shares them.
     * @param forceNames the owner's "always show names" setting, which the tree called `mForceNames`.
     */
    @JvmStatic
    fun runFlags(
        messages: List<MessageSnapshot>,
        mergeWindow: Long,
        group: Boolean,
        forceNames: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<UiRunFlags> =
        messages.mapIndexed { at, message ->
            val previous = messages.getOrNull(at - 1)
            val next = messages.getOrNull(at + 1)
            val firstOfRun = previous == null || !merge(previous, message, mergeWindow, group)
            val lastOfRun = next == null || !merge(message, next, mergeWindow, group)
            val incoming = MessageProjection.incoming(message)
            // The tree's own alignment: a start-aligned bubble at the head of its run, an end-aligned one
            // at the tail - which is where the avatar's edge is.
            val showAvatar = if (incoming) firstOfRun else lastOfRun
            UiRunFlags(
                firstOfRun = firstOfRun,
                lastOfRun = lastOfRun,
                firstOfDay = previous == null || day(previous, zone) != day(message, zone),
                showAvatar = showAvatar,
                showName = showAvatar && incoming && (group || forceNames),
            )
        }

    /**
     * `MessageAdapter.merge(a, b)`: whether `b` continues `a`'s run. A system row is its own kind - the
     * design's third direction - and never merges with a message, nor a message with it.
     */
    private fun merge(
        a: MessageSnapshot,
        b: MessageSnapshot,
        mergeWindow: Long,
        group: Boolean,
    ): Boolean {
        if (system(a) != system(b)) {
            return false
        }
        if (!system(a)) {
            if (MessageProjection.direction(a) != MessageProjection.direction(b)) {
                return false
            }
            if (group && MessageProjection.direction(a) == Direction.INCOMING) {
                val occupantA = a.occupantId
                val occupantB = b.occupantId
                if (occupantA != null && occupantB != null && occupantA != occupantB) {
                    return false
                }
                if (a.counterpart == null || a.counterpart != b.counterpart) {
                    return false
                }
            }
        }
        return (b.timeSent ?: 0L) - (a.timeSent ?: 0L) <= mergeWindow
    }

    /*
     * Which way a row travelled is **not** asked here. It is [MessageProjection.direction]'s answer - the
     * store's status with the store's own default for a row it has not classified - because the run key
     * and the side the bubble is drawn on are one fact. This file comparing the raw nullable column was
     * how they came apart: an unclassified row was grouped as outgoing and drawn incoming, so it carried
     * a sent run's avatar edge and gaps.
     */

    /** A system row: the tree's `Message.TYPE_STATUS`, which is the design's third kind of run. */
    private fun system(message: MessageSnapshot): Boolean =
        message.type == Message.TYPE_STATUS.toLong()

    /** The local day an instant falls on, in the caller's zone: a run never crosses one. */
    private fun day(message: MessageSnapshot, zone: ZoneId) =
        Instant.ofEpochMilli(message.timeSent ?: 0L).atZone(zone).toLocalDate()
}
