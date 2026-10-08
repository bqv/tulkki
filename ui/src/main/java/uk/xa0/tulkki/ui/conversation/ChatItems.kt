package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.ui.projection.UiMessage

/**
 * One item of the conversation's list, `Design: the Compose UI` §4.2's own translation: "In Compose
 * there is no direct equivalent: use the current day as a `stickyHeader` (the platform-supported
 * case) plus a drawn divider + label between days".
 *
 * <p>So a day is an *item* here after all, and that is not a reversal of §4.2's "draw, not insert"
 * - the design's worry was that inserted **separators** shift the list on every bind, and its own
 * Compose answer is a header the platform pins. The rows themselves stay the plain list they were;
 * nothing about a row changes because a header sits above it.
 */
sealed interface ChatItem {

    /** The `LazyColumn` key: the row's own local id, or the day's own index. */
    val key: String

    /** A day's header, drawn above the first row of that day. */
    data class Day(
        override val key: String,
        /** The index into `ChatState.rows` of the first row of this day. */
        val firstRow: Int,
        val label: String,
    ) : ChatItem

    /** One message, with the unread count the pill above it carries - or `null` for no pill. */
    data class Row(val message: UiMessage, val unreadCount: Int?) : ChatItem {

        override val key: String get() = message.id.uuid
    }
}

/**
 * §4.2's headers and §4.2's unread count, over the rows as the read delivered them.
 *
 * <p>**A pure rule, because the list's item indices are what `Anchor` anchors on.** §4.3 captures the
 * first visible item's id and index, and those are *this* list's indices - headers included - so the
 * item list has to be a value the screen can rebuild and compare, not something the `LazyColumn`
 * invents as it measures. It also gives the unread count a cell: the pill's number is the read's own
 * count, recovered from the anchor exactly as `UnreadDivider.anchor` wrote it.
 */
object ChatItems {

    /**
     * The items for one conversation's rows, oldest first.
     *
     * @param rows the assembled rows, in the read's own order
     * @param unreadAnchor the index of the first unread row, or `null`
     * @param label the day's words for the row at that index, which is `DayLabel.of`'s answer in the
     *     screen and a fake in a cell - the same seam `PreviewWords` is, and for the same reason
     */
    @JvmStatic
    fun of(rows: List<UiMessage>, unreadAnchor: Int?, label: (Int) -> String): List<ChatItem> {
        val items = ArrayList<ChatItem>(rows.size + 1)
        for ((index, message) in rows.withIndex()) {
            // `MessageRuns` sets `firstOfDay` on the first row of every day, and on the list's first row
            // whatever it is - and the `index == 0` half is kept here anyway, so this rule does not depend
            // on another rule's edge case for the header a conversation must open under.
            if (index == 0 || message.run.firstOfDay) {
                items += ChatItem.Day(key = "day-$index", firstRow = index, label = label(index))
            }
            items +=
                ChatItem.Row(
                    message = message,
                    unreadCount = if (unreadAnchor == index) rows.size - index else null,
                )
        }
        return items
    }
}
