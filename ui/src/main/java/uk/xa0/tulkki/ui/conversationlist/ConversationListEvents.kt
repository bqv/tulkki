package uk.xa0.tulkki.ui.conversationlist

/**
 * What the conversation list asks its host to do, so the screen emits and the host owns the effects.
 *
 * <p>It is an interface rather than a handful of lambdas for the reason `SettingsActions` is: the host is
 * one object - `ConversationListFragment` - and it already holds the service, the account and the
 * activity's four protocols (`OnConversationSelected`, `OnConversationArchived`,
 * `OnConversationListItemUpdated`, `OnConversationRead`). The screen then has one collaborator instead of
 * a parameter list that changes whenever a row gains an action.
 *
 * <p>**The host implements this in Java**, which is why the conversation is named by its local uuid - the
 * row's own identity, and the `LazyColumn` key - rather than by the `ConversationId` value class, whose
 * JVM shape mangles a method name for any Java caller. The value class stays the screen's own typing.
 */
interface ConversationListEvents {

    /** One of the three filters was chosen; §3.5's predicates, not a tab state machine. */
    fun onFilter(filter: ConversationFilter)

    /** A row was tapped: open that conversation. */
    fun onOpen(conversationUuid: String)

    /**
     * A row's action was chosen - from the long-press menu ([ConversationMenu.entries]) or from a swipe
     * ([ConversationMenu.archive]). The action is the one that will happen, so the host performs it and
     * does not re-read the row to decide what the row already decided.
     */
    fun onAction(action: ConversationAction, conversationUuid: String)
}
