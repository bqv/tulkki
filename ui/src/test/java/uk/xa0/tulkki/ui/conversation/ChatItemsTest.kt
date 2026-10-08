package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.MessageType
import uk.xa0.tulkki.ui.projection.UiBody
import uk.xa0.tulkki.ui.projection.UiDeliveryState
import uk.xa0.tulkki.ui.projection.UiEncryption
import uk.xa0.tulkki.ui.projection.UiEnglishRow
import uk.xa0.tulkki.ui.projection.UiMessage
import uk.xa0.tulkki.ui.projection.UiOriginalRow
import uk.xa0.tulkki.ui.projection.UiRunFlags
import uk.xa0.tulkki.ui.projection.UiTransferState

/**
 * §4.2's headers and its unread number, over rows the screen is handed.
 *
 * <p>Two facts are pinned here rather than in the screen: the day headers are items, so `Anchor`'s
 * indices are this list's and not the rows' - a header inserted above a row moves every index under
 * it - and the pill's number is the *newest* rows' count, recovered from the anchor exactly as
 * `UnreadDivider.anchor` wrote it.
 */
class ChatItemsTest {

    private fun row(id: String, firstOfDay: Boolean = false): UiMessage =
        UiMessage(
            id = MessageId(id),
            conversationId = ConversationId("c"),
            direction = Direction.INCOMING,
            time = 0L,
            type = MessageType.TEXT,
            top = UiBody.Visible("x"),
            bottom = null,
            divider = false,
            quote = null,
            english = UiEnglishRow.Absent,
            original = UiOriginalRow.Absent,
            review = null,
            transfer = UiTransferState.None,
            encryption = UiEncryption.None,
            delivery = UiDeliveryState.Sent,
            reactions = emptyList(),
            gloss = emptyList(),
            run = UiRunFlags(firstOfRun = true, lastOfRun = true, firstOfDay = firstOfDay, showAvatar = true, showName = false),
            selected = false,
            canTranslateNow = true,
        )

    @Test
    fun aHeaderOpensTheListAndEveryNewDay() {
        val items = ChatItems.of(listOf(row("a", true), row("b"), row("c", true)), null) { "day-$it" }
        Assert.assertEquals(5, items.size)
        Assert.assertTrue(items[0] is ChatItem.Day)
        Assert.assertEquals(0, (items[0] as ChatItem.Day).firstRow)
        Assert.assertEquals("day-0", (items[0] as ChatItem.Day).label)
        Assert.assertEquals("a", (items[1] as ChatItem.Row).message.id.uuid)
        Assert.assertEquals("b", (items[2] as ChatItem.Row).message.id.uuid)
        Assert.assertEquals(2, (items[3] as ChatItem.Day).firstRow)
        Assert.assertEquals("day-2", (items[3] as ChatItem.Day).label)
        Assert.assertEquals("c", (items[4] as ChatItem.Row).message.id.uuid)
    }

    @Test
    fun theFirstRowAlwaysGetsAHeader() {
        // `MessageRuns` sets `firstOfDay` on the list's first row whatever it is, and this rule keeps
        // that promise itself rather than inheriting it, so a conversation opens under a header.
        val items = ChatItems.of(listOf(row("a", false)), null) { "day-$it" }
        Assert.assertTrue(items[0] is ChatItem.Day)
        Assert.assertEquals(0, (items[0] as ChatItem.Day).firstRow)
    }

    @Test
    fun theUnreadNumberIsOnlyOnTheAnchorRow() {
        val items = ChatItems.of(listOf(row("a", true), row("b"), row("c")), unreadAnchor = 1) { "day-$it" }
        val rows = items.filterIsInstance<ChatItem.Row>()
        Assert.assertNull("the read row has no pill", rows[0].unreadCount)
        Assert.assertEquals("the newest two are the unread ones", 2, rows[1].unreadCount)
        Assert.assertNull(rows[2].unreadCount)
    }

    @Test
    fun nothingUnreadDrawsNoNumber() {
        val rows = ChatItems.of(listOf(row("a", true)), null) { "day-$it" }.filterIsInstance<ChatItem.Row>()
        Assert.assertNull(rows[0].unreadCount)
    }

    @Test
    fun everyItemKeyIsUnique() {
        val items = ChatItems.of(listOf(row("a", true), row("b"), row("c", true)), 2) { "day-$it" }
        Assert.assertEquals(items.size, items.map { it.key }.toSet().size)
        Assert.assertEquals("the row's own id is its key", "c", items.last().key)
    }

    @Test
    fun noRowsNoItems() {
        Assert.assertTrue(ChatItems.of(emptyList(), 0) { "day-$it" }.isEmpty())
    }
}
