package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Roster`.
 *
 * Declared in the island, implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). Grown by ruling 3: three members, because three are what
 * the parser files' call sites read - `getContact`, `markAllAsNotInRoster` and `setVersion` - and
 * nothing else is here until a site wants it.
 */
interface RosterRef {

    fun getContact(jid: Jid): ContactRef

    fun markAllAsNotInRoster()

    fun setVersion(version: String?)

    // -- part 14: `injectServiceDiscoveryResult`, the island's first reader of a whole roster ------
    //
    // The island's one declared use of the *type* `Roster` is that private method's parameter (every
    // other mention is `account.getRoster().member(...)`, which needs no import). Retyping the
    // parameter is what makes the roster's own reads ref-shaped, so these two arrive with it, and
    // nothing else does.

    /**
     * Tulkki: return-position drag. The model answers `List<Contact>`, generics are invariant,
     * so the covariant return needs `List<? extends ContactRef>` - the same fact that shaped
     * `PresencesRef.getPresencesMap()` and `MucOptionsRef.getUsers(int)`. The island's
     * local therefore has the wildcard and its loop variable is a [ContactRef].
     */
    fun getContacts(): List<ContactRef>

    /**
     * Tulkki: the model's own accessor, made covariant by `Account implementing AccountRef`.
     * `injectServiceDiscoveryResult` hands the answer straight to `syncRoster(AccountRef)`, so no
     * cast is needed on either side.
     */
    fun getAccount(): AccountRef

    // -- C5-E2: the address-book half of the roster ---------------------------------------------------
    //
    // `XmppConnectionService.loadPhoneContacts` used to call `getWithSystemAccounts(JabberIdContact
    // .class)` - two `:data` names in an island file. The class literal is the trap: `Contact
    // .getOption(Class)` keys on the class object, so passing an island ref's own `.class` here would
    // compile and silently clear the wrong option bit. The island therefore asks a *named question*
    // and the model answers it with its own class literal (`Roster.getJabberIdSystemAccounts`), the
    // same device as `AccountRef.getPreAuthRegistrationToken`.
    //
    // `getWithSystemAccounts(Class)` itself stays on the model: `:app`'s `ContactListSyncService`
    // still calls it with `PhoneNumberContact.class`.

    /**
     * The roster's contacts synced from the address book as Jabber-id entries. A Java `List` return
     * is `MutableList` in Kotlin: the model answers a fresh `ArrayList` (filtered out of
     * `getContacts`'s own copy) and the island removes from it mid-pass (`RosterSync`), so the
     * read-only spelling was the narrowing.
     *
     * `MutableList` is invariant, so the model's own accessor answers `MutableList<ContactRef>` and
     * the unchecked cast that re-types its `MutableList<Contact>` lives in `Roster`, beside the class
     * literal - the return cannot stay covariant once it is mutable.
     */
    fun getJabberIdSystemAccounts(): MutableList<ContactRef>

    // -- C5-E1: the two members `XmppConnectionService`'s retyped bodies read ----------------------
    //
    // Both are the model's own declarations, so `Roster` satisfies them with no body. They land here
    // because the account those bodies hold is an `AccountRef` now, so `account.getRoster()` answers
    // this interface and not the model class.

    /** `Roster.clearPresences()`, read by `reconnectAccount`. */
    fun clearPresences()

    /** `Roster.getContactFromContactList(Jid)`, read by `findContacts`. */
    fun getContactFromContactList(jid: Jid?): ContactRef?
}
