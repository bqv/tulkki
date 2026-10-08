package uk.xa0.tulkki.app.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.preference.PreferenceManager
import android.util.Log
import com.google.common.base.Optional
import com.google.common.base.Strings
import com.google.common.collect.Iterables
import com.google.common.io.BaseEncoding
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import uk.xa0.tulkki.app.receiver.UnifiedPushDistributor
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.updb.UnifiedPushDatabase
import uk.xa0.tulkki.data.updb.UnifiedPushTransport
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Presence
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The UnifiedPush distributor's broker: renews endpoints, hands payloads to the apps that want them.
 *
 * <p>**All six `UnifiedPushPort` members take `override`** - the Java annotated only three of them,
 * but `renewUnifiedPushEndpointsOnBind`, `reconfigurePushDistributor` and `processPushMessage` are
 * port members too, and Kotlin requires the keyword for every one.
 *
 * <p>**Two casts back to this class's own types**, both the Java's own: the renewal parameter arrives
 * as the port's marker and is cast to `PushTargetMessenger?` (null survives the cast, which is why the
 * three call sites below still test it), and `rebroadcastEndpoint`'s transport is cast to `Transport`.
 * `pushTarget` builds the database's own `PushTarget` from the two strings the island hands over and
 * wraps it in the port's marker; since port-13's `PushTargetRef` deletion the island never names that
 * type at all.
 *
 * <p>**A Java field reached from the enclosing class.** `PushTargetMessenger.pushTarget` is `private`
 * in the Java and the *broker* reads it in `renewUnifiedEndpoint`, and Kotlin's `private` does not
 * reach the enclosing class - the `LanguageCheck.Report` / `EmojiSearch` treatment - so it is
 * `internal`. `messenger` stays public and nullable, because the Java wrote null into it and every
 * read tests it first.
 *
 * <p>`RENEWAL_INTERVAL` is a `const val` (the Java's `public static final long`, still a JVM
 * constant) and `SCHEDULER` a private companion val. The `IllegalArgumentException | ParseException`
 * multi-catch is the row's eighth split. Every place a `Jid` stood on the **left** of a `+` gains
 * `.toString()`: Kotlin has `String.plus(Any?)` only, and `Jid` is a `CharSequence`.
 *
 * <p>Name-string audit: 0 hits for `UnifiedPushBroker`, `Transport` or `PushTargetMessenger` in the
 * manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and the ProGuard rules. The one class this
 * file names twice, `UnifiedPushDistributor`, is deliberately two different classes - the `:ui` one
 * registered for the DISTRIBUTOR role and `:app`'s receiver - and both keep their FQCNs.
 */
class UnifiedPushBroker(private val service: XmppConnectionService) :
        uk.xa0.tulkki.xmpp.services.UnifiedPushPort {

    init {
        SCHEDULER.scheduleAtFixedRate(
                this::renewUnifiedPushEndpoints,
                RENEWAL_INTERVAL,
                RENEWAL_INTERVAL,
                TimeUnit.MILLISECONDS)
    }

    override fun renewUnifiedPushEndpointsOnBind(account: AccountRef) {
        val transportOptional = getTransport()
        if (transportOptional.isPresent) {
            val transport = transportOptional.get()
            val transportAccount = transport.account
            if (transportAccount != null && transportAccount.getUuid() == account.getUuid()) {
                val database = UnifiedPushDatabase.getInstance(service)
                if (database.hasEndpoints(transport)) {
                    sendDirectedPresence(transportAccount, transport.transport)
                }
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString() +
                                ": trigger endpoint renewal on bind")
                renewUnifiedEndpoint(transportOptional.get(), null)
            }
        }
    }

    private fun sendDirectedPresence(account: Account, to: Jid) {
        val presence = Presence()
        presence.setTo(to)
        service.sendPresencePacket(account, presence)
    }

    private fun renewUnifiedPushEndpoints() {
        renewUnifiedPushEndpoints(null)
    }

    /**
     * 3.7 pair 4: the island's form of the renewal.
     *
     * <p>The parameter's static type is the port's marker rather than `PushTargetMessenger`, because
     * the island builds the object through [pushTarget] and only ever hands it back; one cast here is
     * the whole of the cost, and it is checked rather than silent.
     */
    override fun renewUnifiedPushEndpoints(
            pushTarget: uk.xa0.tulkki.xmpp.services.UnifiedPushPort.PushTarget?
    ): Optional<uk.xa0.tulkki.xmpp.services.UnifiedPushPort.Transport> {
        val pushTargetMessenger = pushTarget as PushTargetMessenger?
        val transportOptional = getTransport()
        if (transportOptional.isPresent) {
            val transport = transportOptional.get()
            if (transport.account.isEnabled()) {
                renewUnifiedEndpoint(transportOptional.get(), pushTargetMessenger)
            } else {
                if (pushTargetMessenger != null && pushTargetMessenger.messenger != null) {
                    sendRegistrationDelayed(pushTargetMessenger.messenger!!, "account is disabled")
                }
                Log.d(Config.LOGTAG, "skipping UnifiedPush endpoint renewal. Account is disabled")
            }
        } else {
            if (pushTargetMessenger != null && pushTargetMessenger.messenger != null) {
                sendRegistrationDelayed(pushTargetMessenger.messenger!!, "no transport selected")
            }
            Log.d(Config.LOGTAG, "skipping UnifiedPush endpoint renewal. No transport selected")
        }
        // The renewal's own type is this class's; the port promises only the marker, so the result
        // is widened here rather than the whole method being retyped.
        if (!transportOptional.isPresent) {
            return Optional.absent()
        }
        val renewed: uk.xa0.tulkki.xmpp.services.UnifiedPushPort.Transport = transportOptional.get()
        return Optional.of(renewed)
    }

    private fun sendRegistrationDelayed(messenger: Messenger, error: String) {
        val intent = Intent(UnifiedPushDistributor.ACTION_REGISTRATION_DELAYED)
        intent.putExtra(UnifiedPushDistributor.EXTRA_MESSAGE, error)
        val message = Message()
        message.obj = intent
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            Log.d(Config.LOGTAG, "unable to tell messenger of delayed registration", e)
        }
    }

    private fun renewUnifiedEndpoint(
            transport: Transport,
            pushTargetMessenger: PushTargetMessenger?
    ) {
        val account = transport.account
        // port-5: `AbstractEntity.getUuid()` is nullable; the push registry is keyed by account uuid
        // and an account with none has no renewals to look up.
        val accountUuid = account.getUuid() ?: return
        val unifiedPushDatabase = UnifiedPushDatabase.getInstance(service)
        val renewals =
                unifiedPushDatabase.getRenewals(
                        accountUuid, transport.transport.toString())
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                        ": " +
                        renewals.size +
                        " UnifiedPush endpoints scheduled for renewal on " +
                        transport.transport)
        for (renewal in renewals) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                            ": try to renew UnifiedPush " +
                            renewal)
            UnifiedPushDistributor.quickLog(
                    service,
                    String.format(
                            "%s: try to renew UnifiedPush %s",
                            account.getJid(),
                            renewal.toString()))
            val hashedApplication =
                    UnifiedPushDistributor.hash(accountUuid, renewal.application)
            val hashedInstance =
                    UnifiedPushDistributor.hash(accountUuid, renewal.instance)
            val registration = Iq(Iq.Type.SET)
            registration.setTo(transport.transport)
            val register = registration.addChild("register", Namespace.UNIFIED_PUSH)
            register.setAttribute("application", hashedApplication)
            register.setAttribute("instance", hashedInstance)
            val messenger: Messenger?
            if (pushTargetMessenger != null && renewal == pushTargetMessenger.pushTarget) {
                messenger = pushTargetMessenger.messenger
            } else {
                messenger = null
            }
            this.service.sendIqPacket(account, registration) { response ->
                processRegistration(transport, renewal, messenger, response)
            }
        }
    }

    private fun processRegistration(
            transport: Transport,
            renewal: UnifiedPushDatabase.PushTarget,
            messenger: Messenger?,
            response: Iq
    ) {
        if (response.getType() == Iq.Type.RESULT) {
            val registered = response.findChild("registered", Namespace.UNIFIED_PUSH)
            if (registered == null) {
                return
            }
            val endpoint = registered.getAttribute("endpoint")
            if (endpoint.isNullOrEmpty()) {
                Log.w(Config.LOGTAG, "endpoint was null in up registration")
                return
            }
            val expiration: Long
            try {
                expiration = AbstractParser.getTimestamp(registered.getAttribute("expiration"))
            } catch (e: IllegalArgumentException) {
                Log.d(Config.LOGTAG, "could not parse expiration", e)
                return
            } catch (e: ParseException) {
                Log.d(Config.LOGTAG, "could not parse expiration", e)
                return
            }
            renewUnifiedPushEndpoint(transport, renewal, messenger, endpoint, expiration)
        } else {
            Log.d(Config.LOGTAG, "could not register UP endpoint " + response.getErrorCondition())
        }
    }

    private fun renewUnifiedPushEndpoint(
            transport: Transport,
            renewal: UnifiedPushDatabase.PushTarget,
            messenger: Messenger?,
            endpoint: String,
            expiration: Long
    ) {
        Log.d(Config.LOGTAG, "registered endpoint " + endpoint + " expiration=" + expiration)
        // port-5: `AbstractEntity.getUuid()` is nullable; the endpoint row is keyed by account uuid
        // and an account with none has no row to update.
        val accountUuid = transport.account.getUuid() ?: return
        val unifiedPushDatabase = UnifiedPushDatabase.getInstance(service)
        val modified =
                unifiedPushDatabase.updateEndpoint(
                        renewal.instance,
                        accountUuid,
                        transport.transport.toString(),
                        endpoint,
                        expiration)
        if (modified) {
            Log.d(
                    Config.LOGTAG,
                    "endpoint for " +
                            renewal.application +
                            "/" +
                            renewal.instance +
                            " was updated to " +
                            endpoint)
            UnifiedPushDistributor.quickLog(
                    service,
                    "endpoint for " +
                            renewal.application +
                            "/" +
                            renewal.instance +
                            " was updated to " +
                            endpoint)
            val applicationEndpoint =
                    UnifiedPushDatabase.ApplicationEndpoint(renewal.application, endpoint)
            sendEndpoint(messenger, renewal.instance, applicationEndpoint)
        }
    }

    private fun sendEndpoint(
            messenger: Messenger?,
            instance: String,
            applicationEndpoint: UnifiedPushDatabase.ApplicationEndpoint
    ) {
        if (messenger != null) {
            Log.d(
                    Config.LOGTAG,
                    "using messenger instead of broadcast to communicate endpoint to " +
                            applicationEndpoint.application)
            val message = Message()
            message.obj = endpointIntent(instance, applicationEndpoint)
            try {
                messenger.send(message)
            } catch (e: RemoteException) {
                Log.d(Config.LOGTAG, "messenger failed. falling back to broadcast")
                broadcastEndpoint(instance, applicationEndpoint)
            }
        } else {
            broadcastEndpoint(instance, applicationEndpoint)
        }
    }

    override fun reconfigurePushDistributor(): Boolean {
        val enabled = getTransport().isPresent
        setUnifiedPushDistributorEnabled(enabled)
        if (!enabled) {
            unregisterCurrentPushTargets()
        }
        return enabled
    }

    private fun setUnifiedPushDistributorEnabled(enabled: Boolean) {
        val packageManager = service.getPackageManager()
        val componentNames =
                Arrays.asList(
                        ComponentName(
                                service.getApplicationContext(),
                                uk.xa0.tulkki.ui.UnifiedPushDistributor::class.java),
                        ComponentName(
                                service.getApplicationContext(),
                                UnifiedPushDistributor::class.java))
        if (enabled) {
            for (componentName in componentNames) {
                packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP)
            }
            Log.d(Config.LOGTAG, "UnifiedPushDistributor has been enabled")
        } else {
            for (componentName in componentNames) {
                packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
            }
            Log.d(Config.LOGTAG, "UnifiedPushDistributor has been disabled")
        }
    }

    private fun unregisterCurrentPushTargets() {
        val future = deletePushTargets()
        Futures.addCallback(
                future,
                object : FutureCallback<List<UnifiedPushDatabase.PushTarget>> {
                    override fun onSuccess(pushTargets: List<UnifiedPushDatabase.PushTarget>) {
                        broadcastUnregistered(pushTargets)
                    }

                    override fun onFailure(throwable: Throwable) {
                        Log.d(
                                Config.LOGTAG,
                                "could not delete endpoints after UnifiedPushDistributor was"
                                        + " disabled")
                    }
                },
                MoreExecutors.directExecutor())
    }

    private fun deletePushTargets(): ListenableFuture<List<UnifiedPushDatabase.PushTarget>> {
        return Futures.submit(
                Callable { UnifiedPushDatabase.getInstance(service).deletePushTargets() },
                SCHEDULER)
    }

    private fun broadcastUnregistered(pushTargets: List<UnifiedPushDatabase.PushTarget>) {
        for (pushTarget in pushTargets) {
            Log.d(Config.LOGTAG, "sending unregistered to " + pushTarget)
            broadcastUnregistered(pushTarget)
        }
    }

    private fun broadcastUnregistered(pushTarget: UnifiedPushDatabase.PushTarget) {
        val intent = unregisteredIntent(pushTarget)
        service.sendBroadcast(intent)
    }

    override fun processPushMessage(
            account: AccountRef,
            transport: Jid,
            push: Element
    ): Boolean {
        val instance = push.getAttribute("instance")
        val application = push.getAttribute("application")
        if (Strings.isNullOrEmpty(instance) || Strings.isNullOrEmpty(application)) {
            return false
        }
        val content = push.getContent()
        val payload: ByteArray
        if (Strings.isNullOrEmpty(content)) {
            payload = ByteArray(0)
        } else if (BaseEncoding.base64().canDecode(content)) {
            payload = BaseEncoding.base64().decode(content)
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                            ": received invalid unified push payload")
            return false
        }
        val pushTarget = getPushTarget(account, transport, application, instance)
        if (pushTarget.isPresent) {
            val target = pushTarget.get()
            // TODO check if app is still installed?
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                            ": broadcasting a " +
                            payload.size +
                            " bytes push message to " +
                            target.application)
            broadcastPushMessage(target, payload)
            return true
        } else {
            Log.d(Config.LOGTAG, "could not find application for push")
            return false
        }
    }

    fun getTransport(): Optional<Transport> {
        val sharedPreferences: SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(service.getApplicationContext())
        val accountPreference =
                sharedPreferences.getString(UnifiedPushDistributor.PREFERENCE_ACCOUNT, "none")
        val pushServerPreference =
                sharedPreferences.getString(
                        UnifiedPushDistributor.PREFERENCE_PUSH_SERVER,
                        service.getString(R.string.default_push_server))
        if (Strings.isNullOrEmpty(accountPreference) ||
                "none".equals(accountPreference, ignoreCase = true) ||
                Strings.nullToEmpty(pushServerPreference).javaTrim().isEmpty()) {
            return Optional.absent()
        }
        val transport: Jid
        val jid: Jid
        try {
            transport = Jid.of(Strings.nullToEmpty(pushServerPreference).javaTrim())
            jid = Jid.of(Strings.nullToEmpty(accountPreference).javaTrim())
        } catch (e: IllegalArgumentException) {
            return Optional.absent()
        }
        val account = AccountRegistry.get().findAccountByJid(jid)
        if (account == null) {
            return Optional.absent()
        }
        return Optional.of(Transport(account, transport))
    }

    private fun getPushTarget(
            account: AccountRef,
            transport: Jid?,
            application: String?,
            instance: String?
    ): Optional<UnifiedPushDatabase.PushTarget> {
        if (transport == null || application == null || instance == null) {
            return Optional.absent()
        }
        val uuid = account.getUuid()
        val pushTargets =
                UnifiedPushDatabase.getInstance(service)
                        .getPushTargets(uuid, transport.toString())
        return Iterables.tryFind(pushTargets) { pt ->
            UnifiedPushDistributor.hash(uuid, pt.application) == application &&
                    UnifiedPushDistributor.hash(uuid, pt.instance) == instance
        }
    }

    private fun broadcastPushMessage(
            target: UnifiedPushDatabase.PushTarget,
            payload: ByteArray
    ) {
        val updateIntent = Intent(UnifiedPushDistributor.ACTION_MESSAGE)
        updateIntent.setPackage(target.application)
        updateIntent.putExtra("token", target.instance)
        updateIntent.putExtra("bytesMessage", payload)
        updateIntent.putExtra("message", String(payload, StandardCharsets.UTF_8))
        val distributorVerificationIntent = Intent()
        distributorVerificationIntent.setPackage(service.getPackageName())
        val pendingIntent =
                PendingIntent.getBroadcast(
                        service, 0, distributorVerificationIntent, PendingIntent.FLAG_IMMUTABLE)
        updateIntent.putExtra("distributor", pendingIntent)
        service.sendBroadcast(updateIntent)
    }

    private fun broadcastEndpoint(
            instance: String,
            endpoint: UnifiedPushDatabase.ApplicationEndpoint
    ) {
        Log.d(Config.LOGTAG, "broadcasting endpoint to " + endpoint.application)
        val updateIntent = endpointIntent(instance, endpoint)
        service.sendBroadcast(updateIntent)
    }

    private fun endpointIntent(
            instance: String,
            endpoint: UnifiedPushDatabase.ApplicationEndpoint
    ): Intent {
        val intent = Intent(UnifiedPushDistributor.ACTION_NEW_ENDPOINT)
        intent.setPackage(endpoint.application)
        intent.putExtra("token", instance)
        intent.putExtra("endpoint", endpoint.endpoint)
        val distributorVerificationIntent = Intent()
        distributorVerificationIntent.setPackage(service.getPackageName())
        val pendingIntent =
                PendingIntent.getBroadcast(
                        service, 0, distributorVerificationIntent, PendingIntent.FLAG_IMMUTABLE)
        intent.putExtra("distributor", pendingIntent)
        return intent
    }

    private fun unregisteredIntent(pushTarget: UnifiedPushDatabase.PushTarget): Intent {
        val intent = Intent(UnifiedPushDistributor.ACTION_UNREGISTERED)
        intent.setPackage(pushTarget.application)
        intent.putExtra("token", pushTarget.instance)
        val distributorVerificationIntent = Intent()
        distributorVerificationIntent.setPackage(service.getPackageName())
        val pendingIntent =
                PendingIntent.getBroadcast(
                        service, 0, distributorVerificationIntent, PendingIntent.FLAG_IMMUTABLE)
        intent.putExtra("distributor", pendingIntent)
        return intent
    }

    override fun rebroadcastEndpoint(
            messenger: Messenger?,
            instance: String,
            transport: uk.xa0.tulkki.xmpp.services.UnifiedPushPort.Transport
    ) {
        // The marker carries nothing; the value behind it is this class's own type.
        val value = transport as Transport
        val unifiedPushDatabase = UnifiedPushDatabase.getInstance(service)
        // port-5: `AbstractEntity.getUuid()` is nullable; the endpoint row is keyed by account uuid
        // and an account with none has no endpoint to read.
        val accountUuid = value.account.getUuid() ?: return
        val endpoint =
                unifiedPushDatabase.getEndpoint(
                        accountUuid, value.transport.toString(), instance)
        if (endpoint != null) {
            sendEndpoint(messenger, instance, endpoint)
        }
    }

    /**
     * 3.7 pair 4: the object the island holds while a renewal is in flight.
     *
     * <p>It is built here from the two strings the island carries, handed to the island as the port's
     * marker, and handed back unchanged - the island never reads a field of it, so the marker is the
     * whole contract. This class is the one module that may name both ends, so the value type is
     * constructed here and no cast is owed.
     */
    override fun pushTarget(
            application: String,
            instance: String,
            messenger: Messenger
    ): uk.xa0.tulkki.xmpp.services.UnifiedPushPort.PushTarget {
        return PushTargetMessenger(UnifiedPushDatabase.PushTarget(application, instance), messenger)
    }

    /**
     * 3.7 pair 2 moved the value type down to `uk.xa0.tulkki.data.updb.UnifiedPushTransport`, because
     * `UnifiedPushDatabase` takes one as a parameter. This subclass is kept as the name `:xmpp`'s
     * `XmppConnectionService` already uses in three places, so the island does not have to move for a
     * data class - `UnifiedPushDatabase.hasEndpoints` accepts the supertype, and the broker's own
     * `Optional<Transport>` still means what it said. 3.7 pair 4 makes it the island port's marker
     * too: the island holds the value but never reads it.
     */
    class Transport(account: Account, transport: Jid) :
            UnifiedPushTransport(account, transport),
            uk.xa0.tulkki.xmpp.services.UnifiedPushPort.Transport

    class PushTargetMessenger(
            internal val pushTarget: UnifiedPushDatabase.PushTarget,
            val messenger: Messenger?
    ) : uk.xa0.tulkki.xmpp.services.UnifiedPushPort.PushTarget

    companion object {

        // interval for the 'cron tob' that attempts renewals for everything that expires is lass than
        // `uk.xa0.tulkki.data.updb.UnifiedPushDatabase.TIME_TO_RENEW`, which 3.7 pair 2 moved to the
        // database that owns the row's lifetime
        const val RENEWAL_INTERVAL = 3_600_000L

        private val SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    }
}

// Java's `String.trim()`: only the characters up to U+0020. Kotlin's `trim()` is the Unicode set and
// would also strip a non-breaking space (U+00A0) from a stored push server or account preference,
// which the Java's three reads above never did.
private fun String.javaTrim(): String = trim { it <= ' ' }
