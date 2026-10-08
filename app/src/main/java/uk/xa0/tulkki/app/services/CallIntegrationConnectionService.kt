package uk.xa0.tulkki.app.services

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.RtpSessionActivity
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.JingleRtpConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Registers the app as a self-managed dialler and turns Telecom's calls into Jingle sessions.
 *
 * <p>**The two Bundle keys stay the literals they are.** `EXTRA_SESSION_ID` and the private
 * `EXTRA_ADDRESS` are put into bundles handed to Telecom and read back in `createConnection` /
 * `onCallAudioStateChanged`, and Telecom keeps the bundle a call in flight was built with across a
 * package replace; renaming either makes the session lookup read null. `EXTRA_SESSION_ID` is public
 * because `ConnectionService` (Kotlin now) and `OsHeldNamesTest` both read the field, so it is a
 * `const val` - a static final field with the Java's name and the Java's value. Both spellings are on
 * `tools/verify-allowlist` and both are pinned by `OsHeldNamesTest`.
 *
 * <p>**`Arrays.asList("xmpp", "tel").contains(uri.getScheme())` has no Kotlin spelling.** Kotlin's
 * `List<String>.contains` will not take a `String?`, and `getScheme()` is nullable; the Java's
 * `contains` answered `false` for a null scheme, which is what `"xmpp" == scheme || "tel" == scheme`
 * answers. The null `uri` test stays in front of it, so a request without an address still fails
 * before the scheme is read.
 *
 * <p>**Three multi-catches become catches.** `IllegalArgumentException | SecurityException` twice
 * (the sixth and seventh of the row) and `ExecutionException | InterruptedException | TimeoutException`
 * once, each split into blocks with the Java's single body.
 *
 * <p>The eight `public static` members are `@JvmStatic` companion members - the seven of the outer
 * class and the two of `ServiceConnectionService`'s own, less those that are private - because Java
 * calls them (`UiAppHost.placeCall`, `XmppTulkkiHost`'s four) and because the rest are this public
 * class's public surface. `serviceFuture` is a nullable field the Java dereferenced in `onDestroy`
 * without a check, so that read keeps `!!`.
 *
 * <p>Name-string audit: 0 hits for `CallIntegrationConnectionService` or `ServiceConnectionService`
 * in the manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and the ProGuard rules, apart from
 * the two `eu.siacs.conversations.*` Bundle keys above, which are allowlisted and must not move.
 */
class CallIntegrationConnectionService : android.telecom.ConnectionService() {

    private var serviceFuture: ListenableFuture<ServiceConnectionService>? = null

    override fun onCreate() {
        Log.d(Config.LOGTAG, "CallIntegrationService.onCreate()")
        super.onCreate()
        this.serviceFuture = ServiceConnectionService.bindService(this)
    }

    override fun onDestroy() {
        Log.d(Config.LOGTAG, "destroying CallIntegrationConnectionService")
        super.onDestroy()
        val serviceConnection: ServiceConnection
        try {
            serviceConnection = serviceFuture!!.get().serviceConnection
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "could not fetch service connection", e)
            return
        }
        this.unbindService(serviceConnection)
    }

    override fun onCreateOutgoingConnection(
            phoneAccountHandle: PhoneAccountHandle,
            request: ConnectionRequest
    ): Connection {
        Log.d(Config.LOGTAG, "onCreateOutgoingConnection(" + request.getAddress() + ")")
        val uri = request.getAddress()
        val extras: Bundle = request.getExtras()
        if (uri == null) {
            return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "invalid address"))
        }
        if (!("xmpp" == uri.getScheme() || "tel" == uri.getScheme())) {
            return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "invalid address"))
        }
        val jid: Jid
        if ("tel" == uri.getScheme()) {
            // Tulkki: the platform marks `Bundle.getString` `@Nullable`; the Java handed the
            // platform-typed `String` straight to `Jid.of`, whose dereference is the failure. The
            // caller names that `NullPointerException` rather than widening the island's non-null
            // `CharSequence`.
            jid = Jid.of(extras.getString(EXTRA_ADDRESS) ?: throw NullPointerException())
        } else {
            jid = Jid.of(uri.getSchemeSpecificPart())
        }
        val videoState = extras.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE)
        val media: Set<Media> =
                if (videoState == VideoProfile.STATE_AUDIO_ONLY) ImmutableSet.of(Media.AUDIO)
                else ImmutableSet.of(Media.AUDIO, Media.VIDEO)
        Log.d(Config.LOGTAG, "jid=" + jid)
        Log.d(Config.LOGTAG, "phoneAccountHandle:" + phoneAccountHandle.getId())
        Log.d(Config.LOGTAG, "media " + media)
        val service = ServiceConnectionService.get(this.serviceFuture!!)
        return createOutgoingRtpConnection(service, phoneAccountHandle.getId(), jid, media)
    }

    override fun onCreateIncomingConnection(
            phoneAccountHandle: PhoneAccountHandle,
            request: ConnectionRequest
    ): Connection {
        Log.d(Config.LOGTAG, "onCreateIncomingConnection()")
        val service = ServiceConnectionService.get(this.serviceFuture!!)
        val extras: Bundle = request.getExtras()
        val extraExtras = extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val incomingCallAddress: String? =
                extras.getString(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        val sid = extraExtras?.getString(EXTRA_SESSION_ID)
        Log.d(Config.LOGTAG, "sid " + sid)
        val uri = if (incomingCallAddress == null) null else Uri.parse(incomingCallAddress)
        Log.d(Config.LOGTAG, "uri=" + uri)
        if (uri == null || sid == null) {
            return Connection.createFailedConnection(
                    DisconnectCause(
                            DisconnectCause.ERROR,
                            "connection request is missing required information"))
        }
        if (service == null) {
            return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "service connection not found"))
        }
        val jid = Jid.of(uri.getSchemeSpecificPart())
        val account = AccountRegistry.get().findAccountByUuid(phoneAccountHandle.getId())
        // Tulkki: 3.7 C5 - the Java handed this possibly-null `Account` to the island's non-null
        // `AccountRef`; `:data`'s `Account` implements that ref, so no boundary moves, and the Java's
        // null case found no connection and took the `no incoming connection found` arm below. The
        // parameter is not widened to take the null; the lookup is simply skipped for it.
        val weakReference =
                if (account == null) null
                else service.getJingleConnectionManager().findJingleRtpConnection(account, jid, sid)
        if (weakReference == null) {
            Log.d(Config.LOGTAG, "no connection found for " + jid + " and sid=" + sid)
            return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "no incoming connection found"))
        }
        val jingleRtpConnection = weakReference.get()
        if (jingleRtpConnection == null) {
            Log.d(Config.LOGTAG, "connection has been terminated")
            return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "connection has been terminated"))
        }
        Log.d(Config.LOGTAG, "registering call integration for incoming call")
        // The port's only implementation is the `Connection` subclass this service already owns.
        return jingleRtpConnection.getCallIntegration() as Connection
    }

    companion object {

        // Tulkki: the pre-rename text on purpose - these are Bundle keys handed to Telecom (put at
        // placeCall/addNewIncomingCall, read back in createConnection/onCallAudioStateChanged), and
        // Telecom keeps the bundle a call in flight was built with across a package replace. Renaming
        // them makes Jid.of(extras.getString(EXTRA_ADDRESS)) and the session lookup read null. See
        // docs/MIGRATION.md "The literal audit" F5; protected in tools/rename-packages and pinned by
        // OsHeldNamesTest.
        private const val EXTRA_ADDRESS = "eu.siacs.conversations.address"

        const val EXTRA_SESSION_ID = "eu.siacs.conversations.sid"

        private val ACCOUNT_REGISTRATION_EXECUTOR: ExecutorService =
                Executors.newSingleThreadExecutor()

        private fun createOutgoingRtpConnection(
                service: XmppConnectionService?,
                phoneAccountHandle: String,
                with: Jid,
                media: Set<Media>
        ): Connection {
            if (service == null) {
                Log.d(
                        Config.LOGTAG,
                        "CallIntegrationConnection service was unable to bind to" +
                                " XmppConnectionService")
                return Connection.createFailedConnection(
                        DisconnectCause(DisconnectCause.ERROR, "service connection not found"))
            }
            val account =
                    AccountRegistry.get().findAccountByUuid(phoneAccountHandle)
                            ?: throw NullPointerException()
            return createOutgoingRtpConnection(service, account, with, media)
        }

        private fun createOutgoingRtpConnection(
                service: XmppConnectionService,
                account: Account,
                with: Jid,
                media: Set<Media>
        ): Connection {
            Log.d(Config.LOGTAG, "create outgoing rtp connection!")
            val intent = Intent(service, RtpSessionActivity::class.java)
            intent.setAction(Intent.ACTION_VIEW)
            intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().toString())
            intent.putExtra(RtpSessionActivity.EXTRA_WITH, with.toString())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val callIntegration: Connection
            if (with.isBareJid()) {
                val contact = account.getRoster().getContact(with)
                if (Config.JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK &&
                        contact.getPresences().isEmpty()) {
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_REPORTED_STATE,
                            RtpEndUserState.CONTACT_OFFLINE.toString())
                    callIntegration =
                            Connection.createFailedConnection(
                                    DisconnectCause(
                                            DisconnectCause.ERROR, "contact is offline"))
                    // we can use a JMI 'finish' message to notify the contact of a call we never
                    // actually attempted
                    // sendJingleFinishMessage(service, contact, Reason.CONNECTIVITY_ERROR);
                } else {
                    val proposal: JingleConnectionManager.RtpSessionProposal?
                    try {
                        proposal =
                                service.getJingleConnectionManager()
                                        .proposeJingleRtpSession(account, with, media)
                    } catch (e: IllegalStateException) {
                        return Connection.createFailedConnection(
                                DisconnectCause(
                                        DisconnectCause.ERROR,
                                        "Phone is busy. Probably race condition. Try again in a" +
                                                " moment"))
                    }
                    if (proposal == null) {
                        // TODO instead of just null checking try to get the sessionID
                        return Connection.createFailedConnection(
                                DisconnectCause(
                                        DisconnectCause.ERROR, "a call is already in progress"))
                    }
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_REPORTED_STATE,
                            RtpEndUserState.FINDING_DEVICE.toString())
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, proposal.sessionId)
                    callIntegration = proposal.getCallIntegration() as Connection
                }
                if (Media.audioOnly(media)) {
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_ACTION,
                            RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
                } else {
                    intent.putExtra(
                            RtpSessionActivity.EXTRA_LAST_ACTION,
                            RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
                }
            } else {
                val jingleRtpConnection =
                        service.getJingleConnectionManager()
                                .initializeRtpSession(account, with, media)
                val sessionId = jingleRtpConnection.getId().sessionId
                intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, sessionId)
                callIntegration = jingleRtpConnection.getCallIntegration() as Connection
            }
            service.startActivity(intent)
            return callIntegration
        }

        private fun sendJingleFinishMessage(
                service: XmppConnectionService,
                contact: Contact,
                reason: Reason
        ) {
            service.getJingleConnectionManager()
                    .sendJingleMessageFinish(contact, UUID.randomUUID().toString(), reason)
        }

        @JvmStatic
        fun togglePhoneAccountAsync(context: Context, account: AccountRef) {
            ACCOUNT_REGISTRATION_EXECUTOR.execute { togglePhoneAccount(context, account) }
        }

        private fun togglePhoneAccount(context: Context, account: AccountRef) {
            if (account.isEnabled()) {
                registerPhoneAccount(context, account)
            } else {
                unregisterPhoneAccount(context, account)
            }
        }

        private fun registerPhoneAccount(context: Context, account: AccountRef) {
            try {
                registerPhoneAccountOrThrow(context, account)
            } catch (e: IllegalArgumentException) {
                Log.w(
                        Config.LOGTAG,
                        "could not register phone account for " + account.getJid().asBareJid(),
                        e)
                ContextCompat.getMainExecutor(context)
                        .execute { showCallIntegrationNotAvailable(context) }
            } catch (e: SecurityException) {
                Log.w(
                        Config.LOGTAG,
                        "could not register phone account for " + account.getJid().asBareJid(),
                        e)
                ContextCompat.getMainExecutor(context)
                        .execute { showCallIntegrationNotAvailable(context) }
            }
        }

        private fun showCallIntegrationNotAvailable(context: Context) {
            Toast.makeText(context, R.string.call_integration_not_available, Toast.LENGTH_LONG)
                    .show()
        }

        private fun registerPhoneAccountOrThrow(context: Context, account: AccountRef) {
            val handle = getHandle(context, account)
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (telecomManager.getOwnSelfManagedPhoneAccounts().contains(handle)) {
                    Log.d(
                            Config.LOGTAG,
                            "a phone account for " +
                                    account.getJid().asBareJid() +
                                    " already exists")
                    return
                }
            }
            val builder =
                    PhoneAccount.builder(getHandle(context, account), account.getJid().asBareJid())
            builder.setSupportedUriSchemes(Collections.singletonList("xmpp"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setCapabilities(
                        PhoneAccount.CAPABILITY_SELF_MANAGED or
                                PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            }
            val phoneAccount = builder.build()
            telecomManager.registerPhoneAccount(phoneAccount)
        }

        @JvmStatic
        fun togglePhoneAccountsAsync(context: Context, accounts: Collection<AccountRef>) {
            ACCOUNT_REGISTRATION_EXECUTOR.execute { togglePhoneAccounts(context, accounts) }
        }

        private fun togglePhoneAccounts(context: Context, accounts: Collection<AccountRef>) {
            for (account in accounts) {
                if (account.isEnabled()) {
                    try {
                        registerPhoneAccountOrThrow(context, account)
                    } catch (e: IllegalArgumentException) {
                        Log.w(
                                Config.LOGTAG,
                                "could not register phone account for " +
                                        account.getJid().asBareJid(),
                                e)
                    } catch (e: SecurityException) {
                        Log.w(
                                Config.LOGTAG,
                                "could not register phone account for " +
                                        account.getJid().asBareJid(),
                                e)
                    }
                } else {
                    unregisterPhoneAccount(context, account)
                }
            }
        }

        @JvmStatic
        fun unregisterPhoneAccount(context: Context, account: AccountRef) {
            val handle = getHandle(context, account)
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (telecomManager.getOwnSelfManagedPhoneAccounts().contains(handle)) {
                    telecomManager.unregisterPhoneAccount(handle)
                }
            } else {
                telecomManager.unregisterPhoneAccount(handle)
            }
        }

        /**
         * Tulkki: 3.7 C5-E4 - the parameter is the island's ref because `findPhoneAccount` passes
         * `id.account`. The body reads only `account.getUuid()`, the ref's own member; the three other
         * callers pass model `Account`s, which is an upcast and needs no edit.
         */
        @JvmStatic
        fun getHandle(context: Context, account: AccountRef): PhoneAccountHandle {
            val competentName = ComponentName(context, CallIntegrationConnectionService::class.java)
            return PhoneAccountHandle(competentName, account.getUuid())
        }

        @JvmStatic
        fun placeCall(
                service: XmppConnectionService,
                account: Account,
                with: Jid,
                media: Set<Media>
        ) {
            if (CallIntegration.selfManaged(service)) {
                val extras = Bundle()
                extras.putParcelable(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getHandle(service, account))
                extras.putInt(
                        TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                        if (Media.audioOnly(media)) VideoProfile.STATE_AUDIO_ONLY
                        else VideoProfile.STATE_BIDIRECTIONAL)
                if (service.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) !=
                        PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                                    service,
                                    R.string.no_permission_to_place_call,
                                    Toast.LENGTH_SHORT)
                            .show()
                    return
                }
                val address: Uri
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Android 9+ supports putting xmpp uris into the address
                    address = CallIntegration.address(with)
                } else {
                    // for Android 8 we need to put in a fake tel uri
                    val outgoingCallExtras = Bundle()
                    outgoingCallExtras.putString(EXTRA_ADDRESS, with.toString())
                    extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingCallExtras)
                    address = Uri.parse("tel:0")
                }
                try {
                    service.getSystemService(TelecomManager::class.java).placeCall(address, extras)
                    return
                } catch (e: SecurityException) {
                    Log.e(Config.LOGTAG, "call integration not available", e)
                }
            }

            val connection = createOutgoingRtpConnection(service, account, with, media)
            if (connection != null) {
                Log.d(
                        Config.LOGTAG,
                        "not adding outgoing call to TelecomManager on Android " +
                                Build.VERSION.RELEASE +
                                " (" +
                                Build.DEVICE +
                                ")")
            }
        }

        private fun findPhoneAccount(
                context: Context,
                id: AbstractJingleConnection.Id
        ): ArrayList<PhoneAccountHandle> {
            val def = getHandle(context, id.account)
            val lst = ArrayList<PhoneAccountHandle>()
            if (CallIntegration.selfManaged(context)) lst.add(def)
            if (Build.VERSION.SDK_INT < 23) return lst

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean("dialler_integration_incoming", true)) return lst

            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                // We cannot request audio permission in Dialer UI
                // when Dialer is shown over keyguard, the user cannot even necessarily
                // see notifications.
                return lst
            }

            /* Are video calls really coming in from a PSTN gateway?
            if (media.size() != 1 || !media.contains(Media.AUDIO)) {
                // Currently our ConnectionService only handles single audio calls
                Log.w(Config.LOGTAG, "only audio calls can be handled by cheogram connection service");
                return def;
            }*/

            // Tulkki: 3.7 C5-E4 - `id.account` is the island's ref now, so this roster walk is over
            // `ContactRef`s. Every read below is one of the ref's own members, so no cast is needed.
            for (contact in id.account.getRoster().getContacts()) {
                if (contact.getJid().getDomain() != id.with.getDomain()) {
                    continue
                }

                if (!contact.getPresences().anyIdentity("gateway", "pstn")) {
                    continue
                }

                val handle = contact.phoneAccountHandle()
                if (handle != null) lst.add(0, handle)
            }

            return lst
        }

        @JvmStatic
        fun addNewIncomingCall(context: Context, id: AbstractJingleConnection.Id): Boolean {
            if (NotificationService.isQuietHours(context, id.getContact().getAccount() as Account))
                    return true
            val phoneAccountHandles = findPhoneAccount(context, id)
            if (phoneAccountHandles.isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        "not adding incoming call to TelecomManager on Android " +
                                Build.VERSION.RELEASE +
                                " (" +
                                Build.DEVICE +
                                ")")
                return false
            }
            val bundle = Bundle()
            bundle.putString(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    CallIntegration.address(id.with).toString())
            val extras = Bundle()
            extras.putString(EXTRA_SESSION_ID, id.sessionId)
            extras.putString("account", id.account.getJid().toString())
            extras.putString("with", id.with.toString())
            bundle.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras)
            for (phoneAccountHandle in phoneAccountHandles) {
                try {
                    context.getSystemService(TelecomManager::class.java)
                            .addNewIncomingCall(phoneAccountHandle, bundle)
                    return true
                } catch (e: SecurityException) {
                    Log.e(
                            Config.LOGTAG,
                            id.account.getJid().asBareJid().toString() + ": call integration not available",
                            e)
                }
            }
            return false
        }
    }

    class ServiceConnectionService(
            val serviceConnection: ServiceConnection,
            val service: XmppConnectionService
    ) {

        companion object {

            @JvmStatic
            fun get(future: ListenableFuture<ServiceConnectionService>): XmppConnectionService? {
                try {
                    return future.get(2, TimeUnit.SECONDS).service
                } catch (e: ExecutionException) {
                    return null
                } catch (e: InterruptedException) {
                    return null
                } catch (e: TimeoutException) {
                    return null
                }
            }

            @JvmStatic
            fun bindService(context: Context): ListenableFuture<ServiceConnectionService> {
                val serviceConnectionFuture = SettableFuture.create<ServiceConnectionService>()
                val intent = Intent(context, XmppConnectionService::class.java)
                intent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_CALL_INTEGRATION_SERVICE_STARTED)
                val serviceConnection =
                        object : ServiceConnection {

                            override fun onServiceConnected(
                                    name: ComponentName,
                                    iBinder: IBinder
                            ) {
                                val binder = iBinder as uk.xa0.tulkki.xmpp.services.XmppConnectionBinder
                                serviceConnectionFuture.set(
                                        ServiceConnectionService(this, binder.getService()))
                            }

                            override fun onServiceDisconnected(name: ComponentName) {}
                        }
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                return serviceConnectionFuture
            }
        }
    }
}
