package uk.xa0.tulkki.xmpp.refs

import android.content.Context
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.EncryptionException

/**
 * Tulkki: 3.7 C5-E1 - the XMPP island's view of the account registry.
 *
 * The island used to hold the account list itself: `XmppConnectionService` declared
 * `private List<Account> accounts` and a model-typed `getAccounts()`, and that
 * declaration *was* the `:xmpp -> :data` import this slice exists to remove - a method whose
 * return type names the model cannot be retyped, only moved. So the list moves to
 * `uk.xa0.tulkki.data.AccountRegistry` and this interface is the island's half of it.
 *
 * What makes the move cast-free in both directions is one list behind covariant returns: the
 * model-typed holder answers `List<Account>` / `Account`, which are subtypes of the
 * declarations here, so `:ui`/`:app` keep binding the model with a compiler-guaranteed
 * element type and the island gets refs. The **receiver's declared type decides the view**; no cast
 * exists in either direction.
 *
 * Members arrive with their consumer (ruling 3). `create` constructs without adding, so the
 * caller's ordering - `initAccountServices`, the database write, the phone-account toggle,
 * then [add] - is byte for byte what it was; the alternative, a `create` that adds, would
 * have moved the account into the list one step earlier than the code it replaces.
 */
interface AccountRegistryRef {

    /**
     * The live list, not a copy: the island and the model holder are two views of the same object,
     * and the wildcard is what makes the model holder's covariant `List<Account>` an override.
     * A `List.of`/`List.copyOf` here would be a second source of truth *and* would start throwing on
     * `contains(null)`; `AccountRegistry` keeps the mutable `ArrayList` it always was.
     */
    fun getAccounts(): List<AccountRef>

    /** The model holder's own lookup, covariantly typed. Kept identical to the deleted one. */
    fun findAccountByJid(jid: Jid): AccountRef?

    /** The model holder's own lookup, covariantly typed. Kept identical to the deleted one. */
    fun findAccountByUuid(uuid: String): AccountRef?

    /**
     * The island's construction site: `new Account(jid, password)`, inside `:data` where the
     * class is reachable. **It does not add the account** - see the class comment.
     */
    fun create(jid: Jid, password: String): AccountRef

    /** Join the list. Called where the island's `this.accounts.add(account)` used to be. */
    fun add(account: AccountRef)

    /**
     * Leave the list, by identity of uuid rather than by the object: the island's one caller
     * (`deleteAccount`) holds the account it was handed and the list is the registry's.
     */
    fun removeByUuid(uuid: String)

    /**
     * Re-read the accounts from the database and replace the list - the island's own
     * `this.accounts = backend.getAccounts()` moved down. The database read stays inside
     * `:data`, where `DatabaseBackend` is a same-module name.
     */
    @Throws(EncryptionException::class)
    fun reload(context: Context)

    /**
     * Empty the list without re-reading: the two database-init failure paths, which used to assign a
     * fresh `ArrayList`.
     */
    fun clear()
}
