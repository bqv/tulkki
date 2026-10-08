package uk.xa0.tulkki.ui.conversation

import androidx.annotation.StringRes
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.R as XmppR

/**
 * What the resting bar's action button *does*, as a noun the host answers to.
 *
 * <p>It is a verb and not a listener for `ConversationNotice.NoticeAction`'s reason: this decision is
 * pure and a JVM cell must be able to pin it, while the listeners are the fragment's - each one opens
 * a dialog, sends a presence or re-joins a room. Two verbs may share a label and never share a
 * listener: [JOIN] and [ACCEPT_JOIN] both read "Join", and only one of them is the room's ordinary
 * join.
 *
 * <p>[label] is the string's own, as [NoticeAction] does it. [NONE] carries `0`, which is the
 * "no action button" the bar already draws for a sentence that is the whole answer.
 */
enum class BarVerb(@StringRes val label: Int) {
    /** The sentence is the whole answer: no button is drawn. */
    NONE(0),

    /** The nick is taken or invalid: reopen the room's nick dialog. */
    EDIT_NICK(R.string.edit),

    /** Join the room again, as the room's own join. */
    JOIN(R.string.join),

    /** The `NON_ANONYMOUS` case: the room is public, so the join has to be accepted first. */
    ACCEPT_JOIN(R.string.join),

    /** Leave the room. */
    LEAVE(R.string.leave),

    /** Ask the room for its password and join with it. */
    ENTER_PASSWORD(R.string.enter_password),

    /** Try the join once more: a timeout or a transient room problem. */
    TRY_AGAIN(R.string.try_again),
}

/**
 * One line of the conversation's resting bar: the sentence, and the verb its action button carries.
 *
 * <p>It is `ui/ConversationFragment.java`'s `updateSnackBar(Conversation)` as a value. The fragment
 * still draws the bar - the Compose bar is step 6's - but it no longer decides what to say: the
 * branch, the words and the button are this type's, so every arm has a cell.
 *
 * @param words the sentence, from the strings the tree already shipped
 * @param verb what the action button does, [BarVerb.NONE] when the sentence is the whole answer
 */
data class UiBar(@StringRes val words: Int, val verb: BarVerb)

/**
 * The room's own answers to a join, as the bar says them.
 *
 * <p>**Every arm, and what it was.** `updateSnackBar`'s MUC switch was 15 reachable arms over
 * `MucOptions.Error`, and each one is recorded here because the wording is the whole of the surface:
 *
 * <ul>
 *   <li>`NO_RESPONSE` - the join was sent and the room has not answered yet. "Joining group chat…",
 *       no button: there is nothing to do but wait.
 *   <li>`SERVER_NOT_FOUND` - the room's server is not there. "Remote server not found", and the
 *       button depends on the room's history: a room the owner has read before gets "Try again", an
 *       empty one gets "Leave" (there is nothing to go back to).
 *   <li>`REMOTE_SERVER_TIMEOUT` - the same shape for a server that answered too slowly. Its words are
 *       the island's (`xmpp/R.string.remote_server_timeout`), because the wire layer named the state.
 *   <li>`NICK_IN_USE` - somebody holds the nick. "Nickname is already in use" with "Edit": the fix is
 *       a different nick, which is the room's nick dialog.
 *   <li>`PASSWORD_REQUIRED` - "Group chat requires password" with "Enter password".
 *   <li>`BANNED` - "You are banned from this group chat" with "Leave"; a banned occupant cannot
 *       re-join, so retrying is not an offer this bar can make.
 *   <li>`MEMBERS_ONLY` - "This group chat is members only" with "Leave".
 *   <li>`RESOURCE_CONSTRAINT` - the room is at its limit. "Resource constraint" with "Try again",
 *       because the limit may clear.
 *   <li>`KICKED` - "You have been kicked from this group chat" with "Join": a kick is not a ban, and
 *       the room's own join is the way back.
 *   <li>`TECHNICAL_PROBLEMS` - "You left this group chat due to technical reasons" with "Try again".
 *   <li>`UNKNOWN` - "You are no longer in this group chat" with "Try again".
 *   <li>`INVALID_NICK` - **the tree's own dead arm, preserved deliberately.** The Java wrote
 *       `case INVALID_NICK: showSnackbar(R.string.invalid_muc_nick, R.string.edit, clickToMuc);`
 *       **without a `break`**, so it fell through into `SHUTDOWN` and the shutdown line overwrote it:
 *       an invalid nick has always read "The group chat was shut down" with "Try again". This is
 *       recorded rather than repaired - the move's job is the move - so the arm answers exactly what
 *       the owner has always seen, and fixing it is a separate, deliberate change.
 *   <li>`SHUTDOWN` - "The group chat was shut down" with "Try again".
 *   <li>`DESTROYED` - "This group chat has been destroyed" with "Leave".
 *   <li>`NON_ANONYMOUS` - the room publishes the owner's address. "This channel will make your XMPP
 *       address public" with "Join", where the join is the *accepted* one ("[BarVerb.ACCEPT_JOIN]"):
 *       the tree gated it behind saying yes to the disclosure, and the two joins are different
 *       listeners.
 * </ul>
 *
 * <p>`NONE` and anything the tree did not name answer `null`, which is `hideSnackbar()` - the bar is
 * not drawn when the room has nothing to say.
 */
object ConversationBar {

    /**
     * The bar for a room whose join did not finish, or `null` when there is nothing to say.
     *
     * <p>The caller has already decided the room is one whose join did not complete (not online,
     * account online) - that gate stays in the fragment, because it is three reads of the live
     * conversation rather than a property of the error.
     *
     * @param error the room's own `MucOptions.Error`
     * @param receivedMessages whether this room has ever delivered a message here. It is the branch
     *     `SERVER_NOT_FOUND` and `REMOTE_SERVER_TIMEOUT` take: a room with history is worth retrying,
     *     an empty one only worth leaving.
     */
    @JvmStatic
    fun mucError(error: MucOptions.Error, receivedMessages: Boolean): UiBar? =
        when (error) {
            MucOptions.Error.NO_RESPONSE -> UiBar(R.string.joining_conference, BarVerb.NONE)
            MucOptions.Error.SERVER_NOT_FOUND ->
                retryOrLeave(R.string.remote_server_not_found, receivedMessages)
            MucOptions.Error.REMOTE_SERVER_TIMEOUT ->
                retryOrLeave(XmppR.string.remote_server_timeout, receivedMessages)
            MucOptions.Error.NICK_IN_USE -> UiBar(XmppR.string.nick_in_use, BarVerb.EDIT_NICK)
            MucOptions.Error.PASSWORD_REQUIRED ->
                UiBar(R.string.conference_requires_password, BarVerb.ENTER_PASSWORD)
            MucOptions.Error.BANNED -> UiBar(R.string.conference_banned, BarVerb.LEAVE)
            MucOptions.Error.MEMBERS_ONLY -> UiBar(R.string.conference_members_only, BarVerb.LEAVE)
            MucOptions.Error.RESOURCE_CONSTRAINT ->
                UiBar(R.string.conference_resource_constraint, BarVerb.TRY_AGAIN)
            MucOptions.Error.KICKED -> UiBar(R.string.conference_kicked, BarVerb.JOIN)
            MucOptions.Error.TECHNICAL_PROBLEMS ->
                UiBar(R.string.conference_technical_problems, BarVerb.TRY_AGAIN)
            MucOptions.Error.UNKNOWN -> UiBar(R.string.conference_unknown_error, BarVerb.TRY_AGAIN)
            // The tree's fall-through: INVALID_NICK showed SHUTDOWN's line. Kept, and recorded in
            // this object's own doc.
            MucOptions.Error.INVALID_NICK,
            MucOptions.Error.SHUTDOWN -> UiBar(R.string.conference_shutdown, BarVerb.TRY_AGAIN)
            MucOptions.Error.DESTROYED -> UiBar(R.string.conference_destroyed, BarVerb.LEAVE)
            MucOptions.Error.NON_ANONYMOUS ->
                UiBar(
                    R.string.group_chat_will_make_your_jabber_id_public,
                    BarVerb.ACCEPT_JOIN,
                )
            MucOptions.Error.NONE -> null
        }

    /** A room with history is worth another try; an empty one is only worth leaving. */
    private fun retryOrLeave(@StringRes words: Int, receivedMessages: Boolean): UiBar =
        UiBar(words, if (receivedMessages) BarVerb.TRY_AGAIN else BarVerb.LEAVE)
}
