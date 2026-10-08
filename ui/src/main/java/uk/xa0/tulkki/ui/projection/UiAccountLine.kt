package uk.xa0.tulkki.ui.projection

/**
 * The account line a row may draw beneath its preview, and the one rule that decides whether it exists.
 *
 * <p>**The owner's preference decides, because that is the tree's rule.** The deleted
 * `ConversationAdapter` drew the line exactly while
 * `xmppConnectionService.getBooleanPreference("show_own_accounts", R.bool.show_own_accounts)` was
 * true, and the deleted `CallsAdapter:138` and `ListItemAdapter:149` read the same key the same way.
 * An earlier version ruled on "more than one account is connected" instead; that was the
 * coordinator's own heuristic and not the tree's, and a user setting wins over an invention. The host now
 * passes the preference and the conversation's own account address, and the line exists for both.
 *
 * <p>It is a pure function of the two facts so the rule has a cell.
 */
object UiAccountLine {

    /**
     * @param shown the owner's `show_own_accounts`.
     * @param account the conversation's own account address, or `null` when it cannot be read.
     */
    @JvmStatic
    fun of(shown: Boolean, account: String?): String? =
        if (shown && !account.isNullOrBlank()) account else null
}
