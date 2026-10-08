package uk.xa0.tulkki.ui.conversationlist

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.PerProcess
import uk.xa0.tulkki.ui.projection.PreviewWords
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTransferState

/**
 * `ui-8`'s cells over the host's pure half: what the fragment hands the screen after a read.
 *
 * <p>What they hold is the join the Compose list depends on - one row per conversation, in the read's
 * order, each projected with the message its pointer names - and the two cases a read can produce that a
 * cell must not let become a crash or a second query: an empty conversation, and a pointer whose message
 * the read did not return. Neither is an error: the projection's own `Absent` is what a row with nothing
 * to preview draws.
 */
class ConversationListHostTest {

    @Test
    fun theRowsAreTheProjectionOfEachConversationInTheReadsOrder() {
        val mikko = conversation("c1", name = "Mikko", lastMessageId = "m1")
        val sari = conversation("c2", name = "Sari", lastMessageId = "m2")
        val state =
            assemble(
                snapshots = listOf(mikko, sari),
                lastMessages = mapOf("m1" to message("m1", "Hei!"), "m2" to message("m2", "Moi!")),
            )
        Assert.assertEquals("one row per conversation, in the read's order", listOf("c1", "c2"), state.visible.map { it.id.uuid })
        Assert.assertEquals("and each row is the projection of its own conversation", "Mikko", state.visible[0].name)
        Assert.assertFalse("a read landed, so this is not the loading state", state.loading)
        Assert.assertEquals(
            "the row's preview is the message the pointer named",
            UiPreview.Visible("Hei!"),
            state.visible[0].preview,
        )
    }

    @Test
    fun aPointerThatNamesNothingIsNothingToPreviewAndNotACrash() {
        val empty = conversation("c1", lastMessageId = null)
        val dangling = conversation("c2", lastMessageId = "m9")
        val state = assemble(snapshots = listOf(empty, dangling), lastMessages = emptyMap())
        Assert.assertEquals(2, state.visible.size)
        Assert.assertEquals(UiPreview.Absent, state.visible[0].preview)
        Assert.assertEquals(
            "a pointer the read did not resolve is absent, never a blank line",
            UiPreview.Absent,
            state.visible[1].preview,
        )
        Assert.assertEquals(
            "the pointer itself is still the row's identity",
            MessageId("m9"),
            state.visible[1].lastMessageId,
        )
    }

    @Test
    fun theStateCarriesTheFiltersAndTheConnectionItWasGiven() {
        val state =
            assemble(
                snapshots = listOf(conversation("c1", group = true), conversation("c2")),
                filter = ConversationFilter.GROUPS,
                connection = UiConnection.CONNECTING,
                archivedVisible = true,
            )
        Assert.assertEquals(ConversationFilter.GROUPS, state.filter)
        Assert.assertEquals(UiConnection.CONNECTING, state.connection)
        Assert.assertTrue(state.archivedVisible)
        Assert.assertEquals("the filter is applied to the rows the host read", listOf("c1"), state.visible.map { it.id.uuid })
    }

    private fun assemble(
        snapshots: List<ConversationSnapshot>,
        lastMessages: Map<String, MessageSnapshot> = emptyMap(),
        filter: ConversationFilter = ConversationFilter.ALL,
        connection: UiConnection = UiConnection.CONNECTED,
        archivedVisible: Boolean = false,
    ) = ConversationListHost.assemble(
        snapshots = snapshots,
        lastMessages = lastMessages,
        appLanguage = "fi",
        interpreter = Interpreter.of("fi", "en"),
        words = PreviewWords { id, args -> if (args.isEmpty()) "s$id" else "s$id:" + args.joinToString("|") },
        now = 1_000L,
        perProcess = PerProcess.NONE,
        filter = filter,
        connection = connection,
        archivedVisible = archivedVisible,
    )

    private fun conversation(
        id: String,
        name: String? = null,
        lastMessageId: String? = null,
        group: Boolean = false,
    ): ConversationSnapshot =
        ConversationSnapshot(
            id = id,
            accountId = "a1",
            name = name,
            jid = "$id@example.org",
            contactUuid = null,
            mode = if (group) Conversation.MODE_MULTI.toLong() else Conversation.MODE_SINGLE.toLong(),
            status = Conversation.STATUS_AVAILABLE.toLong(),
            created = 0L,
            attributes = null,
            detectedLanguage = null,
            languageOverride = null,
            lastMessageId = lastMessageId,
            lastMessageAt = 0L,
        )

    private fun message(id: String, original: String?): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = 0L,
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
            translationState = Message.TRANSLATION_DONE.toLong(),
            translationLang = "fi",
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = original, original = original),
        )
}
