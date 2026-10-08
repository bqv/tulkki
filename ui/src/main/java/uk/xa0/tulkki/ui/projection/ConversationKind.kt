package uk.xa0.tulkki.ui.projection

/**
 * What kind of conversation a row is, as `Design: the Compose UI` §3.5's list needs it: one value that
 * answers every place the tree asks "which kind is this", instead of a boolean per question.
 *
 * <p>The tree keeps three answers that are all derivable from the same two stored facts
 * (`ConversationSnapshot.mode`, and `members_only` + `non_anonymous` in its `attributes`):
 *
 * <ul>
 *   <li>the list's `Groups` filter, which is `mode == MODE_MULTI`;
 *   <li>`ConversationListFragment`'s long-press menu, which shows the contact's details for a one-to-one
 *       and the room's for a multi, and titles its archive entry by
 *       `MucOptions.isPrivateAndNonAnonymous()` — `action_muc_details` / `leave_group` on a private
 *       group, `channel_details` / `action_end_conversation_channel` on a channel (the Java
 *       `ui/src/main/java/uk/xa0/tulkki/ui/ConversationListFragment.java:403-411`, now the Kotlin
 *       `ConversationListFragment.kt` with `ConversationMenu.entries`/`label` in
 *       `conversationlist/ConversationAction.kt`);
 *   <li>and the same predicate again wherever a screen words a room.
 * </ul>
 *
 * <p>[GROUP] is the tree's "private and non-anonymous" multi — a room whose member list is closed and
 * whose occupants are not anonymous; [CHANNEL] is every other multi. One value, so the filter and the
 * menu cannot disagree about what a conversation is.
 */
enum class ConversationKind {
    /** `mode == MODE_SINGLE`. */
    ONE_TO_ONE,

    /** `MODE_MULTI` with both `members_only` and `non_anonymous` stored. */
    GROUP,

    /** `MODE_MULTI` without them: the tree's channel. */
    CHANNEL,
}
