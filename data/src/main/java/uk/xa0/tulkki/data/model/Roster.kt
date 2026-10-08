package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.crypto.OmemoRoster
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.RosterRef

/**
 * One account's contacts, keyed by bare JID, plus the roster version the server last named.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Eight members are `override`s because [OmemoRoster] and [RosterRef] declare them** - [getContact],
 *    [markAllAsNotInRoster], [setVersion], [getContacts], [getAccount], [getJabberIdSystemAccounts],
 *    [clearPresences] and [getContactFromContactList]. Java carried `@Override` on one of the eight;
 *    Kotlin's `override` is mandatory on every one. [getContact] satisfies *both* interfaces at once
 *    (`OmemoRoster` wants `OmemoContact`, `RosterRef` wants `ContactRef`) with the one covariant
 *    return `Contact`, which implements both - no bridge, no `@JvmName`.
 * 2. **`getContacts()`, `getWithSystemAccounts` and `getJabberIdSystemAccounts` all answer
 *    `MutableList<Contact>`.** Java's answer is the fresh `ArrayList` it copies out of the map;
 *    `getWithSystemAccounts` filters *that* list in place through `Iterator.remove()`, and the caller
 *    then removes rows from what it was handed (master's `ContactListSyncService`, Kotlin there).
 *    Kotlin's read-only `List` forbids that, so the honest type for a Java `List` the caller mutates
 *    is `MutableList`; `getJabberIdSystemAccounts` returns the same list object and says so too. It
 *    still satisfies the ref's `List<? extends ContactRef>` returns because Kotlin's `List` is
 *    covariant, and the JVM signature is `java.util.List<Contact>` either way (`javap` on the
 *    compiled class), so no Java caller changes.
 * 3. **`contacts` and `version` keep Java's private field names.** A private Kotlin `val`/`var`
 *    generates the backing field and **no** accessors - measured with `javap`, which is what
 *    `Presences` recorded and this file extends to `var` - so neither name can collide with the
 *    explicit `getAccount()`/`getVersion()`/`setVersion()`. The `HashMap` stays
 *    `java.util.HashMap`, the concrete type Java declared.
 * 4. **The nullability is Java's own.** `getContactFromContactList` and `initContact` take nullable
 *    parameters because Java null-checks both; `getContact(jid: Jid)` is non-null because Java
 *    dereferences it on the first line; `setVersion` is nullable because Java only stores it and the
 *    field starts `null`; `getWithSystemAccounts`'s class takes Java's
 *    `Class<? extends AbstractPhoneContact>` with no wildcard added and stays nullable because Java
 *    never checked it - `Contact.getOption` maps anything but `JabberIdContact.class`, `null`
 *    included, to `SYNCED_VIA_OTHER`.
 * 5. **`getContact` reads the map once where Java read it twice** (`containsKey` then `get`). The two
 *    forms are the same because the map never holds a `null` value, and the single read is what keeps
 *    the non-null return without a `!!`.
 *
 * No member is static and every field is private, so this file adds **zero** interop debt. Nothing
 * in the tree extends `Roster`, so Kotlin's implicit `final` is Java's own shape here.
 */
class Roster(
    private val account: Account,
) : OmemoRoster, RosterRef {

    private val contacts: HashMap<Jid, Contact> = HashMap()

    private var version: String? = null

    override fun getContactFromContactList(jid: Jid?): Contact? {
        val requested = jid ?: return null
        return synchronized(contacts) {
            val contact = contacts[requested.asBareJid()]
            if (contact != null && contact.showInContactList()) contact else null
        }
    }

    override fun getContact(jid: Jid): Contact =
        synchronized(contacts) {
            val bareJid = jid.asBareJid()
            val existing = contacts[bareJid]
            if (existing != null) {
                existing
            } else {
                val contact = Contact(bareJid)
                contact.setAccount(account)
                contacts[contact.getJid().asBareJid()] = contact
                contact
            }
        }

    override fun clearPresences() {
        for (contact in getContacts()) {
            contact.clearPresences()
        }
    }

    override fun markAllAsNotInRoster() {
        for (contact in getContacts()) {
            contact.resetOption(Contact.Options.IN_ROSTER)
        }
    }

    fun getWithSystemAccounts(clazz: Class<out AbstractPhoneContact>?): MutableList<Contact> {
        val option = Contact.getOption(clazz)
        val with = getContacts()
        val iterator = with.iterator()
        while (iterator.hasNext()) {
            val contact = iterator.next()
            if (!contact.getOption(option)) {
                iterator.remove()
            }
        }
        return with
    }

    /**
     * Tulkki: 3.7 C5-E2 - `RosterRef.getJabberIdSystemAccounts`, the named question the island asks
     * instead of naming `JabberIdContact.class`. The class literal stays here, in `:data`, where the
     * option bit it selects also lives; a ref's own `.class` passed to the class-keyed lookup would
     * compile and silently clear the wrong option bit.
     *
     * <p>The return is `MutableList<ContactRef>` because the interface's is mutable and
     * `MutableList` is invariant - the covariant `MutableList<Contact>` the model builds is not a
     * subtype of it. `getWithSystemAccounts` answers a **fresh** `ArrayList` (it copies
     * `getContacts()`), so the unchecked cast is the same list under a wider element type, and the
     * island's `RosterSync` removes from its own copy exactly as it did before.
     */
    @Suppress("UNCHECKED_CAST")
    override fun getJabberIdSystemAccounts(): MutableList<ContactRef> =
        getWithSystemAccounts(JabberIdContact::class.java) as MutableList<ContactRef>

    override fun getContacts(): MutableList<Contact> =
        synchronized(contacts) {
            ArrayList(contacts.values)
        }

    fun initContact(contact: Contact?) {
        val newContact = contact ?: return
        newContact.setAccount(account)
        synchronized(contacts) {
            contacts[newContact.getJid().asBareJid()] = newContact
        }
    }

    override fun setVersion(version: String?) {
        this.version = version
    }

    fun getVersion(): String? = version

    override fun getAccount(): Account = account
}
