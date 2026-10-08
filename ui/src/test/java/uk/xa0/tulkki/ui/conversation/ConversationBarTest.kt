package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.R as XmppR

/**
 * The room's own answers to a join, pinned cell by cell.
 *
 * <p>`updateSnackBar`'s MUC switch was 15 reachable arms and no test: the strings moved with the
 * switch, so only a cell can say that an arm was not quietly dropped or re-worded. The six the
 * redesign brief names by hand - nick in use, banned, kicked, shutdown, destroyed, server timeout -
 * get a cell each, and the one arm the Java could not reach (`INVALID_NICK`'s missing `break`) is
 * pinned to what the owner has always actually seen.
 */
class ConversationBarTest {

    /** `MucOptions.Error.NONE` is the only silence; every other room error says something. */
    @Test
    fun onlyTheNoneErrorDrawsNoBar() {
        val silent =
            MucOptions.Error.entries.filter { ConversationBar.mucError(it, receivedMessages = false) == null }
        Assert.assertEquals(listOf(MucOptions.Error.NONE), silent)
    }

    /** The nick is taken: the fix is a different nick, which is the room's own dialog. */
    @Test
    fun aNickInUseOffersEdit() {
        Assert.assertEquals(
            UiBar(XmppR.string.nick_in_use, BarVerb.EDIT_NICK),
            ConversationBar.mucError(MucOptions.Error.NICK_IN_USE, receivedMessages = false),
        )
    }

    /** A ban cannot be retried, so the only offer is to leave. */
    @Test
    fun aBanOffersLeave() {
        Assert.assertEquals(
            UiBar(R.string.conference_banned, BarVerb.LEAVE),
            ConversationBar.mucError(MucOptions.Error.BANNED, receivedMessages = false),
        )
    }

    /** A kick is not a ban: the room's ordinary join is the way back. */
    @Test
    fun aKickOffersJoin() {
        Assert.assertEquals(
            UiBar(R.string.conference_kicked, BarVerb.JOIN),
            ConversationBar.mucError(MucOptions.Error.KICKED, receivedMessages = false),
        )
    }

    /** Shutdown and destroyed are different fates with different words and different offers. */
    @Test
    fun shutdownRetriesAndDestructionLeaves() {
        Assert.assertEquals(
            UiBar(R.string.conference_shutdown, BarVerb.TRY_AGAIN),
            ConversationBar.mucError(MucOptions.Error.SHUTDOWN, receivedMessages = false),
        )
        Assert.assertEquals(
            UiBar(R.string.conference_destroyed, BarVerb.LEAVE),
            ConversationBar.mucError(MucOptions.Error.DESTROYED, receivedMessages = false),
        )
    }

    /**
     * A server that timed out is worth another try only where there is history to come back to; an
     * empty room is only worth leaving. The same branch serves "remote server not found", and the
     * timeout's words are the island's own name for the state.
     */
    @Test
    fun aRemoteServerAnswerDependsOnTheRoomsHistory() {
        Assert.assertEquals(
            UiBar(XmppR.string.remote_server_timeout, BarVerb.TRY_AGAIN),
            ConversationBar.mucError(MucOptions.Error.REMOTE_SERVER_TIMEOUT, receivedMessages = true),
        )
        Assert.assertEquals(
            UiBar(XmppR.string.remote_server_timeout, BarVerb.LEAVE),
            ConversationBar.mucError(MucOptions.Error.REMOTE_SERVER_TIMEOUT, receivedMessages = false),
        )
        Assert.assertEquals(
            UiBar(R.string.remote_server_not_found, BarVerb.TRY_AGAIN),
            ConversationBar.mucError(MucOptions.Error.SERVER_NOT_FOUND, receivedMessages = true),
        )
        Assert.assertEquals(
            UiBar(R.string.remote_server_not_found, BarVerb.LEAVE),
            ConversationBar.mucError(MucOptions.Error.SERVER_NOT_FOUND, receivedMessages = false),
        )
    }

    /**
     * The Java's own dead arm, preserved: `case INVALID_NICK:` had no `break`, so the shutdown line
     * overwrote the invalid-nick one and that is what the owner has always seen. A cell here means a
     * future repair is a deliberate change with a red test in front of it, not a silent re-wording.
     */
    @Test
    fun theInvalidNickArmAnswersWhatTheOwnerHasAlwaysSeen() {
        Assert.assertEquals(
            ConversationBar.mucError(MucOptions.Error.SHUTDOWN, receivedMessages = false),
            ConversationBar.mucError(MucOptions.Error.INVALID_NICK, receivedMessages = false),
        )
    }

    /** A sentence that is the whole answer draws no button, and every other verb names one. */
    @Test
    fun onlyTheNoneVerbCarriesNoLabel() {
        Assert.assertEquals(0, BarVerb.NONE.label)
        val labelless = BarVerb.entries.filter { it != BarVerb.NONE && it.label == 0 }
        Assert.assertEquals(emptyList<BarVerb>(), labelless)
    }

    /** The room's disclosure warning offers the accepted join, which is a different verb from join. */
    @Test
    fun thePublicRoomWarningOffersTheAcceptedJoin() {
        Assert.assertEquals(
            UiBar(R.string.group_chat_will_make_your_jabber_id_public, BarVerb.ACCEPT_JOIN),
            ConversationBar.mucError(MucOptions.Error.NON_ANONYMOUS, receivedMessages = false),
        )
    }
}
