package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.utils.ReplacingSerialSingleThreadExecutor
import uk.xa0.tulkki.xmpp.utils.ReplacingTaskManager

/**
 * Tulkki: the phone-contact merge, the roster write-back and the live-list accessor, lifted out of
 * `XmppConnectionService`.
 *
 * Two per-service fields travel with the chunk because nothing outside it reads them — the
 * replacing contact-merger executor and the one-shot "address book synced" flag — and they live
 * here now; the service is a singleton, so the lifetimes are the same. The third field, the roster
 * sync task manager, **is** read outside (`deleteAccount` clears one account's queue), so it stays
 * on the service and is passed in by hand, exactly as chunk `C16` passed its executor.
 *
 * The merge's loop and its order are the Java's: the lookup, then per account the contact
 * replacement, the avatar-cache clean on a change, the removal from the system-account list, then
 * the leftovers unset; the shortcut refresh, the roster push and the sync consideration come last.
 * `getConversationList()` answers the live `CopyOnWriteArrayList` it is handed — not a copy — which
 * the row names as the contract.
 */
object RosterSync {

    private val contactMergerExecutor =
        ReplacingSerialSingleThreadExecutor("ContactMerger")

    private val initialAddressbookSyncCompleted = AtomicBoolean(false)

    @JvmStatic
    fun loadPhoneContacts(
        service: XmppConnectionService,
        shortcutService: ShortcutPort,
        contactListSync: ContactListSyncPort,
    ) {
        contactMergerExecutor.execute {
            val contacts = XmppConnectionService.dataStatics().loadJabberIdContacts(service)
            Log.d(Config.LOGTAG, "start merging phone contacts with roster")
            for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                // Tulkki: 3.7 C5-E2 - the named question replaces the class-keyed
                // `getWithSystemAccounts(JabberIdContact.class)`. The class literal stays in
                // `:data`: passing a ref's `.class` to that lookup would compile and clear the
                // wrong option bit. The local is still the wildcard `getContacts()` shaped.
                val withSystemAccounts =
                    account.getRoster().getJabberIdSystemAccounts()
                // Tulkki: 3.7 C5-E2 - port-13's `JabberIdRef` deletion: the merge reads the
                // entry's *key*, which the query built from the entry's own JID
                // (`JabberIdContact.load`), because the value is the family marker now.
                for (entry in contacts.entries) {
                    val contact = account.getRoster().getContact(entry.key)
                    val needsCacheClean = contact.setPhoneContact(entry.value)
                    if (needsCacheClean) {
                        service.getAvatarService().clear(contact)
                    }
                    withSystemAccounts.remove(contact)
                }
                for (contact in withSystemAccounts) {
                    val needsCacheClean = contact.unsetJabberIdPhoneContact()
                    if (needsCacheClean) {
                        service.getAvatarService().clear(contact)
                    }
                }
            }
            Log.d(Config.LOGTAG, "finished merging phone contacts")
            shortcutService.refresh(initialAddressbookSyncCompleted.compareAndSet(false, true))
            service.updateRosterUi(UpdateRosterReason.PUSH)
            contactListSync.considerSync()
        }
    }

    /**
     * Queues one roster write per account on [taskManager]; the deliberate 500 ms settle after the
     * write is the Java's, not a new one.
     */
    @JvmStatic
    fun syncRoster(
        service: XmppConnectionService,
        account: AccountRef,
        taskManager: ReplacingTaskManager,
    ) {
        taskManager.execute(account) {
            service.unregisterPhoneAccounts(account)
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).writeRoster(account.getRoster())
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
            }
        }
    }

    /** The live conversation list itself, handed straight back; never a copy. */
    @JvmStatic
    fun getConversationList(list: MutableList<ConversationRef>): MutableList<ConversationRef> = list
}
