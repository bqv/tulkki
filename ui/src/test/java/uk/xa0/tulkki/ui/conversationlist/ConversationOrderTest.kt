package uk.xa0.tulkki.ui.conversationlist

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.ConversationKind
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.UiConversation
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTranslationMode

/**
 * `ui-8`'s cells over the list's order and its suggestion, re-derived from `Conversation.compareTo` now
 * that the service's entity list is not what the list is built from.
 *
 * <p>The fourth cell is the one this port is most likely to get wrong: the tree sorts by the **last
 * message's `timeReceived`**, while the snapshot the list is built from carries the **pointer's** instant
 * in `lastMessageAt`. A comparator that reads the pointer looks right on every fixture whose two numbers
 * agree - which is every fixture written from the same row - and reorders a real list only when a message
 * was sent before it was received.
 */
class ConversationOrderTest {

    @Test
    fun theTwoPinnedKeysComeFirstInTheTreesOrder() {
        val noteToSelf = row("a", pinned = true, withSelf = true)
        val pinned = row("b", pinned = true)
        val newest = row("c")
        val older = row("d")
        val times = mapOf("a" to 1L, "b" to 2L, "c" to 900L, "d" to 800L)
        Assert.assertEquals(
            "the pinned note-to-self, then the pinned, then the newest - the two pinned keys outrank time",
            listOf("a", "b", "c", "d"),
            ConversationOrder.sort(listOf(newest, pinned, older, noteToSelf), times).map { it.id.uuid },
        )
        Assert.assertEquals(
            "and a row the caller did not time sorts as zero within its own key group",
            listOf("c", "d"),
            ConversationOrder.sort(listOf(newest, older), mapOf("c" to 900L)).map { it.id.uuid },
        )
    }

    @Test
    fun aConversationWithNoMessagesIsTimedByItsCreationOrItsClear() {
        val conversation = conversation("c1", created = 500L)
        Assert.assertEquals(
            "no messages: the later of creation and the last clear",
            900L,
            ConversationOrder.sortableTime(conversation, null, lastClearHistoryAt = 900L, draftAt = null),
        )
        Assert.assertEquals(
            "and creation when the clear is older",
            500L,
            ConversationOrder.sortableTime(conversation, null, lastClearHistoryAt = 100L, draftAt = null),
        )
    }

    @Test
    fun aDraftBeatsTheLastMessage() {
        val conversation = conversation("c1", created = 500L)
        val message = message("m1", timeSent = 1_000L, timeReceived = 1_000L)
        Assert.assertEquals(
            "the draft's own instant when it is later",
            2_000L,
            ConversationOrder.sortableTime(conversation, message, lastClearHistoryAt = 0L, draftAt = 2_000L),
        )
        Assert.assertEquals(
            "and not when it is older: the draft does not sink a newer message",
            1_000L,
            ConversationOrder.sortableTime(conversation, message, lastClearHistoryAt = 0L, draftAt = 400L),
        )
        Assert.assertEquals(
            "a conversation with a draft and no message is timed by the draft",
            2_000L,
            ConversationOrder.sortableTime(conversation, null, lastClearHistoryAt = 0L, draftAt = 2_000L),
        )
    }

    @Test
    fun theSortableTimeIsTheLastMessagesReceivedTimeAndNotThePointersInstant() {
        val conversation = conversation("c1", created = 500L, lastMessageAt = 9_000L)
        val received = message("m1", timeSent = 5_000L, timeReceived = 7_000L)
        Assert.assertEquals(
            "the tree reads the last row's received time; the pointer's instant is a different number",
            7_000L,
            ConversationOrder.sortableTime(conversation, received, lastClearHistoryAt = 0L, draftAt = null),
        )
        Assert.assertNotEquals(
            "and the two disagree on this fixture, which is what the cell is for",
            conversation.lastMessageAt,
            ConversationOrder.sortableTime(conversation, received, lastClearHistoryAt = 0L, draftAt = null),
        )
        Assert.assertEquals(
            "a row with no received time falls back to when it was sent",
            5_000L,
            ConversationOrder.sortableTime(
                conversation,
                message("m2", timeSent = 5_000L, timeReceived = null),
                lastClearHistoryAt = 0L,
                draftAt = null,
            ),
        )
    }

    @Test
    fun theUuidsAreTheRowsOwnIdsInTheOrderTheyAreDrawn() {
        val rows = ConversationOrder.sort(listOf(row("c"), row("a", pinned = true), row("b")), mapOf("c" to 900L))
        Assert.assertEquals(
            "the drawn order, named: the pinned row first, then by time",
            listOf("a", "c", "b"),
            ConversationOrder.uuids(rows),
        )
        Assert.assertEquals(
            "and the suggestion is asked about exactly that list, so the two cannot disagree",
            "c",
            ConversationOrder.suggestion(ConversationOrder.uuids(rows), excluded = "a"),
        )
        Assert.assertEquals("an empty list needs no rows", emptyList<String>(), ConversationOrder.uuids(emptyList()))
    }

    @Test
    fun theSuggestionIsTheFirstRowThatIsNotExcluded() {
        val uuids = listOf("a", "b", "c")
        Assert.assertEquals("a", ConversationOrder.suggestion(uuids, excluded = null))
        Assert.assertEquals("b", ConversationOrder.suggestion(uuids, excluded = "a"))
        Assert.assertEquals(
            "a caller that already has the only conversation is offered nothing",
            null,
            ConversationOrder.suggestion(listOf("a"), excluded = "a"),
        )
        Assert.assertEquals("and an empty list offers nothing", null, ConversationOrder.suggestion(emptyList(), excluded = null))
    }

    /** One row, with only the fields the order reads spelled by the cell. */
    private fun row(id: String, pinned: Boolean = false, withSelf: Boolean = false): UiConversation =
        UiConversation(
            id = ConversationId(id),
            name = id.uppercase(),
            jid = "$id@example.org",
            lastMessageId = null,
            lastMessageAt = 0L,
            preview = UiPreview.Absent,
            unread = 0,
            muted = false,
            archived = false,
            pinned = pinned,
            kind = ConversationKind.ONE_TO_ONE,
            withSelf = withSelf,
            ongoingCall = false,
            language = UiLanguagePair(conversationLanguage = "de", appLanguage = "fi", overridden = false),
            translation = UiTranslationMode.ON,
        )

    private fun conversation(id: String, created: Long, lastMessageAt: Long? = null): ConversationSnapshot =
        ConversationSnapshot(
            id = id,
            accountId = "a1",
            name = null,
            jid = "$id@example.org",
            contactUuid = null,
            mode = Conversation.MODE_SINGLE.toLong(),
            status = Conversation.STATUS_AVAILABLE.toLong(),
            created = created,
            attributes = null,
            detectedLanguage = null,
            languageOverride = null,
            lastMessageId = null,
            lastMessageAt = lastMessageAt,
        )

    private fun message(id: String, timeSent: Long?, timeReceived: Long?): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = timeSent,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = Message.TYPE_TEXT.toLong(),
            status = Message.STATUS_RECEIVED.toLong(),
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
            errorMsg = null,
            bodyLanguage = null,
            reactions = null,
            readByMarkers = null,
            translationState = Message.TRANSLATION_NONE.toLong(),
            translationLang = null,
            timeReceived = timeReceived,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = null, original = "Hei"),
        )
}
