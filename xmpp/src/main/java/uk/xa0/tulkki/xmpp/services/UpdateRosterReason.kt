package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: why the roster UI is being refreshed, un-nested out of `XmppConnectionService`
 * and converted from the Java.
 *
 * A Kotlin `enum class` is the same enum the Java was: the four constants keep their names and
 * order, so `UpdateRosterReason.PRESENCE` resolves identically from Java
 * (`XmppConnectionService.java:2894/2910`), from Kotlin (`RosterSync`, `UiUpdateDispatch`,
 * the three `:ui` screens) and from `:app`'s `ContactListSyncService`. `OnRosterUpdate`'s
 * `reason` parameter is this type and is non-null; the PRESENCE contract that a refresh with no
 * contact is a mistake lives on the caller, in `UiUpdateDispatch.updateRoster`, exactly as the
 * Java had it.
 */
enum class UpdateRosterReason {
    INIT,
    AVATAR,
    PUSH,
    PRESENCE,
}
