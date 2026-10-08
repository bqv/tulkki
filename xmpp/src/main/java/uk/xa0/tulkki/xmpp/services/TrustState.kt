package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.Executor
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the trust manager and the drawable cache, lifted out of `XmppConnectionService`
 *.
 *
 * The memorising trust manager is the one field nothing outside these three methods reads, so it
 * travels with the chunk and lives here now (the service is a singleton, so the lifetime is the
 * same); the drawable cache does **not** — it is built by `onCreate` and read by chunk `C58`'s
 * `evictPreview`, so it stays on the service and its getter hands the same instance back, **or the
 * null it held before `onCreate` assigned it** (see [drawableCache]). The database-writer executor
 * is private state of chunk `C02` and comes in by hand.
 *
 * `updateMemorizingTrustManager` reads chunk `C76`'s private decision-screen accessor and chunk
 * `C75`'s static trust port on the Java side of the seam, and `getApplicationContext()` is the
 * Java's own argument, not the service handed in.
 */
object TrustState {

    private var memorizingTrustManager: MemorizingTrustManager? = null

    @JvmStatic
    fun memorizingTrustManager(): MemorizingTrustManager? = memorizingTrustManager

    @JvmStatic
    fun setMemorizingTrustManager(trustManager: MemorizingTrustManager?) {
        memorizingTrustManager = trustManager
    }

    @JvmStatic
    fun updateMemorizingTrustManager(
        context: Context,
        trustPort: TrustPort,
        decisionScreen: MemorizingTrustManager.DecisionScreenPort,
        trustSystemCAStore: Boolean,
    ) {
        val trustManager =
            if (trustSystemCAStore) {
                MemorizingTrustManager(context, trustPort, decisionScreen)
            } else {
                MemorizingTrustManager(context, null, trustPort, decisionScreen)
            }
        setMemorizingTrustManager(trustManager)
    }

    @JvmStatic
    fun syncRosterToDisk(service: XmppConnectionService, account: AccountRef, executor: Executor) {
        val runnable = Runnable { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).writeRoster(account.getRoster()) }
        executor.execute(runnable)
    }

    /**
     * The service's drawable cache, or **`null`** before `onCreate` assigned the slot.
     *
     * Both the parameter and the answer are nullable because the Java's were:
     * `public LruCache<String, Drawable> getDrawableCache() { return this.mDrawableCache; }`
     * (`616b391df0`, `XmppConnectionService.java:7546-7548`), over a field that starts null. A
     * non-null Kotlin parameter is not a faithful port of that body: it adds a
     * `checkNotNullParameter` at entry, so a call before the assignment threw **inside this object**
     * where the Java handed the null back to the caller - the family `34cd97ca55` fixed on
     * `databaseBackend`. The service's own getter stays unannotated, so its Kotlin callers in
     * `:app` (`AvatarService`) and `:data` (`FileBackend`) still see the platform type the field
     * was, and `:ui`'s `ConversationFragment` is a Java caller with an unchanged Java signature -
     * no call site moves, in this module or outside it.
     */
    @JvmStatic
    fun drawableCache(cache: LruCache<String, Drawable>?): LruCache<String, Drawable>? = cache

    @JvmStatic
    fun knownHosts(): Collection<String> {
        val hosts = HashSet<String>()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            hosts.add(account.getServer())
            for (contact in account.getRoster().getContacts()) {
                if (contact.showInRoster()) {
                    val server = contact.getServer()
                    if (server != null) {
                        hosts.add(server)
                    }
                }
            }
        }
        val quicksyDomain = Config.QUICKSY_DOMAIN
        if (quicksyDomain != null) {
            hosts.remove(quicksyDomain.toString()) // we only want to show this when we type an e164
            // number
        }
        // Tulkki: upstream's two own servers were added here unconditionally. The list is the JID
        // field's autocomplete and the domains exempt from `suspiciousSubDomain`'s warning, so those
        // entries advertised upstream's servers to every user and bought them an exemption; both are
        // gone. The account's and the roster's own servers are the list.
        return hosts
    }

    @JvmStatic
    fun knownConferenceHosts(): Collection<String> {
        val mucServers = HashSet<String>()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            val connection = account.getXmppConnection()
            if (connection != null) {
                mucServers.addAll(connection.getMucServers())
                for (bookmark in account.getBookmarks()) {
                    val jid = bookmark.getJid()
                    val s = if (jid == null) null else jid.getDomain().toString()
                    if (s != null) {
                        mucServers.add(s)
                    }
                }
            }
        }
        return mucServers
    }
}
