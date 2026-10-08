package uk.xa0.tulkki.app.services

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.util.HashMap
import java.util.Objects
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import java.util.stream.Stream
import uk.xa0.tulkki.app.android.PhoneNumberContact
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.SerialSingleThreadExecutor

/**
 * The real contact-list sync: address-book rows become roster contacts, one gateway at a time.
 *
 * <p>**The three port predicates name the declaring class.** Java's `isQuicksy()` et al. were the
 * superclass's statics, inherited into scope; Kotlin does not inherit statics, so each forwarder
 * spells `AbstractContactListSyncService.` in front - the row's treatment of the same fact in
 * `UpdateNowPlayingService` and `PhoneHelper`. Every external caller keeps its own spelling, because
 * Java still inherits them through this class.
 *
 * <p>**`getNumber` is a private companion member.** It was `protected static`; a companion member
 * cannot be `protected` in Kotlin (BobTransfer's `attempts`), and nothing outside this file calls it -
 * `grep` finds one caller, `refresh` below. Everything else is the Java's shape: `ImmutableMap`/
 * `ImmutableList` as they were, `Objects.hash` over the same two values, the stream pipeline that
 * builds the gateway set, and `Attempt`'s retry window.
 *
 * <p>`Attempt.NULL` is `internal` rather than the Java's `private static final`, because Kotlin's
 * `private` does not reach the enclosing class (the `LanguageCheck.Report` / `EmojiSearch` treatment):
 * it is read from `considerSync` as the `getOrDefault` fallback and from nowhere else. The
 * `withSystemAccounts` is a mutable copy taken at the call site, because the model's
 * `getWithSystemAccounts` hands back a read-only `List`; rows are removed from the copy mid-pass.
 *
 * <p>Name-string audit: 0 hits for `ContactListSyncService` or `Attempt` in the manifest, `res/xml`,
 * `res/layout*`, `preferences_*.xml` and the ProGuard rules.
 */
class ContactListSyncService(xmppConnectionService: XmppConnectionService) :
        AbstractContactListSyncService(xmppConnectionService),
        uk.xa0.tulkki.xmpp.services.ContactListSyncPort {

    // -- 3.7 pair 4: the island's port --------------------------------------------------------------
    //
    // The three predicates are this class's statics (inherited from `AbstractContactListSyncService`)
    // spelled as instance methods so a port can carry them; the `:ui` and `:app` callers keep using
    // the statics unchanged. The other five members of the port are the methods below.

    override fun quicksy(): Boolean = AbstractContactListSyncService.isQuicksy()

    override fun playStoreFlavor(): Boolean = AbstractContactListSyncService.isPlayStoreFlavor()

    override fun contactListIntegration(context: Context): Boolean =
            AbstractContactListSyncService.isContactListIntegration(context)

    protected val mRunningSyncJobs = AtomicInteger(0)
    protected val mSerialSingleThreadExecutor =
            SerialSingleThreadExecutor(ContactListSyncService::class.java.simpleName)
    protected val mLastSyncAttempt: HashMap<String, Attempt> = HashMap()

    override fun considerSync() {
        considerSync(false)
    }

    override fun signalAccountStateChange() {}

    override fun isSynchronizing(): Boolean {
        return mRunningSyncJobs.get() > 0
    }

    override fun considerSyncBackground(force: Boolean) {
        mRunningSyncJobs.incrementAndGet()
        mSerialSingleThreadExecutor.execute {
            considerSync(force)
            if (mRunningSyncJobs.decrementAndGet() == 0) {
                service.updateRosterUi(uk.xa0.tulkki.xmpp.services.UpdateRosterReason.INIT)
            }
        }
    }

    override fun handleSmsReceived(intent: Intent?) {
        Log.d(Config.LOGTAG, "ignoring received SMS")
    }

    protected fun refresh(
            account: Account,
            gateways: Set<String>,
            phoneNumberContacts: Collection<PhoneNumberContact>
    ) {
        for (contact in account.getRoster().getWithSystemAccounts(PhoneNumberContact::class.java)) {
            val uri = contact.getSystemAccount()
            if (uri == null) {
                continue
            }
            val number = getNumber(gateways, contact)
            val phoneNumberContact =
                    PhoneNumberContact.findByUriOrNumber(phoneNumberContacts, uri, number)
            val needsCacheClean: Boolean
            if (phoneNumberContact != null) {
                if (uri != phoneNumberContact.getLookupUri()) {
                    Log.d(
                            Config.LOGTAG,
                            "lookupUri has changed from " +
                                    uri +
                                    " to " +
                                    phoneNumberContact.getLookupUri())
                }
                needsCacheClean = contact.setPhoneContact(phoneNumberContact)
            } else {
                needsCacheClean = contact.unsetPhoneContact(PhoneNumberContact::class.java)
                Log.d(Config.LOGTAG, uri.toString() + " vanished from address book")
            }
            if (needsCacheClean) {
                service.getAvatarService().clear(contact)
            }
        }
    }

    protected fun considerSync(forced: Boolean) {
        var allContacts: ImmutableMap<String, PhoneNumberContact>? = null
        for (account in ImmutableList.copyOf(AccountRegistry.get().getAccounts())) {
            val gateways = this.gateways(account)
            val contacts = allContacts ?: PhoneNumberContact.load(service)
            allContacts = contacts
            refresh(account, gateways, contacts.values)
            if (!considerSync(account, gateways, contacts, forced)) {
                service.syncRoster(account)
            }
        }
    }

    protected fun gateways(account: Account): Set<String> {
        return Stream.concat(
                        account.getGateways("pstn").stream(),
                        account.getGateways("sms").stream())
                .map { a -> a.getJid().asBareJid().toString() }
                .collect(Collectors.toSet())
    }

    protected fun considerSync(
            account: Account,
            gateways: Set<String>,
            contacts: Map<String, PhoneNumberContact>,
            forced: Boolean
    ): Boolean {
        val hash = Objects.hash(contacts.keys, gateways)
        // port-5: `AbstractEntity.getUuid()` is nullable; the attempt map is keyed by account uuid,
        // and an account with none has no identity to remember an attempt under, so none is made.
        val accountUuid = account.getUuid() ?: return false
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": consider sync of " + hash)
        if (!mLastSyncAttempt.getOrDefault(accountUuid, Attempt.NULL).retry(hash) && !forced) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": do not attempt sync")
            return false
        }
        mRunningSyncJobs.incrementAndGet()

        mLastSyncAttempt.put(accountUuid, Attempt.create(hash))
        val withSystemAccounts =
                account.getRoster().getWithSystemAccounts(PhoneNumberContact::class.java).toMutableList()
        for (item in contacts.entries) {
            val phoneContact = item.value
            for (gateway in gateways) {
                val jid = Jid.ofLocalAndDomain(phoneContact.getPhoneNumber(), gateway)
                val contact = account.getRoster().getContact(jid)
                var needsCacheClean = contact.setPhoneContact(phoneContact)
                needsCacheClean = needsCacheClean or contact.setSystemTags(phoneContact.getTags())
                if (needsCacheClean) {
                    service.getAvatarService().clear(contact)
                }
                withSystemAccounts.remove(contact)
            }
        }
        for (contact in withSystemAccounts) {
            val needsCacheClean = contact.unsetPhoneContact(PhoneNumberContact::class.java)
            if (needsCacheClean) {
                service.getAvatarService().clear(contact)
            }
        }

        mRunningSyncJobs.decrementAndGet()
        service.syncRoster(account)
        service.updateRosterUi(uk.xa0.tulkki.xmpp.services.UpdateRosterReason.INIT)
        return true
    }

    protected class Attempt private constructor(
            private val timestamp: Long,
            private val hash: Int
    ) {

        fun retry(hash: Int): Boolean {
            return hash != this.hash ||
                    SystemClock.elapsedRealtime() - timestamp >=
                            Config.CONTACT_SYNC_RETRY_INTERVAL
        }

        companion object {

            internal val NULL = Attempt(0, 0)

            fun create(hash: Int): Attempt = Attempt(SystemClock.elapsedRealtime(), hash)
        }
    }

    companion object {

        private fun getNumber(gateways: Set<String>, contact: Contact): String? {
            val jid = contact.getJid()
            // `Jid.getDomain()` answers a `Jid`, not a `String`, so the Java's
            // `"quicksy.im".equals(jid.getDomain())` and `gateways.contains(jid.getDomain())` were
            // both necessarily false and this method has always answered null. The port keeps the
            // Java's answer *and* its test, spelled so a `Jid` can still be handed to `equals`
            // (Kotlin's `Set<String>.contains` will not take one, so the set is asked with `any`).
            // Repairing it here would start returning local parts, which changes what `refresh`
            // stores for every address-book contact; that is a behaviour change and belongs in a
            // commit that takes it deliberately, with a test.
            if (jid.getLocal() != null &&
                    ("quicksy.im".equals(jid.getDomain()) ||
                            gateways.any { it.equals(jid.getDomain()) })) {
                return jid.getLocal()
            }
            return null
        }
    }
}
