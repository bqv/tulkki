package uk.xa0.tulkki.data

import android.content.Context
import android.util.Pair
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.AccountRegistryRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import java.util.ArrayList

/**
 * Tulkki: 3.7 C5-E1 - the account registry, moved out of `XmppConnectionService`.
 *
 * Two things live here that used to live in the island. The first is the list itself: a `List<Account>`
 * field and a model-typed `getAccounts()` in `:xmpp` are a `:xmpp -> :data` import, and the island cannot
 * retype them - a model-typed return is the import - so the storage moves down and the island holds
 * [AccountRegistryRef] instead. The second is the three members whose *signature* is the model:
 * `findAccountByJid`, `findAccountByUuid` and `onboardingIncomplete`, which `:ui` binds to `Account`.
 *
 * **One list, two declared views.** This class overrides [AccountRegistryRef.getAccounts] with
 * `List<Account>` and [AccountRegistryRef.findAccountByJid] with `Account`, both covariant, so the
 * receiver's declared type alone decides what a caller gets: `:ui`/`:app` hold this class and the compiler
 * guarantees every element is an `Account`; `:xmpp` holds the ref and gets refs. There is no cast in
 * either direction and no second source of truth.
 *
 * **Why a static and not an installed port.** `DataStaticsHost` exists because its bodies live in `:app`
 * and the island may not name them, so something must be installed before the island asks. This
 * implementation is `:data`'s own and `DataStatics.accounts()` returns it - the same shape
 * `DataStaticsHost.INSTANCE` has, minus the installer that would carry no information here and would add a
 * "not installed yet" failure mode to `:ui`. The instance is created on first class use and lives for the
 * process, which is the lifetime `XmppConnectionService`'s list always had (it is reloaded per service
 * create, never re-created).
 *
 * Nothing here is serialised. `Account`'s own persistence is `DatabaseBackend.getAccounts()`, which builds
 * a fresh list; this holder is in-memory only and deliberately has no field a Gson or a `Cursor` could
 * write.
 */
class AccountRegistry private constructor() : AccountRegistryRef {

    /** The one list. Mutable on purpose: the island's `contains(null)`/`indexOf(null)` keep working. */
    private var accounts: MutableList<Account> = ArrayList()

    override fun getAccounts(): List<Account> = accounts

    override fun findAccountByJid(jid: Jid): Account? {
        for (account in accounts) {
            if (account.getJid().asBareJid() == jid.asBareJid()) {
                return account
            }
        }
        return null
    }

    override fun findAccountByUuid(uuid: String): Account? {
        for (account in accounts) {
            if (account.getUuid() == uuid) {
                return account
            }
        }
        return null
    }

    override fun create(jid: Jid, password: String): Account = Account(jid, password)

    override fun add(account: AccountRef) {
        accounts.add(account as Account)
    }

    override fun removeByUuid(uuid: String) {
        val iterator = accounts.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().getUuid() == uuid) {
                iterator.remove()
                return
            }
        }
    }

    /**
     * The database read, moved down out of `XmppConnectionService.initializeDatabaseInBackground`.
     * `DatabaseBackend.getAccounts()` already swallows its own failures and answers a fresh list, so this
     * inherits the old assignment's behaviour exactly - including that a wrong database key yields an empty
     * list rather than a throw.
     */
    @Throws(EncryptionException::class)
    override fun reload(context: Context) {
        accounts = ArrayList(DatabaseBackend.getInstance(context).getAccounts())
    }

    override fun clear() {
        accounts = ArrayList()
    }

    /**
     * Tulkki: C5-E1 - moved from `XmppConnectionService.onboardingIncomplete()`, body unchanged.
     *
     * It is here rather than on the ref because its return type is the model: the one caller is `:ui`'s
     * `ConversationListActivity`, which hands the pair to `UiHost.finishOnboarding(Activity, Account,
     * Account)`. A ref-typed pair would push the island type into two more `:ui` signatures for no gain.
     *
     * Tulkki: `Config.ONBOARDING_DOMAIN` is deleted, and that domain was the only thing that made an account
     * recognisable as the onboarding half - no option, key or stored flag marks one. The answer is therefore
     * "there is no such state", and the method stays for the caller that asks. Retiring the onboarding UI it
     * feeds is C5's full removal, which the owner did not choose.
     */
    fun onboardingIncomplete(): Pair<Account, Account>? = null

    companion object {

        private val INSTANCE = AccountRegistry()

        /** The process-wide holder. `:ui`/`:app` reach the model view through this. */
        @JvmStatic
        fun get(): AccountRegistry = INSTANCE
    }
}
