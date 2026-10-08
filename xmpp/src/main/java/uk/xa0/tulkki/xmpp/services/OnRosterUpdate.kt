package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ContactRef

/**
 * Tulkki: the roster refresh, un-nested out of `XmppConnectionService`.
 *
 * Converted from the Java. `reason` is an enum and **non-null**. `contact` is **nullable**, and the
 * evidence is the caller's own contract rather than its text: the Kotlin home
 * `UiUpdateDispatch.updateRoster` (`UiUpdateDispatch.kt:76-89`) declares
 * `contact: ContactRef?` and hands it on unchanged - the only guard is
 * `reason == PRESENCE && contact == null -> throw`, so an `INIT`/`AVATAR`/`PUSH` refresh reaches
 * every listener with a **null** contact. `ConversationListActivity.kt:1768` already spells the
 * override `ContactRef?` for that reason. The other two implementers
 * (`ContactDetailsActivity.kt:258`, `StartConversationActivity.kt:255`) declared it non-null against
 * the Java platform type and are widened in the same commit, because a Kotlin override's parameter
 * type must match exactly (measured with kotlinc 2.3.21); neither reads the value.
 */
interface OnRosterUpdate {
    fun onRosterUpdate(reason: UpdateRosterReason, contact: ContactRef?)
}
