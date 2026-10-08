package uk.xa0.tulkki.xmpp.services

import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.Pair
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnGatewayResult
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.refs.RosterRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.function.Consumer

/**
 * Tulkki: service discovery - the disco cache, the gateway and caps fetches, the caps injection,
 * the ad-hoc command query and the MAM preferences fetch - lifted out of `XmppConnectionService`
 *.
 *
 * The chunk owns the `discoCache` field, and it moves **whole**: nothing outside these methods
 * reads it, so the `LruCache` and its 20-entry bound live here now and the Java file keeps no slot.
 * Everything else travels through **public** service members - `getIqGenerator()`,
 * `getAvatarService()`, `getContactListSyncService()`, `sendIqPacket`, `syncRoster`,
 * `updateConversationUi`, the public `databaseBackend` slot and the public static `dataStatics()`.
 * `getPushManagementService`'s `require` guard stays the service's own shared helper, resolved on
 * the Java side, so the build-fault text is still written once; the Kotlin half only returns what
 * it is handed.
 *
 * `fetchCaps`'s `presence` is nullable exactly where the Java null-checked it, the cached-result
 * test keeps the Java's order, and `injectServiceDiscoveryResult` keeps the Java's eager `|=`
 * accumulation - the Kotlin `or` evaluates both sides, where `||` would skip the capability refresh.
 */
object ServiceDiscovery {

    private val discoCache = LruCache<Pair<String, String>, ServiceDiscoveryResultRef>(20)

    @JvmStatic
    fun publishDisplayName(service: XmppConnectionService, account: AccountRef) {
        val displayName = account.getDisplayName()
        val request: Iq =
            if (TextUtils.isEmpty(displayName)) {
                service.getIqGenerator().deleteNode(Namespace.NICK)
            } else {
                service.getIqGenerator()
                    .publishNick(displayName ?: throw NullPointerException("display name is empty"))
            }
        service.getAvatarService().clear(account)
        service.sendIqPacket(account, request) { packet ->
            if (packet.getType() == Iq.Type.ERROR) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": unable to modify nick name " +
                        packet,
                )
            }
        }
    }

    @JvmStatic
    fun getCachedServiceDiscoveryResult(
        service: XmppConnectionService,
        key: Pair<String, String>,
    ): ServiceDiscoveryResultRef? {
        var result: ServiceDiscoveryResultRef? = discoCache.get(key)
        if (result != null) {
            return result
        } else {
            val first = key.first
            val second = key.second
            if (first == null || second == null) return null
            if (!service.hasDatabaseBackend()) return null
            val backend = service.databaseBackend ?: return null
            result = backend.findDiscoveryResult(first, second)
            if (result != null) {
                discoCache.put(key, result)
            }
            return result
        }
    }

    @JvmStatic
    fun fetchFromGateway(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        input: String?,
        callback: OnGatewayResult,
    ) {
        val request = Iq(if (input == null) Iq.Type.GET else Iq.Type.SET)
        request.setTo(jid)
        val query = request.query("jabber:iq:gateway")
        if (input != null) {
            val prompt = query.addChild("prompt")
            prompt.setContent(input)
        }
        service.sendIqPacket(account, request) { packet ->
            if (packet.getType() == Iq.Type.RESULT) {
                callback.onGatewayResult(
                    packet.query().findChildContent(if (input == null) "prompt" else "jid"),
                    null,
                )
            } else {
                val error = packet.findChild("error")
                callback.onGatewayResult(
                    null,
                    if (error == null) null else error.findChildContent("text"),
                )
            }
        }
    }

    @JvmStatic
    fun fetchCaps(
        service: XmppConnectionService,
        accountRef: AccountRef,
        jid: Jid,
        presence: PresenceRef?,
    ) {
        fetchCaps(service, accountRef, jid, presence, null)
    }

    @JvmStatic
    fun fetchCaps(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        presence: PresenceRef?,
        cb: Runnable?,
    ) {
        // `getHash()`/`getVer()` are `String?` on the ref. A key with a null half was never
        // stored (`getCachedServiceDiscoveryResult` refuses one) and always answered null, so
        // spelling it as "no key" is the Java's own behaviour, not a narrowing.
        val capsHash = presence?.getHash()
        val capsVer = presence?.getVer()
        val key: Pair<String, String>? =
            if (capsHash == null || capsVer == null) null else Pair(capsHash, capsVer)
        val disco: ServiceDiscoveryResultRef? =
            if (key == null) null else getCachedServiceDiscoveryResult(service, key)

        if (disco != null && presence != null) {
            presence.setServiceDiscoveryResult(disco)
            val contact = account.getRoster().getContact(jid)
            if (contact.refreshRtpCapability()) {
                service.syncRoster(account)
            }
            contact.refreshCaps()
            if (disco.hasIdentity("gateway", "pstn")) {
                contact.registerAsPhoneAccount(service)
                service.getContactListSyncService().considerSyncBackground(false)
            }
            service.updateConversationUi(true)
        } else {
            val request = Iq(Iq.Type.GET)
            request.setTo(jid)
            val node = presence?.getNode()
            val ver = presence?.getVer()
            val query = request.query(Namespace.DISCO_INFO)
            if (node != null && ver != null) {
                query.setAttribute("node", node + "#" + ver)
            }

            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": making disco request for " +
                    key?.second +
                    " to " +
                    jid,
            )
            service.sendIqPacket(account, request) { response ->
                if (response.getType() == Iq.Type.RESULT) {
                    val discoveryResult: ServiceDiscoveryResultRef =
                        XmppConnectionService.dataStatics().newServiceDiscoveryResult(response)
                    if (presence == null ||
                        presence.getVer() == null ||
                        presence.getVer().equals(discoveryResult.getVer())
                    ) {
                        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).insertDiscoveryResult(discoveryResult)
                        injectServiceDiscoveryResult(
                            service,
                            account.getRoster(),
                            presence?.getHash(),
                            presence?.getVer(),
                            jid.getResource(),
                            discoveryResult,
                        )
                        if (discoveryResult.hasIdentity("gateway", "pstn")) {
                            val contact = account.getRoster().getContact(jid)
                            contact.registerAsPhoneAccount(service)
                            service.getContactListSyncService().considerSyncBackground(false)
                        }
                        service.updateConversationUi(true)
                        if (cb != null) cb.run()
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": mismatch in caps for contact " +
                                jid +
                                " " +
                                presence.getVer() +
                                " vs " +
                                discoveryResult.getVer(),
                        )
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString() +
                            ": unable to fetch caps from " +
                            jid,
                    )
                }
            }
        }
    }

    @JvmStatic
    fun fetchCommands(service: XmppConnectionService, account: AccountRef, jid: Jid, callback: Consumer<Iq>) {
        val request =
            service.getIqGenerator().queryDiscoItems(jid, "http://jabber.org/protocol/commands")
        service.sendIqPacket(account, request, callback)
    }

    @JvmStatic
    fun injectServiceDiscoveryResult(
        service: XmppConnectionService,
        roster: RosterRef,
        hash: String?,
        ver: String?,
        resource: String?,
        disco: ServiceDiscoveryResultRef,
    ) {
        var rosterNeedsSync = false
        for (contact in roster.getContacts()) {
            var serviceDiscoverySet = false
            val onePresence = contact.getPresences().get(resource ?: "")
            if (onePresence != null) {
                onePresence.setServiceDiscoveryResult(disco)
                serviceDiscoverySet = true
            } else if (resource == null && hash == null && ver == null) {
                val p = XmppConnectionService.dataStatics().newOfflinePresence()
                p.setServiceDiscoveryResult(disco)
                contact.updatePresence("", p)
                serviceDiscoverySet = true
            }
            if (hash != null && ver != null) {
                for (presence in contact.getPresences().getPresences()) {
                    if (hash.equals(presence.getHash()) && ver.equals(presence.getVer())) {
                        presence.setServiceDiscoveryResult(disco)
                        serviceDiscoverySet = true
                    }
                }
            }
            if (serviceDiscoverySet) {
                rosterNeedsSync = rosterNeedsSync or contact.refreshRtpCapability()
                contact.refreshCaps()
            }
        }
        if (rosterNeedsSync) {
            service.syncRoster(roster.getAccount())
        }
    }

    @JvmStatic
    fun fetchMamPreferences(
        service: XmppConnectionService,
        account: AccountRef,
        callback: OnMamPreferencesFetched,
    ) {
        val version = MessageArchiveService.Version.get(account)
        val request = Iq(Iq.Type.GET)
        request.addChild("prefs", version.namespace)
        service.sendIqPacket(account, request) { packet ->
            val prefs = packet.findChild("prefs", version.namespace)
            if (packet.getType() == Iq.Type.RESULT && prefs != null) {
                callback.onPreferencesFetched(prefs)
            } else {
                callback.onPreferencesFetchFailed()
            }
        }
    }

    @JvmStatic
    fun pushManagementService(
        port: PushManagementPort,
    ): PushManagementPort = port
}
