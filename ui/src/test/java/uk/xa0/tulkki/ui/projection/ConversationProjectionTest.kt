package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.R

/**
 * `ui-8`'s cells over the conversation-list row, docs/MIGRATION.md "Design: the Compose UI" §2.2's
 * `UiConversation` and §2.3 invariant 4.
 *
 * <p>What they hold: the pointer is an id and the preview is the projection of the row it names (never a
 * copy); an empty conversation has nothing to preview rather than an empty line; the three flags have the
 * sources `ConversationSnapshot`'s own comment names - `mode`, `status`, the `muted_till` attribute
 * against a clock the caller passes - while the unread count comes from the service seam, because no
 * column carries it; and the language pair and the translation mode are the pair every surface names.
 *
 * <p>And the one case that is the row's own rather than its message's: the owner's draft, which outranks
 * the newest message while nothing is unread.
 */
class ConversationProjectionTest {

    @Test
    fun thePointerIsAnIdAndThePreviewIsTheProjectionOfTheRowItNames() {
        val message = message(id = "m7", original = "Hei, mitä kuuluu?")
        val conversation = conversation(lastMessageId = "m7", lastMessageAt = 1_700_000_000_000L)
        val row = project(conversation, message)
        Assert.assertEquals("the pointer is an id, never the text", MessageId("m7"), row.lastMessageId)
        Assert.assertEquals("and its instant is the one denormalised scalar", 1_700_000_000_000L, row.lastMessageAt)
        Assert.assertEquals(
            "the preview is the message projection of the row the pointer names",
            MessagePreview.of(message, "Mikko", ON, words),
            row.preview,
        )
    }

    @Test
    fun aConversationWithNoMessagesHasNothingToPreview() {
        val row = project(conversation(lastMessageId = null, lastMessageAt = null), null)
        Assert.assertEquals(UiPreview.Absent, row.preview)
        Assert.assertNull(row.lastMessageId)
        Assert.assertEquals("an unset instant is the epoch, not a crash", 0L, row.lastMessageAt)
    }

    /**
     * The draft is the row's own line, and `Conversation.getDraft()`'s conditions are this cell: nothing
     * unread, and later than the newest message. It rides `Visible.draft` rather than a case of its own -
     * these types declare exactly one `String` property, which `ProjectionTypesTest` holds - and what the
     * order does with the draft is `ConversationOrderTest.aDraftBeatsTheLastMessage`'s and unchanged: this
     * cell only decides what is drawn.
     */
    @Test
    fun theDraftIsTheDrawnLineAndOutranksTheNewestMessage() {
        val draft = """{"next_message":"Kirjoitan tässä","next_message_timestamp":"2000"}"""
        val newest = message(id = "m1", original = "Hei", timeSent = 1_000L)
        Assert.assertEquals(
            "a draft later than the newest message is the row's line, not the message",
            UiPreview.Visible("Kirjoitan tässä", draft = true),
            project(conversation(attributes = draft), newest).preview,
        )
        Assert.assertEquals(
            "and on a conversation with no message at all, which is the case a draft mostly is",
            UiPreview.Visible("Kirjoitan tässä", draft = true),
            project(conversation(attributes = draft), null).preview,
        )
        Assert.assertEquals(
            "an unread row draws the message: what is not yet read outranks what is being typed",
            MessagePreview.of(newest, "Mikko", ON, words),
            project(conversation(attributes = draft), newest, perProcess = facts(unread = 1)).preview,
        )
        Assert.assertEquals(
            "a draft older than the newest message is not a draft at all",
            MessagePreview.of(newest, "Mikko", ON, words),
            project(
                    conversation(attributes = """{"next_message":"vanha","next_message_timestamp":"500"}"""),
                    newest,
                )
                .preview,
        )
        Assert.assertEquals(
            "a blank draft leaves the message, as `setNextMessage(null)` stores it",
            MessagePreview.of(newest, "Mikko", ON, words),
            project(
                    conversation(attributes = """{"next_message":"","next_message_timestamp":"2000"}"""),
                    newest,
                )
                .preview,
        )
        Assert.assertEquals(
            "and the column is untrusted like the others: an unreadable one is no draft",
            MessagePreview.of(newest, "Mikko", ON, words),
            project(conversation(attributes = "not json"), newest).preview,
        )
    }

    @Test
    fun theKindIsTheStoredModeAndItsTwoRoomAttributes() {
        Assert.assertEquals(
            ConversationKind.ONE_TO_ONE,
            project(conversation(mode = Conversation.MODE_SINGLE.toLong())).kind,
        )
        Assert.assertEquals(
            "a multi without the room's two attributes is the tree's channel",
            ConversationKind.CHANNEL,
            project(conversation(mode = Conversation.MODE_MULTI.toLong())).kind,
        )
        Assert.assertEquals(
            "private and non-anonymous is the tree's group",
            ConversationKind.GROUP,
            project(
                conversation(
                    mode = Conversation.MODE_MULTI.toLong(),
                    attributes = """{"members_only":"true","non_anonymous":"true"}""",
                )
            ).kind,
        )
        Assert.assertEquals(
            "either one missing is not private-and-non-anonymous",
            ConversationKind.CHANNEL,
            project(
                conversation(
                    mode = Conversation.MODE_MULTI.toLong(),
                    attributes = """{"members_only":"true"}""",
                )
            ).kind,
        )
    }

    @Test
    fun theGroupFilterFactIsTheKindsOwnDerivation() {
        Assert.assertTrue(project(conversation(mode = Conversation.MODE_MULTI.toLong())).group)
        Assert.assertTrue(
            project(
                conversation(
                    mode = Conversation.MODE_MULTI.toLong(),
                    attributes = """{"members_only":"true","non_anonymous":"true"}""",
                )
            ).group,
        )
        Assert.assertFalse(project(conversation(mode = Conversation.MODE_SINGLE.toLong())).group)
    }

    @Test
    fun theArchivedFlagIsTheStoredStatus() {
        Assert.assertTrue(project(conversation(status = Conversation.STATUS_ARCHIVED.toLong())).archived)
        Assert.assertFalse(project(conversation(status = Conversation.STATUS_AVAILABLE.toLong())).archived)
    }

    /**
     * The mute is the stored instant against the caller's clock - the projector reads no clock of its
     * own - and an attribute that is missing, blank or unreadable is "not muted" rather than a throw.
     */
    @Test
    fun theMuteIsTheAttributeAgainstTheClock() {
        val muted = conversation(attributes = """{"muted_till":"2000"}""")
        Assert.assertTrue("before the stored instant", project(muted, now = 1_000L).muted)
        Assert.assertFalse("after it", project(muted, now = 3_000L).muted)
        Assert.assertTrue(
            "a number in the attribute reads the same as the string the model writes",
            project(conversation(attributes = """{"muted_till":2000}"""), now = 1_000L).muted,
        )
        Assert.assertFalse("no attributes at all", project(conversation(attributes = null), now = 1_000L).muted)
        Assert.assertFalse(
            "an unreadable column falls back rather than throwing",
            project(conversation(attributes = "not json at all"), now = 1_000L).muted,
        )
    }

    @Test
    fun theLanguagePairNamesBothSidesAndWhichOneWon() {
        val chosen = project(conversation(detectedLanguage = "de", languageOverride = "sv"))
        Assert.assertEquals("the override is the conversation's language", "sv", chosen.language.conversationLanguage)
        Assert.assertTrue("and it is the one in force", chosen.language.overridden)
        Assert.assertEquals("the app language is always the other half", "fi", chosen.language.appLanguage)

        val detected = project(conversation(detectedLanguage = "de", languageOverride = null))
        Assert.assertEquals("de", detected.language.conversationLanguage)
        Assert.assertFalse(detected.language.overridden)

        Assert.assertNull(
            "nobody has read a language yet",
            project(conversation(detectedLanguage = null, languageOverride = null)).language.conversationLanguage,
        )
        Assert.assertFalse(
            "a blank override is not a choice",
            project(conversation(detectedLanguage = "de", languageOverride = " ")).language.overridden,
        )
    }

    @Test
    fun theTranslationModeIsTheSwitchAndTheMissingLanguage() {
        Assert.assertEquals(
            "off is the interpreter's own predicate, whatever the languages say",
            UiTranslationMode.OFF,
            project(conversation(detectedLanguage = "de"), interpreter = OFF).translation,
        )
        Assert.assertEquals(
            UiTranslationMode.ON,
            project(conversation(detectedLanguage = "de"), interpreter = ON).translation,
        )
        Assert.assertEquals(
            "on, but nobody has read a language: a state, never an invented default",
            UiTranslationMode.LANGUAGE_UNKNOWN,
            project(conversation(detectedLanguage = null), interpreter = ON).translation,
        )
    }

    /** The pinned flag is the attribute the tree sorts on; the note-to-self row is the contact's. */
    @Test
    fun thePinnedFlagIsTheAttributeAndTheNoteToSelfIsTheSeams() {
        Assert.assertTrue(
            project(conversation(attributes = """{"pinned_on_top":"true"}""")).pinned
        )
        Assert.assertFalse("an absent attribute is not pinned", project(conversation()).pinned)
        Assert.assertFalse(
            "and an unreadable column falls back rather than throwing",
            project(conversation(attributes = "not json")).pinned,
        )
        Assert.assertTrue(project(conversation(), perProcess = facts(withSelf = true)).withSelf)
        Assert.assertFalse(project(conversation()).withSelf)
    }

    /**
     * The four fields the second line and the trailing strip draw: the sender's own three-way rule (the
     * tree's answer to "why is there a name in the middle of this row"), the tick from the row's status, and
     * the two facts only the running app knows. What each mark is *drawn* in is the screen's;
     * `UiRowMarkTest` and `UiAccountLineTest` hold the states themselves.
     */
    @Test
    fun theSenderAndTheStripAreTheRowsOwnRuleAndTheSeamsAnswers() {
        val received = message(id = "m1", original = "Hei")
        Assert.assertNull(
            "a one-to-one says nothing about who sent it: the conversation's name is already the first line",
            project(conversation(), received).sender,
        )
        Assert.assertEquals(
            "a room says who, from the row's own recorded counterpart",
            "them@example.org:",
            project(conversation(mode = Conversation.MODE_MULTI.toLong()), received).sender,
        )
        val sent = message(id = "m2", original = "Hei", status = Message.STATUS_SEND)
        Assert.assertEquals(
            "and what the owner sent says so in the same slot",
            "s${R.string.me}:",
            project(conversation(), sent).sender,
        )
        Assert.assertEquals(
            "the tick is the row's own status",
            R.drawable.ic_done_24dp,
            project(conversation(), sent).tick,
        )
        Assert.assertNull("and a received row has none", project(conversation(), received).tick)
        Assert.assertEquals(
            "the notification mark is the seam's answer",
            UiNotification.MUTED_UNTIL,
            project(conversation(), perProcess = facts(notification = UiNotification.MUTED_UNTIL))
                .notification,
        )
        Assert.assertEquals(
            "and so is the account line",
            "sari@example.org",
            project(conversation(), perProcess = facts(account = "sari@example.org")).account,
        )
    }

    @Test
    fun theUnreadCountIsTheServiceFact() {
        Assert.assertEquals(5, project(conversation(), perProcess = facts(unread = 5)).unread)
        Assert.assertEquals("and none is none", 0, project(conversation()).unread)
    }

    /**
     * The two fields the row gained: the presence dot is the seam's answer and nothing else, and the row's
     * clock is the newest message's instant - or the draft's, while the owner is still writing one, which is
     * what the tree timed a row by. What the clock *says* for a given instant is `ConversationRowTimeTest`'s.
     */
    @Test
    fun thePresenceIsTheSeamAndTheClockIsTheDraftsWhenThereIsOne() {
        Assert.assertEquals("no answer from the seam is no dot", UiPresence.UNKNOWN, project(conversation()).presence)
        Assert.assertEquals(
            "and the seam's answer is the row's",
            UiPresence.DND,
            project(conversation(), perProcess = facts(presence = UiPresence.DND)).presence,
        )

        val messageAt = 1_700_000_000_000L
        val now = messageAt + 86_400_000L
        val newest = message(id = "m1", original = "Hei", timeSent = messageAt)
        Assert.assertEquals(
            "the row is timed by the newest message when there is no draft",
            ConversationRowTime.of(messageAt, now, true, words),
            project(conversation(lastMessageAt = messageAt), newest, now = now).time,
        )
        Assert.assertEquals(
            "and by the draft's own instant while one is being written",
            ConversationRowTime.of(now - 10_000L, now, true, words),
            project(
                    conversation(
                        attributes =
                            """{"next_message":"Kirjoitan","next_message_timestamp":"${now - 10_000L}"}""",
                        lastMessageAt = messageAt,
                    ),
                    newest,
                    now = now,
                )
                .time,
        )
    }

    /** The recording words seam: the resource and its arguments, as the projection chose them. */
    private val words = PreviewWords { id, args ->
        if (args.isEmpty()) "s$id" else "s$id:" + args.joinToString("|")
    }

    /**
     * A room's name, in `Conversation.getName`'s own order: the live room, its subject, its bookmark, its
     * participants, then the room's own local part - and never the stored column, which the tree does not
     * read for a room. A one-to-one keeps its column and falls back to the whole address.
     */
    @Test
    fun aRoomsNameIsComposedWhileAOneToOneKeepsItsOwn() {
        val room = conversation(mode = Conversation.MODE_MULTI.toLong())
        Assert.assertEquals(
            "the live room's own name wins over everything",
            "Tulkki",
            project(room, perProcess = facts(room = RoomName("Tulkki", "aihe", "kirjanmerkki", "Sari"))).name,
        )
        Assert.assertEquals(
            "then the subject",
            "aihe",
            project(room, perProcess = facts(room = RoomName(" ", "aihe", "kirjanmerkki", "Sari"))).name,
        )
        Assert.assertEquals(
            "then the bookmark",
            "kirjanmerkki",
            project(room, perProcess = facts(room = RoomName(null, null, "kirjanmerkki", "Sari"))).name,
        )
        Assert.assertEquals(
            "then the participants",
            "Sari",
            project(room, perProcess = facts(room = RoomName(null, null, null, "Sari"))).name,
        )
        Assert.assertEquals(
            "and with none of the four, the room's own localpart - never the whole address",
            "tulkki",
            project(
                    conversation(
                        jid = "tulkki@conference.example.org",
                        mode = Conversation.MODE_MULTI.toLong(),
                    )
                )
                .name,
        )
        Assert.assertEquals(
            "a one-to-one keeps its own name when it has one",
            "Mikko",
            project(conversation()).name,
        )
        Assert.assertEquals(
            "and a one-to-one with no name draws its localpart too, never the address",
            "alice",
            project(conversation(name = null, jid = "alice@example.org")).name,
        )
        Assert.assertEquals(
            "while a JID with no localpart at all leaves the name empty rather than printing one",
            "",
            project(conversation(name = null, jid = "example.org")).name,
        )
    }

    @Test
    fun theNoteToSelfRowIsWrappedInTheTreesOwnTitle() {
        Assert.assertEquals(
            "the deleted adapter wrapped whatever `getName` returned, and the row does the same",
            "s${R.string.note_to_self_conversation_title}:Mikko",
            project(conversation(), perProcess = facts(withSelf = true)).name,
        )
    }

    @Test
    fun aRoomDrawsNoPresenceDotEvenWhenTheSeamReportsOne() {
        Assert.assertEquals(
            "a one-to-one's dot is the seam's answer",
            UiPresence.ONLINE,
            project(conversation(), perProcess = facts(presence = UiPresence.ONLINE)).presence,
        )
        Assert.assertEquals(
            "and a room's is nothing whatever the seam says, because a room is not a person - the case a",
            UiPresence.UNKNOWN,
            project(
                    conversation(mode = Conversation.MODE_MULTI.toLong()),
                    perProcess = facts(presence = UiPresence.ONLINE),
                )
                .presence,
        )
    }

    /** A live state the cell is not about, with the answers it is. */
    private fun facts(
        unread: Int = 0,
        withSelf: Boolean = false,
        presence: UiPresence = UiPresence.UNKNOWN,
        notification: UiNotification = UiNotification.NONE,
        account: String? = null,
        room: RoomName? = null,
    ): PerProcess =
        object : PerProcess {
            override fun mutedOccupant(message: MessageSnapshot): Boolean = false

            override fun moderated(message: MessageSnapshot): Boolean = false

            override fun transfer(message: MessageSnapshot): UiTransferState = UiTransferState.None

            override fun unreadCount(conversation: ConversationSnapshot): Int = unread

            override fun withSelf(conversation: ConversationSnapshot): Boolean = withSelf

            override fun ongoingCall(conversation: ConversationSnapshot): Boolean = false

            override fun presence(conversation: ConversationSnapshot): UiPresence = presence

            override fun notification(conversation: ConversationSnapshot): UiNotification = notification

            override fun accountLine(conversation: ConversationSnapshot): String? = account

            override fun roomName(conversation: ConversationSnapshot): RoomName? = room
        }

    private fun project(
        conversation: ConversationSnapshot,
        lastMessage: MessageSnapshot? = null,
        interpreter: Interpreter = ON,
        now: Long = 1_000L,
        perProcess: PerProcess = PerProcess.NONE,
    ): UiConversation =
        ConversationProjection.of(
            conversation = conversation,
            lastMessage = lastMessage,
            appLanguage = "fi",
            interpreter = interpreter,
            words = words,
            now = now,
            perProcess = perProcess,
        )

    private companion object {
        /** The interpreter on, and off: two different languages, and the shipped pair equal to itself. */
        val ON: Interpreter = Interpreter.of("fi", "en")

        val OFF: Interpreter = Interpreter.of("fi", "fi")
    }

    /** One conversation row, with only the fields a cell reads spelled by the cell. */
    private fun conversation(
        id: String = "c1",
        name: String? = "Mikko",
        jid: String? = "mikko@example.org",
        mode: Long? = Conversation.MODE_SINGLE.toLong(),
        status: Long? = Conversation.STATUS_AVAILABLE.toLong(),
        attributes: String? = null,
        detectedLanguage: String? = null,
        languageOverride: String? = null,
        lastMessageId: String? = null,
        lastMessageAt: Long? = null,
    ): ConversationSnapshot =
        ConversationSnapshot(
            id = id,
            accountId = "a1",
            name = name,
            jid = jid,
            contactUuid = null,
            mode = mode,
            status = status,
            created = 0L,
            attributes = attributes,
            detectedLanguage = detectedLanguage,
            languageOverride = languageOverride,
            lastMessageId = lastMessageId,
            lastMessageAt = lastMessageAt,
        )

    /** One message row, for the pointer's own read. */
    private fun message(
        id: String,
        original: String?,
        timeSent: Long = 0L,
        status: Int = Message.STATUS_RECEIVED,
    ): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = timeSent,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = Message.TYPE_TEXT.toLong(),
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
            errorMsg = null,
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
            bodies = ConversationBodies(translated = null, original = original),
        )
}
