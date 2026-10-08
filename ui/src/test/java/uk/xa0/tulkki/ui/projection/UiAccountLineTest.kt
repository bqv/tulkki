package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test

/**
 * `ui-8`'s cell over the account line's rule: the owner's own preference, which is what the tree read.
 *
 * <p>The deleted `ConversationAdapter`, `CallsAdapter:138` and `ListItemAdapter:149` all asked
 * `getBooleanPreference("show_own_accounts", R.bool.show_own_accounts)` and nothing else, so the line exists
 * exactly while the owner asked for it. An earlier version ruled on "more than one account is connected",
 * which was the coordinator's heuristic rather than the tree's; a user setting wins over an invention.
 */
class UiAccountLineTest {

    @Test
    fun theOwnerPreferenceIsTheWholeRule() {
        Assert.assertEquals(
            "the owner asked for the line, so it is drawn",
            "mikko@example.org",
            UiAccountLine.of(true, "mikko@example.org"),
        )
        Assert.assertNull(
            "and not when they did not, however many accounts are connected",
            UiAccountLine.of(false, "mikko@example.org"),
        )
    }

    @Test
    fun anAddressNobodyCanReadIsNoLine() {
        Assert.assertNull("no address is no line, however the preference reads", UiAccountLine.of(true, null))
        Assert.assertNull("and a blank one is none either", UiAccountLine.of(true, "  "))
    }
}
