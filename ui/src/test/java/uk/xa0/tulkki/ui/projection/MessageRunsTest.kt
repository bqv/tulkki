package uk.xa0.tulkki.ui.projection

import java.time.ZoneId
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message

/**
 * `ui-8`'s cells over `runFlags`, `Design: the Compose UI` §4.1's own test list - "a run of three, direction
 * change, occupant change, day break, list of one" - plus the two conditions the tree added that the design's
 * sentence does not mention: `MessageAdapter.merge`'s ninety-second window, and the avatar's side.
 *
 * <p>Which of these flags a screen draws with is the screen's; what these hold is which flags a list implies.
 */
class MessageRunsTest {

    /** The tree's `Config.MESSAGE_MERGE_WINDOW`, which `:ui` may not name - so the cell spells it. */
    private val window = 90_000L

    private val zone = ZoneId.of("UTC")

    private fun flags(
        messages: List<MessageSnapshot>,
        group: Boolean = false,
        forceNames: Boolean = false,
    ): List<UiRunFlags> = MessageRuns.runFlags(messages, window, group, forceNames, zone)

    @Test
    fun aRunOfThreeHasOneHeadOneTailAndAFirstOfDay() {
        val run = flags(
            listOf(
                incoming("m1", at = 1_000L),
                incoming("m2", at = 2_000L),
                incoming("m3", at = 3_000L),
            )
        )
        Assert.assertEquals(
            "the head opens the run and carries the date",
            UiRunFlags(firstOfRun = true, lastOfRun = false, firstOfDay = true, showAvatar = true, showName = false),
            run[0],
        )
        Assert.assertEquals(
            "the middle is neither head nor tail",
            UiRunFlags(firstOfRun = false, lastOfRun = false, firstOfDay = false, showAvatar = false, showName = false),
            run[1],
        )
        Assert.assertEquals(
            "and the tail closes it - which is where the avatar's own edge is",
            UiRunFlags(firstOfRun = false, lastOfRun = true, firstOfDay = false, showAvatar = false, showName = false),
            run[2],
        )
    }

    @Test
    fun aDirectionChangeAndAnOccupantChangeAreBothANewRun() {
        Assert.assertTrue(
            "the answer is its own run's head",
            flags(listOf(incoming("m1", 1_000L), outgoing("m2", 2_000L)))[1].firstOfRun,
        )
        Assert.assertTrue(
            "and so is a second person in the same room",
            flags(
                listOf(
                    incoming("m1", 1_000L, occupant = "a"),
                    incoming("m2", 2_000L, occupant = "b"),
                ),
                group = true,
            )[1].firstOfRun,
        )
        Assert.assertFalse(
            "while the same person continues their own",
            flags(
                listOf(
                    incoming("m1", 1_000L, occupant = "a"),
                    incoming("m2", 2_000L, occupant = "a"),
                ),
                group = true,
            )[1].firstOfRun,
        )
    }

    @Test
    fun theWindowsNinetySecondsIsWhatTheDesignsSentenceLeavesOut() {
        Assert.assertFalse(
            "inside the tree's window the rows are one run",
            flags(listOf(incoming("m1", 1_000L), incoming("m2", 2_000L)))[1].firstOfRun,
        )
        Assert.assertTrue(
            "a second over it is another run, however alike they look",
            flags(listOf(incoming("m1", 1_000L), incoming("m2", 1_000L + window + 1)))[1].firstOfRun,
        )
        Assert.assertFalse(
            "and exactly on it is still inside, as `<=` says",
            flags(listOf(incoming("m1", 1_000L), incoming("m2", 1_000L + window)))[1].firstOfRun,
        )
    }

    @Test
    fun aDayBreakOpensTheDayAndTheAvatarFollowsTheRowsOwnSide() {
        val across = listOf(
            incoming("m1", 1_000L),
            incoming("m2", 1_000L + 24 * 60 * 60 * 1000L),
        )
        val run = flags(across)
        Assert.assertTrue("a new day is a new run", run[1].firstOfRun)
        Assert.assertTrue("and carries the date", run[1].firstOfDay)

        val pair = flags(listOf(incoming("m1", 1_000L), outgoing("m2", 2_000L)))
        Assert.assertTrue("an incoming run wears its avatar at the head", pair[0].showAvatar)
        Assert.assertTrue("and one on the owner's side at the tail", pair[1].showAvatar)
        Assert.assertFalse(
            "the owner's own rows carry no name",
            pair[1].showName,
        )
        Assert.assertTrue(
            "a room's incoming row does, when its avatar is drawn",
            flags(
                listOf(incoming("m1", 1_000L, occupant = "a"), incoming("m2", 2_000L, occupant = "a")),
                group = true,
            )[0].showName,
        )
        Assert.assertTrue(
            "and the owner's setting forces one anywhere",
            flags(listOf(incoming("m1", 1_000L)), forceNames = true)[0].showName,
        )
    }

    @Test
    fun aListOfOneIsBothItsOwnHeadAndItsOwnTail() {
        Assert.assertEquals(
            UiRunFlags(firstOfRun = true, lastOfRun = true, firstOfDay = true, showAvatar = true, showName = false),
            flags(listOf(incoming("m1", 1_000L)))[0],
        )
        Assert.assertEquals("and an empty list has no flags at all", emptyList<UiRunFlags>(), flags(emptyList()))
    }

    @Test
    fun aSystemRowIsItsOwnKindAndNeverMergesWithAMessage() {
        val run = flags(
            listOf(
                system("m1", 1_000L),
                incoming("m2", 2_000L),
                system("m3", 3_000L),
            )
        )
        Assert.assertTrue("a system row opens its own run", run[1].firstOfRun)
        Assert.assertTrue("and the message after it opens another", run[2].firstOfRun)
    }

    /**
     * The store has not classified the row (`status` is NULL): it is the tree's own `STATUS_RECEIVED` -
     * `Message`'s field default, and what a cursor `getInt` answers for a NULL column - so its run flags
     * are the received ones, and `MessageProjection.direction` draws it on the same side. The two used to
     * disagree here: the projection read the null as received while this file compared the raw column and
     * read it as sent, so an unclassified row was drawn left with a sent run's avatar edge and gaps.
     */
    @Test
    fun anUnclassifiedRowIsAReceivedRowForTheRunKeyAndForTheDrawnSide() {
        val run = flags(listOf(unclassified("m1", 1_000L), unclassified("m2", 2_000L)))
        Assert.assertEquals(
            "the head carries the avatar, which is where a received run's edge is",
            UiRunFlags(firstOfRun = true, lastOfRun = false, firstOfDay = true, showAvatar = true, showName = false),
            run[0],
        )
        Assert.assertEquals(
            "and the tail closes it, exactly as a received run does",
            UiRunFlags(firstOfRun = false, lastOfRun = true, firstOfDay = false, showAvatar = false, showName = false),
            run[1],
        )
        Assert.assertEquals(
            "the drawn side is the same answer, from the one reader",
            Direction.INCOMING,
            MessageProjection.direction(unclassified("m1", 1_000L)),
        )
    }

    private fun incoming(id: String, at: Long, occupant: String? = null): MessageSnapshot =
        message(id, at, Message.STATUS_RECEIVED, occupant)

    private fun outgoing(id: String, at: Long): MessageSnapshot =
        message(id, at, Message.STATUS_SEND, null)

    private fun system(id: String, at: Long): MessageSnapshot =
        message(id, at, Message.STATUS_RECEIVED, null, type = Message.TYPE_STATUS)

    /** A row the store has not classified: the `status` column is NULL, not zero. */
    private fun unclassified(id: String, at: Long): MessageSnapshot =
        message(id, at, Message.STATUS_RECEIVED, null).copy(status = null)

    private fun message(
        id: String,
        at: Long,
        status: Int,
        occupant: String?,
        type: Int = Message.TYPE_TEXT,
    ): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = at,
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
            occupantId = occupant,
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
            bodies = ConversationBodies(translated = null, original = "Hei"),
        )
}
