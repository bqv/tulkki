package uk.xa0.tulkki.ui.conversationlist

import uk.xa0.tulkki.ui.projection.UiConversation

/**
 * Which conversations the list is showing. §3.5: "the three chat-list filters are **cheap predicates
 * over `rows`**, not a tab state machine with per-state placeholders".
 */
enum class ConversationFilter {
    /** Every conversation the list is showing - archived ones only when they are asked for. */
    ALL,

    /** The conversations carrying something unread. */
    UNREAD,

    /** The group chats. The row's own flag is the snapshot's `mode` (`UiConversation.group`). */
    GROUPS,
}

/**
 * What the account is doing, which is a fact of its own and never an empty list.
 *
 * <p>§3.5 names a `connection` member and gives it no vocabulary, and the tree has none either - the
 * list's own snackbar is dead UI nothing drives - so the three cases are this row's to define. They are
 * the three a person can tell apart on this screen: connected, on the way, and not. §3.5's rule is what
 * they are for: "**error**: the connection state is a **banner or a status line**, never an empty list -
 * 'no conversations' and 'not connected' are different facts".
 */
enum class UiConnection {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
}

/**
 * The conversation list's state, `Design: the Compose UI` §3.5.
 *
 * <p>**`rows` is three-valued, and `null` is the first emission.** §3.5's declaration reads
 * `rows: List<UiConversation>` and its own states separate "empty" from "loading: the first emission; a
 * skeleton of three `ConversationRow`s, not a spinner, so the screen does not jump when rows land". An
 * empty list before the first emission would draw the explainer card - "no conversations" - while the
 * read is still in flight, which is the one thing that state must not say; so `null` is "not yet",
 * `emptyList()` is "the read landed and there are none", and a list is the rows. It is the same
 * three-valued reading `FailuresState.rows` makes, and for the same reason.
 *
 * <p>[filter], [connection] and [archivedVisible] are independent of it: a list with no rows while the
 * account is reconnecting is a status line, not an empty state, and a filter is a predicate rather than
 * a mode the screen switches into.
 *
 * <p>**The onboarding gate is deliberately not here.** §3.5: "no account at all is the onboarding gate,
 * derived from state on every resume and never from a persisted 'seen the tutorial' flag" - the fact is
 * the account list's, and it routes before this screen is built. A member for it would be a second
 * answer to a question the navigation layer already answers.
 *
 * <p>The list's other mechanic, §3.5's coalescing ("a rebuild at most every 2 s, with requests during a
 * rebuild deferred to the next window"), is a `Flow` operator on the rows this state is assembled from,
 * not a field of it.
 */
data class ConversationListState(
    /** The rows, newest first - or `null` before the first emission lands. */
    val rows: List<UiConversation>?,
    val filter: ConversationFilter,
    val connection: UiConnection,
    /** Whether the archived conversations are on screen. Off by default, and the outer gate for them. */
    val archivedVisible: Boolean,
) {

    /** Whether the first emission has landed. The skeleton is what is drawn while it has not. */
    val loading: Boolean
        get() = rows == null

    /**
     * The rows the screen draws: the filter's own predicate, over the rows as they arrived - the order
     * is the read's (`lastMessageAt`, newest first), and a filter never reorders.
     */
    val visible: List<UiConversation>
        get() = rows.orEmpty().filter { shown(it) }

    /** Whether the read landed and there is nothing to show: the explainer card's own state. */
    val empty: Boolean
        get() = rows != null && visible.isEmpty()

    /**
     * One row under the current filter. Archived rows are the outer gate in every filter: §3.5's
     * `archivedVisible` is not a fourth filter but the question of whether the third of the list is on
     * screen at all.
     */
    private fun shown(row: UiConversation): Boolean {
        if (row.archived && !archivedVisible) {
            return false
        }
        return when (filter) {
            ConversationFilter.ALL -> true
            // The badge, not the mute: a muted conversation the owner has not read is still unread.
            ConversationFilter.UNREAD -> row.unread > 0
            ConversationFilter.GROUPS -> row.group
        }
    }
}
