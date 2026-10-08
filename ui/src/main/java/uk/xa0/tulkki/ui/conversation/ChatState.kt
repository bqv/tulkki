package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.ui.conversationlist.UiConnection
import uk.xa0.tulkki.ui.projection.ConversationFacts
import uk.xa0.tulkki.ui.projection.MessageFacts
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.MessageProjection
import uk.xa0.tulkki.ui.projection.ProjectionSettings
import uk.xa0.tulkki.ui.projection.UiMessage

/**
 * The conversation view's state, `Design: the Compose UI` §3.6, and the one place
 * [MessageProjection]'s answer becomes what a screen draws.
 *
 * <p>**The rows are the projector's, and the two facts it may not decide are applied here.**
 * §2.2.1 #2 leaves `selected` to the screen - the projector answers `false` - so the assembly copies the
 * selection into the rows; `revealed` is the English-row exception's own set, and the projector is told it
 * rather than reading it. Both are *arguments of the assembly* and the set is carried here, because a
 * reveal is a screen-local change that must not re-read the file: the same snapshots re-project with one
 * more id in the set.
 *
 * <p>**The settings are an input and not a field, and that is deliberate.** §3.6's declaration gives this
 * state no switches, and a second copy of the five in the state is how a screen and a read come to disagree
 * about them: a changed switch is a new read (the list's own rule - §3.5's coalescing window is a `Flow`
 * operator, not a state), so the read resolves them once and hands them to [of]. What the *state* carries
 * is the picture that read produced plus the screen's own transient facts.
 *
 * <p>**`rows` is three-valued, like the list's.** `null` is "the first emission has not landed" and is the
 * only case that draws a loading shape; `emptyList()` is "the read landed and there is nothing", which is
 * §4.6's explainer; a list is the messages. An empty list before the first emission would tell the owner
 * the conversation is empty while the read is still in flight, which is the one thing that state must not
 * say.
 *
 * <p>§3.6's four remaining members are all here now: [composer] and [translationActivity] since slice
 * 2d, [connection] as the list's own `UiConnection` (because "not connected" is a fact of the account
 * and not of this screen), and [notices] since item 17's banner - which is what §3.6's notice bar
 * always was, and what this state deliberately did not guess at before the banner's rule existed
 * ([ConversationNotice]).
 */
data class ChatState(
    /** The rows, oldest first - or `null` before the first emission lands. */
    val rows: List<UiMessage>?,
    /** The rows whose English row the owner has unblurred in this process, by local uuid. */
    val revealed: Set<MessageId>,
    /** The rows the owner has selected, and the whole of §4.5's selection mode. */
    val selected: Set<MessageId>,
    /** Where the unread divider is drawn: the index of the first unread row, or `null` when none is. */
    val unreadAnchor: Int?,
    /** Whether the list is scrolled to its last row, which is §4.3's one no-anchor case. */
    val atBottom: Boolean,
    /** The composer's own state (§3.6's `composer`), empty when the host has none to draw. */
    val composer: UiComposer = UiComposer(),
    /** What the account is doing: the same three cases the list draws its status line from. */
    val connection: UiConnection = UiConnection.CONNECTED,
    /**
     * How many rows are being translated right now, from the queue. `0` draws no line at all, which is
     * the ordinary case; anything else says the screen is working, which §3.6's `translationActivity`
     * asks for and no other surface says.
     */
    val translationActivity: Int = 0,
    /**
     * §3.6's `notices`: the conversation-level banner's lines, from [ConversationNotice]. Empty is the
     * ordinary case - a conversation whose language is known and whose key is set says nothing - and a
     * list rather than one value because the member is plural in the design.
     */
    val notices: List<UiNotice> = emptyList(),
    /**
     * The rows whose **original** the owner has revealed in this process: item 17's decision five, the
     * second of the two concealment exceptions, and a set of its own rather than [revealed]'s - the
     * English row and the genuinely failed original are two exceptions, and one row may be either.
     * [MessageProjection] is handed it and answers `Visible(original)` for exactly those rows.
     */
    val revealedOriginals: Set<MessageId> = emptySet(),
) {

    /** Whether the first emission has landed. The loading shape is what is drawn while it has not. */
    val loading: Boolean
        get() = rows == null

    /** Whether the read landed and there is nothing to show: §4.6's explainer card. */
    val empty: Boolean
        get() = rows != null && rows.isEmpty()

    /** §4.5: selection mode is exactly "something is selected", never a mode of its own. */
    val selecting: Boolean
        get() = selected.isNotEmpty()

    /** One more revealed row, without touching the file: the read re-projects from its own snapshots. */
    fun revealed(id: MessageId): ChatState = copy(revealed = revealed + id)

    /**
     * The same for the failure gate's strip: one more row whose original the owner has opened. It is a
     * separate call because it is a separate exception - a tap on an English row's bar and a tap on a
     * failed original's strip are two different doors.
     */
    fun revealedOriginal(id: MessageId): ChatState = copy(revealedOriginals = revealedOriginals + id)

    /** One more selected row, or one fewer when it was already selected. */
    fun toggled(id: MessageId): ChatState =
        copy(selected = if (selected.contains(id)) selected - id else selected + id)

    /** Out of selection mode entirely, at a long press or a back gesture. */
    fun cleared(): ChatState = copy(selected = emptySet())

    companion object {

        /**
         * The assembly: one `Flow` emission's snapshots, the two seams' answers, and the settings in force.
         *
         * <p>It is a plain function rather than a `Flow` operator, because the read that owns the snapshots
         * is the host's - the same split `ConversationListHost` keeps ("**The read is not here.**") - and
         * because §4.3's anchoring needs the *previous* answer and the new one side by side.
         *
         * @param messages the conversation's rows as the file holds them, oldest first; `null` before the
         *     first emission
         * @param facts the row-level live facts, and `MessageFacts.NONE` in a cell
         * @param settings the five display switches, read by the host with the rows
         * @param conversation the facts every row of this conversation shares
         * @param revealed the rows whose English row the owner has unblurred
         * @param selected the rows the owner has selected
         * @param unreadCount how many of the newest rows are unread: the read's own count, which is what
         *     places the divider - the newest rows are the unread ones, never the oldest
         * @param composer the composer's own state, which is the host's to assemble (§3.6's `composer`)
         * @param connection what the account is doing, from the same read the list makes
         * @param translationActivity how many rows the queue is translating right now
         * @param notices the conversation-level banner's lines, from [ConversationNotice]
         * @param revealedOriginals the rows whose original the owner has revealed in this process
         */
        @JvmStatic
        fun of(
            messages: List<MessageSnapshot>?,
            facts: MessageFacts = MessageFacts.NONE,
            settings: ProjectionSettings = ProjectionSettings.SHIPPED,
            conversation: ConversationFacts,
            revealed: Set<MessageId> = emptySet(),
            selected: Set<MessageId> = emptySet(),
            unreadCount: Int = 0,
            atBottom: Boolean = true,
            composer: UiComposer = UiComposer(),
            connection: UiConnection = UiConnection.CONNECTED,
            translationActivity: Int = 0,
            notices: List<UiNotice> = emptyList(),
            revealedOriginals: Set<MessageId> = emptySet(),
        ): ChatState {
            val rows =
                messages?.let {
                    MessageProjection.of(it, facts, settings, conversation, revealed, revealedOriginals).map { row ->
                        if (selected.contains(row.id)) row.copy(selected = true) else row
                    }
                }
            return ChatState(
                rows = rows,
                revealed = revealed,
                selected = selected,
                // §4.2: the divider sits above the first unread row, and an unread count larger than the
                // list (a count from a read that has not landed yet) puts it at the top rather than off it.
                unreadAnchor = UnreadDivider.anchor(unreadCount, rows?.size ?: 0),
                atBottom = atBottom,
                composer = composer,
                connection = connection,
                translationActivity = translationActivity,
                notices = notices,
                revealedOriginals = revealedOriginals,
            )
        }
    }
}
