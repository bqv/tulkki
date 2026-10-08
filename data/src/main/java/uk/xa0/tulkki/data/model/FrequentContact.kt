package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Jid

/**
 * One row of `DatabaseBackend.getFrequentContacts`, moved down out of
 * `uk.xa0.tulkki.app.services.ShortcutService` by 3.7 pair 2.
 *
 * It is a value type: the conversation's uuid, the account's uuid, and the counterpart. The database
 * produces it and the shortcut publisher consumes it, so it belongs on the database's side of the
 * boundary; [uk.xa0.tulkki.data.DatabaseBackend] was naming `:app` only for this. The fields are
 * public where the nested class had them private - the two readers, the database itself and
 * `ShortcutService`, are no longer the same top-level class - and nothing else about it changed,
 * including the identity equality it never had.
 *
 * Ported from Java by the `port` stage (port-2), with two decisions recorded rather than inherited:
 *
 * 1. **It is a plain `class`, not a `data class`.** A Kotlin `data class` would synthesise `equals`
 *    and `hashCode`, and this type is a **map key** in `ShortcutService`
 *    (`ImmutableMap.Builder<FrequentContact, Contact>`, then `contactsChanged(contacts.values(), …)`).
 *    Java's `final class` had identity equality, so two rows with the same three values are two keys
 *    today; a `data class` would silently merge them. That is a behaviour change with no test to pin
 *    it, so the identity semantics are kept - the translation's job is to preserve them, not to
 *    improve them.
 * 2. **The three fields are `@JvmField`s**, because the remaining Java reader
 *    (`app/…/ShortcutService.java:89-100`: `frequentContact.account`, `entry.getKey().conversation`)
 *    reads them as **fields**, not through getters. Kotlin `val`s would generate
 *    `getConversation()`/`getAccount()`/`getContact()` and break that caller. That is three
 *    annotations of interop debt, recorded here and in the commit message; it goes to zero when
 *    `:app`'s `ShortcutService` is ported.
 */
class FrequentContact(
    @JvmField val conversation: String,
    @JvmField val account: String,
    @JvmField val contact: Jid,
)
