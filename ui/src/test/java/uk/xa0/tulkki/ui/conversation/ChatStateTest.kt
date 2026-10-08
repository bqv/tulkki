package uk.xa0.tulkki.ui.conversation

import org.junit.After
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.projection.ConversationFacts
import uk.xa0.tulkki.ui.projection.EnglishInHand
import uk.xa0.tulkki.ui.projection.MessageFacts
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.ProjectionSettings
import uk.xa0.tulkki.ui.projection.UiEnglishRow

/**
 * `ChatState`'s cells: what the state layer decides, what it passes through, and the one transition a
 * purchase makes.
 *
 * <p>The state holds no rules of its own - the projection has them - so what is pinned here is the split:
 * the rows are [uk.xa0.tulkki.ui.projection.MessageProjection]'s, `selected` (which §2.2.1 #2 keeps out of
 * the projector) is applied by the assembly, `revealed` is carried so a reveal re-projects without touching
 * the file, and the unread divider's position is [UnreadDivider]'s.
 *
 * <p>The fixture is a compact snapshot builder, not a copy of the projection's: a state cell is about
 * counting and identity, so it spells only the columns it is about.
 */
class ChatStateTest {

    @After
    fun nothingIsLeftInTheMemo() {
        EnglishHold.clear()
    }

    @Test
    fun theRowsAreTheProjectionsAndTheSelectionIsAppliedHere() {
        val state =
            ChatState.of(
                listOf(incoming("m1"), incoming("m2")),
                conversation = conversation(),
                selected = setOf(MessageId("m2")),
            )
        Assert.assertEquals("one row per snapshot, in the read's own order", 2, state.rows!!.size)
        Assert.assertFalse("the projector leaves selection alone, so the assembly applies it", state.rows!![0].selected)
        Assert.assertTrue(state.rows!![1].selected)
        Assert.assertTrue(state.selecting)
        Assert.assertFalse(state.toggled(MessageId("m2")).selecting)
        Assert.assertTrue(state.toggled(MessageId("m2")).selected.isEmpty())
        Assert.assertTrue(state.toggled(MessageId("m1")).selected.contains(MessageId("m1")))
        Assert.assertFalse(state.cleared().selecting)
        Assert.assertEquals("a reveal is one more id in the set", setOf(MessageId("m1")), state.revealed(MessageId("m1")).revealed)
    }

    @Test
    fun nothingYetIsNotTheSameAsNothing() {
        val before = ChatState.of(null, conversation = conversation())
        Assert.assertTrue("no emission yet is the loading shape", before.loading)
        Assert.assertFalse("and never the empty one", before.empty)
        Assert.assertNull("with nothing to divide", before.unreadAnchor)
        val landed = ChatState.of(emptyList(), conversation = conversation())
        Assert.assertFalse(landed.loading)
        Assert.assertTrue("the read landed and there is nothing: §4.6's explainer", landed.empty)
    }

    @Test
    fun theDividerSitsAboveTheNewestUnreadRows() {
        val state =
            ChatState.of(
                listOf(incoming("m1"), incoming("m2"), incoming("m3"), incoming("m4"), incoming("m5")),
                conversation = conversation(),
                unreadCount = 2,
            )
        Assert.assertEquals("the newest two are the unread ones", 3, state.unreadAnchor)
        Assert.assertNull("nothing unread has no divider", ChatState.of(listOf(incoming("m1")), conversation = conversation()).unreadAnchor)
    }

    /**
     * The parent's own requirement for this slice: `UiEnglishRow.Pending` is the state a bare bar draws, and
     * a purchase must resolve it to something else - without a new read, and without a second copy of the
     * words in the state.
     */
    @Test
    fun aPurchaseLandsInTheOneMemoAndTheRowStopsBeingPending() {
        val messages =
            listOf(
                incoming(
                    "m1",
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                    translationLang = "de",
                )
            )
        val settings = ProjectionSettings(showEnglishRow = true)
        val past = conversation(conversationLanguage = "en")
        Assert.assertTrue(
            "nothing bought: a bare bar the owner can tap",
            ChatState.of(messages, MemoFacts, settings, past).rows!!.single().english is UiEnglishRow.Pending,
        )
        EnglishHold.put("m1", "the purchased English")
        val bought = ChatState.of(messages, MemoFacts, settings, past)
        Assert.assertTrue(
            "bought and unrevealed: blurred, never readable",
            bought.rows!!.single().english is UiEnglishRow.Concealed,
        )
        val revealed = ChatState.of(messages, MemoFacts, settings, past, revealed = bought.revealed(MessageId("m1")).revealed)
        Assert.assertEquals(
            "and the tap is what makes it readable",
            UiEnglishRow.Visible("the purchased English"),
            revealed.rows!!.single().english,
        )
        // The memo is the seam's answer and nothing else: an id nobody bought is not in hand.
        Assert.assertNull(EnglishHold.of("m2"))
    }

    /** The seam as the host answers it for this one fact: `english` is the shared memo, and nothing else. */
    private object MemoFacts : MessageFacts by MessageFacts.NONE {
        override fun english(message: MessageSnapshot): EnglishInHand? = EnglishHold.of(message.id)
    }

    private fun conversation(
        appLanguage: String = "fi",
        studyLanguage: String? = "en",
        conversationLanguage: String? = "de",
        conversationName: String? = "Alice",
    ): ConversationFacts =
        ConversationFacts(
            Interpreter.of("fi", "en"),
            appLanguage,
            studyLanguage,
            conversationLanguage,
            conversationName,
            90_000L,
            false,
            false,
        )

    private fun incoming(
        id: String,
        original: String? = "ein Satz",
        translated: String? = null,
        translationState: Long = Message.TRANSLATION_NONE.toLong(),
        translationLang: String? = null,
    ): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = 0L,
            counterpart = "bob@example.org",
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
            translationState = translationState,
            translationLang = translationLang,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = translated, original = original),
        )
}
