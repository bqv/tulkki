package uk.xa0.tulkki.ui.conversation

/**
 * Where an opened conversation puts the reader, `Design: the Compose UI` §4.2's own behaviour:
 *
 * ```java
 * if (unreadCount == 0 || lastVisible + 2 >= itemCount - unreadCount) scrollDown()
 * else scrollToFirstUnread(unreadCount)
 * ```
 *
 * <p>It is one pure decision of three numbers, so it has a JVM cell and the screen has no branch: the
 * `+ 2` is Xabber's own slack - "close enough to the bottom that jumping to the first unread would move the
 * reader *up*" - and the anchor is the index the divider is drawn at, which is the same number the badge
 * counts from.
 */
enum class UnreadJump {
    /** Stay at (or go to) the last row: there is nothing unread, or the reader is already near the end. */
    BOTTOM,

    /** Scroll so the first unread row is at the top: the reader is far enough back to be shown the gap. */
    FIRST_UNREAD,
}

/** The two numbers §4.2's decision and its divider are made of. */
object UnreadDivider {

    /**
     * §4.2's branch, verbatim: the `+ 2` is a row of slack, so a reader one or two rows from the bottom is
     * not thrown to a divider directly above them.
     */
    @JvmStatic
    fun jump(unreadCount: Int, lastVisible: Int, itemCount: Int): UnreadJump {
        if (unreadCount <= 0 || lastVisible + 2 >= itemCount - unreadCount) {
            return UnreadJump.BOTTOM
        }
        return UnreadJump.FIRST_UNREAD
    }

    /**
     * The index the divider is drawn above - the first unread row - or `null` when there is nothing unread.
     *
     * <p>A count larger than the list puts the divider at the top rather than off it: the count and the rows
     * come from two reads and one may land before the other, and a divider at index `-5` is not a place.
     */
    @JvmStatic
    fun anchor(unreadCount: Int, itemCount: Int): Int? {
        if (unreadCount <= 0 || itemCount <= 0) {
            return null
        }
        return (itemCount - unreadCount).coerceAtLeast(0)
    }
}
