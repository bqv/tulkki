package uk.xa0.tulkki.app

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import io.ipfs.cid.Cid
import java.io.File
import java.net.URISyntaxException
import java.util.function.Consumer
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.app.extras.AndroidLoggingHandler
import uk.xa0.tulkki.app.extras.BobTransfer
import uk.xa0.tulkki.app.receiver.SystemEventReceiver
import uk.xa0.tulkki.app.services.AttachFileToConversationRunnable
import uk.xa0.tulkki.app.services.AvatarService
import uk.xa0.tulkki.app.services.CallIntegration
import uk.xa0.tulkki.app.services.CallIntegrationConnectionService
import uk.xa0.tulkki.app.services.ChannelDiscoveryService
import uk.xa0.tulkki.app.services.ContactListSyncService
import uk.xa0.tulkki.app.services.MessageSearchTask
import uk.xa0.tulkki.app.services.NotificationService
import uk.xa0.tulkki.app.services.PushManagementService
import uk.xa0.tulkki.app.services.ShortcutService
import uk.xa0.tulkki.app.services.UnifiedPushBroker
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.app.utils.PhoneHelper
import uk.xa0.tulkki.app.utils.RecursiveFileObserver
import uk.xa0.tulkki.app.utils.TranscoderStrategies
import uk.xa0.tulkki.app.utils.WakeLockHelper
import uk.xa0.tulkki.crypto.CryptoTrustPort
import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.crypto.PgpCallback
import uk.xa0.tulkki.crypto.PgpEngine
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.sync.SyncEngine
import uk.xa0.tulkki.translation.EngineHost
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.OutgoingTranslation
import uk.xa0.tulkki.translation.ReplySpan
import uk.xa0.tulkki.translation.StartupBacklog
import uk.xa0.tulkki.translation.TranslationActivityPort
import uk.xa0.tulkki.translation.TranslationService
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.translation.TranslationStore
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.mam.MamAbort
import uk.xa0.tulkki.xmpp.mam.MamFin
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.mam.SyncAnchors
import uk.xa0.tulkki.xmpp.mam.SyncEvents
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xmpp.services.AttachFilePort
import uk.xa0.tulkki.xmpp.services.AttachFileTask
import uk.xa0.tulkki.xmpp.services.FileObserverPort
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The composition root's whole side of Tulkki's boundary: one adapter over the running service, which
 * the engine, the send path and the archive catch-up all reach through ports they declare themselves.
 *
 * <p>It is the only class that has to know both ends. The engine (`:translation`) may not name the
 * island that runs it nor the modules above; the island (`:xmpp`) may not name the engine at all. So
 * the ports are declared on each side - [EngineHost] by the engine, [uk.xa0.tulkki.xmpp.services.SendGate]
 * and `MessageArchiveService.CatchupFinishedHook` by the island - and this object, built where the
 * service exists, implements every one of them by delegating to the real thing. The file it lives in
 * is the one file that may name all of them.
 *
 * <p><strong>The installation is one line and one object.</strong> `TulkkiApplication.onCreate`
 * installs [XmppConnectionService.installPortsFactory]; each service, in its own `onCreate`, asks it
 * for its ports; the factory is this class's [install], which builds the adapter <em>and</em>
 * registers it as the engine's host in the same breath. There is exactly one service per process and
 * one host per service, which is why a single slot is the whole of the lookup - and why the engine's
 * two ways in (the `Context` it is handed, and the one bubble question that has no Context) both land
 * here.
 *
 * <p>3.7 pair 12 moved [TranslationStore] into `:translation`: it used to hold a weak reference to the
 * service, attached here immediately before the archive pass because attaching it took the service
 * <em>type</em>. The store now asks [EngineHost.orNull] instead - the one slot this constructor fills,
 * on the line below the ports'.
 *
 * <p>Kotlin notes from the port (`applast`). The Java's `private static final` executor-shaped fields
 * are ordinary `private val`s, and [install]/[trustPorts] keep their Java-visible static spelling as
 * companion `@JvmStatic`s because `TulkkiApplication` names both. The singleton adapters stay `enum
 * class`es with their `INSTANCE` entry, so each `Foo.INSTANCE` read is the Java's. The two Kotlin
 * objects the Java reached as `UiTulkkiPorts.INSTANCE` and `CryptoTrustPort.INSTANCE` are named
 * without the synthetic field here - a Kotlin `object` is its own instance, which is the same object
 * the Java's `INSTANCE` held.
 */
class XmppTulkkiHost
private constructor(private val service: XmppConnectionService) :
        EngineHost,
        uk.xa0.tulkki.xmpp.services.TulkkiPorts,
        uk.xa0.tulkki.xmpp.services.SendGate,
        uk.xa0.tulkki.xmpp.services.TranslationQueue,
        SyncEvents,
        SyncAnchors {

    // -- pair 4's held instances -------------------------------------------------------------------
    //
    // Every one of these was a field of `XmppConnectionService` until this pair: the island held the
    // service objects it needed and could not name their classes. The composition root builds them
    // here, in the one object that may name both ends, and hands them back through `TulkkiPorts`;
    // the island's `onCreate` takes them from there.
    private val avatarService = AvatarService(service)
    private val notificationService = NotificationService(service)
    private val channelDiscoveryService = ChannelDiscoveryService(service)
    private val shortcutService = ShortcutService(service)
    private val unifiedPushBroker = UnifiedPushBroker(service)
    private val pushManagementService = PushManagementService(service)
    private val contactListSyncService = ContactListSyncService(service)

    private val incomingMessageHook: uk.xa0.tulkki.xmpp.services.IncomingMessageHook =
            IncomingMessageAdapter(service)

    // -- the engine's host -------------------------------------------------------------------------

    init {
        // The engine's host is installed here because this is the only moment that has both the
        // service and the composition root's blessing; see the class comment.
        EngineHost.install(this)
    }

    override fun databaseBackend(): DatabaseBackend {
        // Tulkki: C5-E5 - `XmppConnectionService.databaseBackend` is the island's `DatabaseBackendRef`
        // now, and this signature must stay the model: `:translation.allow` does not name `:xmpp`, so
        // a ref return here would be a new forbidden edge. `DatabaseBackend.get()` answers the same
        // instance the field was assigned from; only the declared view differs.
        return DatabaseBackend.get()
    }

    override fun updateConversationUi() {
        service.updateConversationUi()
    }

    override fun updateConversation(conversation: Conversation) {
        service.updateConversation(conversation)
    }

    override fun updateNotifications() {
        notificationService.updateNotification()
    }

    override fun conversation(conversationUuid: String): Conversation? =
            service.findConversationByUuid(conversationUuid) as Conversation?

    override fun resendMessage(message: Message, isResend: Boolean) {
        service.resendMessage(message, isResend)
    }

    override fun replySpan(message: Message): ReplySpan = ReplySpans.of(message)

    override fun activity(): TranslationActivityPort = TranslationSettings.get(service).activity()

    override fun translationService(context: Context): TranslationService =
            TranslationHooks.get(context)

    override fun kickTranslationWork(context: Context) {
        TranslationWork.kick(context)
    }

    // -- the island's ports ------------------------------------------------------------------------

    override fun sendGate(): uk.xa0.tulkki.xmpp.services.SendGate = this

    /**
     * Tulkki: the translation queue's one question, over the store that owns it.
     *
     * <p>3.7 pair 9, ruling 3 of round 161. This is the class that may name both ends, so the
     * island's port is answered by the engine's store here and nowhere else. The store is built once,
     * lazily, because the island used to build one per question and the object is a thin wrapper over
     * the database - the same rows, one instance.
     */
    override fun translationQueue(): uk.xa0.tulkki.xmpp.services.TranslationQueue = this

    override fun hasQueued(uuid: String?): Boolean = store().hasQueued(uuid)

    override fun forget(uuid: String?) {
        store().forget(uuid)
    }

    override fun syncEvents(): SyncEvents = this

    override fun syncAnchors(): SyncAnchors = this

    // -- the sync engine: the island's events, and the answers it asks for -------------------------
    //
    // `SyncEvents` and `SyncAnchors` are declared island-side and implemented here because this is the
    // one class that may name both ends - the same reason `CatchupFinishedHook` is implemented here.
    // `SyncEngine` is `:data`'s: it owns the cursor, the ledger and the reducer, and it may not name
    // the translation queue, so the sweep it owes comes back to this object as message uuids and is
    // handed to `:translation` from here.

    override fun onSessionEstablished(account: AccountRef, atWallClock: Long, resumed: Boolean) {
        engine().onSessionEstablished(account, atWallClock, resumed)
    }

    override fun onMamFin(fin: MamFin) {
        engine().onMamFin(fin.account(), fin)
    }

    override fun onMamAborted(account: AccountRef, reason: MamAbort) {
        engine().onMamAborted(account, reason)
    }

    override fun onMessageStored(
            account: AccountRef,
            conversation: ConversationRef?,
            timeSent: Long,
            archived: Boolean,
    ) {
        engine().onMessageStored(account, conversation, timeSent, archived)
    }

    override fun onClientStateChanged(account: AccountRef, active: Boolean) {
        engine().onClientStateChanged(account, active)
    }

    override fun onSessionEnded(account: AccountRef) {
        engine().onSessionEnded(account)
    }

    override fun anchorFor(account: AccountRef): MamReference {
        val fromCursor = engine().anchorFor(account)
        if (fromCursor.getTimestamp() > 0) {
            return fromCursor
        }
        // An account the migration never seeded. The old derivation is still the only statement of
        // where its history ends, and it is recorded on the engine so the ledger enumerates from the
        // same anchor this method is about to hand the island.
        val derived: MamReference? =
                MamReference.max(
                        databaseBackend().getLastMessageReceived(account),
                        databaseBackend().getLastClearDate(account))
        if (derived != null && derived.getTimestamp() > 0) {
            engine().seedCursor(account, derived)
            return derived
        }
        return fromCursor
    }

    override fun anchorFor(conversation: ConversationRef): MamReference =
            engine().anchorFor(conversation)

    // -- pair 7's three crypto-side handles --------------------------------------------------------

    /**
     * The trust family is implemented in `:crypto`, where the four classes that do the work live; the
     * composition root is the one place that names both ends.
     */
    override fun trustPort(): uk.xa0.tulkki.xmpp.services.TrustPort = trustPorts()

    override fun pgpEngineFactory(): uk.xa0.tulkki.xmpp.services.PgpEngineFactory =
            uk.xa0.tulkki.xmpp.services.PgpEngineFactory { api, service -> PgpEngine(api, service) }

    override fun omemoSetting(): uk.xa0.tulkki.xmpp.services.OmemoSettingsPort =
            uk.xa0.tulkki.xmpp.services.OmemoSettingsPort { context -> OmemoSetting.load(context) }

    // -- pair 11's UI-side handles -----------------------------------------------------------------
    //
    // All six are implemented by `UiTulkkiPorts`, the one `:app` class that names the `:ui` screens
    // these ports are about; this object only hands them out. `pgpCallback` is the odd one: it is not
    // a handle but a one-line adapter, built here because this is the one place that may name both
    // the island's continuation and `:crypto`'s.

    override fun liveLocationHook(): uk.xa0.tulkki.xmpp.services.LiveLocationHook = UiTulkkiPorts

    override fun rtpSessionPort(): uk.xa0.tulkki.xmpp.services.RtpSessionPort = UiTulkkiPorts

    override fun themePort(): uk.xa0.tulkki.xmpp.services.ThemePort = UiTulkkiPorts

    override fun profilePictureActivityPort(): uk.xa0.tulkki.xmpp.services.ProfilePictureActivityPort =
            UiTulkkiPorts

    override fun decisionScreenPort(): MemorizingTrustManager.DecisionScreenPort = UiTulkkiPorts

    /**
     * The island's continuation, in the shape the PGP engine's callback slot casts to. The slot's
     * type is `:crypto`'s [PgpCallback], which the island may not name at all - the same fact that
     * makes `PgpEnginePort`'s callback parameters `Object` - and this class names both, so it is where
     * the adapter belongs.
     */
    override fun pgpCallback(callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<*>): Any =
            pgpAdapter(callback)

    private fun <T> pgpAdapter(callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<T>): PgpCallback<T> =
            object : PgpCallback<T> {
                override fun success(`object`: T) {
                    callback.success(`object`)
                }

                override fun error(errorCode: Int, `object`: T?) {
                    callback.error(errorCode, `object`)
                }

                override fun userInputRequired(pi: PendingIntent?, `object`: T) {
                    callback.userInputRequired(pi, `object`)
                }
            }

    override fun refuseQuickReply(conversation: ConversationRef?, body: String?): Boolean =
            OutgoingTranslation.refuseQuickReply(service, conversation as Conversation?, body)

    // Tulkki: 3.7 pair 9, part 16 - part 12 declared these twice (model and ref); with the island's
    // model type gone the two collapse into the ref form and each casts once before delegating, the
    // object really being a `Message`.

    override fun holdBack(message: MessageRef): Boolean =
            OutgoingTranslation.holdBack(service, message as Message)

    override fun alreadyHeld(conversation: ConversationRef, message: MessageRef): Boolean =
            HeldSend.alreadyHeld(conversation as Conversation, message as Message)

    // -- pair 4: the `:app` names the island used to carry -----------------------------------------
    //
    // Two kinds of answer. The carriers that are *objects* - the avatar cache, the notification
    // service, the shortcut publisher, the push broker, the push registration, the contact-list sync
    // and the emoji index - implement their port themselves (`uk.xa0.tulkki.app.services.*`), because
    // their methods already have the port's signatures; this class only hands the instance over. The
    // carriers that are *static helpers* get a small adapter here, because Kotlin (like Java) has no
    // way for a static method to satisfy an interface method - and this file is the one that may name
    // both ends anyway.

    override fun compatibility(): uk.xa0.tulkki.xmpp.services.CompatibilityPort = CompatibilityAdapter.INSTANCE

    override fun phoneHelper(): uk.xa0.tulkki.xmpp.services.PhoneHelperPort = PhoneHelperAdapter.INSTANCE

    override fun wakeLock(): uk.xa0.tulkki.xmpp.services.WakeLockPort = WakeLockAdapter.INSTANCE

    override fun transcoderStrategies(): uk.xa0.tulkki.xmpp.services.TranscoderStrategiesPort =
            TranscoderStrategiesAdapter.INSTANCE

    override fun systemEvent(): uk.xa0.tulkki.xmpp.services.SystemEventPort = SystemEventAdapter.INSTANCE

    override fun loggingHook(): uk.xa0.tulkki.xmpp.services.LoggingHook = LoggingAdapter.INSTANCE

    override fun messageSearch(): uk.xa0.tulkki.xmpp.services.MessageSearchPort =
            uk.xa0.tulkki.xmpp.services.MessageSearchPort { service, term, uuid, callback ->
                MessageSearchTask.search(service, term, uuid, callback)
            }

    override fun bobTransfer(): uk.xa0.tulkki.xmpp.services.BobTransferPort = BobTransferAdapter.INSTANCE

    /** The one `:app` class that implements its port itself: it is what `TulkkiApplication` builds. */
    override fun contactListSync(): uk.xa0.tulkki.xmpp.services.ContactListSyncPort = contactListSyncService

    override fun attachFile(): AttachFilePort = AttachFileAdapter(service)

    override fun fileObserver(): FileObserverPort = FileObserverAdapter()

    override fun avatar(): uk.xa0.tulkki.xmpp.services.AvatarPort = AvatarAdapter(avatarService)

    override fun notification(): uk.xa0.tulkki.xmpp.services.NotificationPort = notificationService

    override fun channelDiscovery(): uk.xa0.tulkki.xmpp.services.ChannelDiscoveryPort =
            channelDiscoveryService

    override fun shortcuts(): uk.xa0.tulkki.xmpp.services.ShortcutPort = shortcutService

    override fun unifiedPush(): uk.xa0.tulkki.xmpp.services.UnifiedPushPort = unifiedPushBroker

    override fun pushManagement(): uk.xa0.tulkki.xmpp.services.PushManagementPort = pushManagementService

    override fun incomingMessageHook(): uk.xa0.tulkki.xmpp.services.IncomingMessageHook =
            incomingMessageHook

    override fun callIntegration(): uk.xa0.tulkki.xmpp.services.CallIntegrationFactory =
            CallIntegrationAdapter.INSTANCE

    // -- the lazy singletons -----------------------------------------------------------------------

    /** Built on the first question, over the running service; see [hasQueued]. */
    private var translationStore: TranslationStore? = null

    private fun store(): TranslationStore =
            translationStore ?: TranslationStore(service).also { translationStore = it }

    /**
     * The one engine, over the one open database. Lazily, like the store above.
     *
     * <p>The engine's own thread hands the sweep back here; it never runs on the caller's thread
     * (S5-6: the launch crash was this class calling the engine from `onServiceConnected` on the main
     * thread, where Room's blocking calls assert).
     */
    private var engine: SyncEngine? = null

    private fun engine(): SyncEngine =
            engine
                    ?: SyncEngine.get(service).also {
                        SyncEngine.installSweepSink(this::onSweep)
                        engine = it
                    }

    /** The engine's sweep, on the engine's thread: hand it to the queue's own entry point. */
    private fun onSweep(account: String, candidates: List<String>) {
        sweep(candidates)
    }

    /** The gap the engine just proved closed owes one sweep; run it, on the engine's own answer. */
    private fun sweep(candidates: List<String>?) {
        if (candidates != null && candidates.isNotEmpty()) {
            StartupBacklog.sweep(service, candidates)
        }
    }

    // -- pair 4's adapters over `:app`'s static helpers --------------------------------------------

    /** [Compatibility]'s statics, behind the island's port. */
    private enum class CompatibilityAdapter : uk.xa0.tulkki.xmpp.services.CompatibilityPort {
        INSTANCE;

        override fun s(): Boolean = Compatibility.s()

        override fun isActiveNetworkMetered(connectivityManager: ConnectivityManager): Boolean =
                Compatibility.isActiveNetworkMetered(connectivityManager)

        override fun getRestrictBackgroundStatus(connectivityManager: ConnectivityManager): Int =
                Compatibility.getRestrictBackgroundStatus(connectivityManager)

        override fun runsTwentySix(): Boolean = Compatibility.runsTwentySix()

        override fun runsAndTargetsTwentySix(context: Context): Boolean =
                Compatibility.runsAndTargetsTwentySix(context)

        override fun hasStoragePermission(context: Context): Boolean =
                Compatibility.hasStoragePermission(context)

        override fun keepForegroundService(context: Context): Boolean =
                Compatibility.keepForegroundService(context)
    }

    /** [PhoneHelper]'s two device questions. */
    private enum class PhoneHelperAdapter : uk.xa0.tulkki.xmpp.services.PhoneHelperPort {
        INSTANCE;

        override fun getAndroidId(context: Context): String? = PhoneHelper.getAndroidId(context)

        override fun isEmulator(): Boolean = PhoneHelper.isEmulator()
    }

    /** [WakeLockHelper]'s two static calls. */
    private enum class WakeLockAdapter : uk.xa0.tulkki.xmpp.services.WakeLockPort {
        INSTANCE;

        override fun acquire(wakeLock: PowerManager.WakeLock?) {
            WakeLockHelper.acquire(wakeLock)
        }

        override fun release(wakeLock: PowerManager.WakeLock?) {
            WakeLockHelper.release(wakeLock)
        }
    }

    /** [TranscoderStrategies]' four constants. */
    private enum class TranscoderStrategiesAdapter :
            uk.xa0.tulkki.xmpp.services.TranscoderStrategiesPort {
        INSTANCE;

        override fun video720p(): DefaultVideoStrategy = TranscoderStrategies.VIDEO_720P

        override fun video360p(): DefaultVideoStrategy = TranscoderStrategies.VIDEO_360P

        override fun audioHq(): DefaultAudioStrategy = TranscoderStrategies.AUDIO_HQ

        override fun audioMq(): DefaultAudioStrategy = TranscoderStrategies.AUDIO_MQ
    }

    /** [SystemEventReceiver]'s class and its two string constants. */
    private enum class SystemEventAdapter : uk.xa0.tulkki.xmpp.services.SystemEventPort {
        INSTANCE;

        override fun receiverClass(): Class<*> = SystemEventReceiver::class.java

        override fun extraNeedsForegroundService(): String =
                SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE

        override fun settingEnabledAccounts(): String = SystemEventReceiver.SETTING_ENABLED_ACCOUNTS
    }

    /** [AndroidLoggingHandler]'s one installation call. */
    private enum class LoggingAdapter : uk.xa0.tulkki.xmpp.services.LoggingHook {
        INSTANCE;

        override fun install() {
            AndroidLoggingHandler.reset(AndroidLoggingHandler())
        }
    }

    /**
     * [BobTransfer]'s two uses.
     *
     * <p>[attachTo] is the whole of `MessageParser`'s second site: it builds the `ForMessage` over the
     * message, hands it to the message and starts it. The `URISyntaxException` the island used to
     * catch is caught here, with the same outcome - the transfer is simply not attached.
     */
    private enum class BobTransferAdapter : uk.xa0.tulkki.xmpp.services.BobTransferPort {
        INSTANCE;

        // `BobCid.cid` answers null on a live path; the port's Java return is platform, so the
        // forward carries the nullability.
        override fun cid(uri: Uri): Cid? = BobTransfer.cid(uri)

        override fun cid(bobCid: String): Cid? = BobTransfer.cid(bobCid)

        /**
         * Tulkki: 3.7 pair 9, part 12 - the same attachment with the parser's ref.
         *
         * <p>`MessageParser`'s BOB site holds a `MessageRef` now and a parameter type is not
         * covariant, so the port carries both. The object is a `Message` (the parser built it through
         * `DataStatics.newMessage`), so this is one identity-safe cast onto the body below.
         */
        override fun attachTo(message: MessageRef, service: XmppConnectionService) {
            val model = message as Message
            try {
                val transfer = BobTransfer.ForMessage(model, service)
                model.setTransferable(transfer)
                transfer.start()
            } catch (e: URISyntaxException) {
                android.util.Log.d(
                        uk.xa0.tulkki.xmpp.Config.LOGTAG, "BobTransfer failed to parse URI")
            }
        }
    }

    /**
     * The attachment worker.
     *
     * <p>`AttachFileToConversationRunnable` takes the service as its first argument and the callback
     * port pair 11 declared; the island builds one per attachment, so the adapter carries the service
     * and the two methods.
     */
    private class AttachFileAdapter(private val service: XmppConnectionService) :
            AttachFilePort {

        override fun create(
                message: MessageRef,
                uri: Uri,
                type: String,
                callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<MessageRef>,
        ): AttachFileTask {
            val runnable = AttachFileToConversationRunnable(service, uri, type, message, callback)
            return object : AttachFileTask {
                override fun isVideoMessage(): Boolean = runnable.isVideoMessage

                override fun run() {
                    runnable.run()
                }
            }
        }

        override fun videoCompression(context: Context): String? =
                AttachFileToConversationRunnable.getVideoCompression(context)
    }

    /**
     * The recursive file observer.
     *
     * <p>`RecursiveFileObserver` is an abstract class the island used to subclass anonymously to
     * override one method; the listener arrives as a `Consumer<File>` instead, so the anonymous
     * subclass lives here and the island keeps only the decision it made in the override.
     */
    private class FileObserverAdapter : FileObserverPort {

        override fun create(
                path: String,
                onDeleted: Consumer<File>,
        ): FileObserverPort.Watcher {
            val observer =
                    object : RecursiveFileObserver(path) {
                        override fun onEvent(event: Int, file: File) {
                            onDeleted.accept(file)
                        }
                    }
            return object : FileObserverPort.Watcher {
                override fun startWatching() {
                    observer.startWatching()
                }

                override fun stopWatching() {
                    observer.stopWatching()
                }

                override fun restartWatching() {
                    observer.restartWatching()
                }
            }
        }
    }

    /**
     * The avatar cache, adapted rather than implemented.
     *
     * <p>`AvatarService` is pair 3's file in the same commit window - it implements that pair's
     * `UiHost`-side `AvatarSource` - so pair 4 leaves it alone and wraps it here instead. The port's
     * members are the union of what this island and `:ui` call on the object the service hands out,
     * and every body below is a forward.
     */
    private class AvatarAdapter(private val service: AvatarService) :
            uk.xa0.tulkki.xmpp.services.AvatarPort {

        // Tulkki: 2026-10-08 - `Avatarable` moved to `uk.xa0.tulkki.libs`, so the port's avatar members
        // take the model type itself and these two casts are gone with the ref that forced them.
        // The object was always the model; `:libs` is a name both ends may write down.

        override fun get(
                avatarable: Avatarable,
                size: Int,
                cachedOnly: Boolean,
        ): Drawable? = service.get(avatarable, size, cachedOnly)

        override fun get(item: Avatarable, size: Int): Drawable? =
                service.get(item as ListItem, size)

        // Tulkki: C5-E1's `getAccountAvatar` shape a third time. `Conversation implements Avatarable,
        // ConversationRef`, and the two are unrelated types (`Avatarable` is `:libs`' marker, the
        // other an island ref), so an arity-3 `get(ConversationRef, ...)` beside
        // `get(Avatarable, ...)` makes every call with a *model* `Conversation` ambiguous. Nothing
        // called it, so the rename is prevention; both arities move together, as the two
        // `getAccountAvatar` overloads did.

        override fun getConversationAvatar(conversation: ConversationRef, size: Int): Drawable? =
                service.get(conversation as Conversation, size)

        override fun getConversationAvatar(
                conversation: ConversationRef,
                size: Int,
                cachedOnly: Boolean,
        ): Drawable? = service.get(conversation as Conversation, size, cachedOnly)

        // Tulkki: C5-E1 renamed the two account overloads to `getAccountAvatar` and retyped their
        // parameter to the ref. The rename is forced, not stylistic: `Account implements Avatarable`
        // and `AccountRef` is a different type, so an `AccountRef` parameter sitting beside
        // `get(Avatarable, int, boolean)` makes every call with a *model* `Account` ambiguous.
        // The cast back is the adapter's usual one - the object really is the model.

        override fun getAccountAvatar(account: AccountRef, size: Int): Drawable? =
                service.get(account as Account, size)

        override fun getAccountAvatar(
                account: AccountRef,
                size: Int,
                cachedOnly: Boolean,
        ): Drawable? = service.get(account as Account, size, cachedOnly)

        override fun getMessageAvatar(
                message: MessageRef,
                size: Int,
                cachedOnly: Boolean,
        ): Drawable? = service.get(message as Message, size, cachedOnly)

        override fun get(name: String?, seed: String?, size: Int, cachedOnly: Boolean): Drawable =
                service.get(name, seed, size, cachedOnly)

        override fun clear(contact: ContactRef) {
            service.clear(contact as Contact)
        }

        override fun clear(conversation: ConversationRef) {
            service.clear(conversation as Conversation)
        }

        override fun clear(account: AccountRef) {
            // The island hands the ref; this adapter is the one place that names both, so the model
            // type comes back here and nowhere else (3.7 pair 9, the parameter half of cluster (f)).
            service.clear(account as Account)
        }

        /**
         * Tulkki: the island's overloads, and the same one-cast shape as [clear].
         *
         * <p>3.7 pair 9, part 11. `PresenceParser` drops a room's and a participant's cached avatar
         * while holding the refs, and this adapter is the class that may name both ends; the objects
         * really are the model's, because the ref's whole point is that `:data` implements it.
         */
        override fun clear(options: MucOptionsRef?) {
            service.clear(options as MucOptions?)
        }

        override fun clear(user: MucOptionsRef.UserRef) {
            service.clear(user as MucOptions.User)
        }

        override fun getRoundedShortcut(mucOptions: MucOptionsRef): Bitmap =
                service.getRoundedShortcut(mucOptions as MucOptions)

        // Tulkki: 3.7 pair 9, part 15 - the port's two contact methods are ref-shaped now, because
        // `XmppConnectionService` no longer names the model type. This adapter is `:app`, so it casts
        // back once and `AvatarService` itself is untouched.

        override fun getRoundedShortcut(contact: ContactRef): Bitmap =
                service.getRoundedShortcut(contact as Contact)

        override fun getRoundedShortcutWithIcon(contact: ContactRef): Bitmap =
                service.getRoundedShortcutWithIcon(contact as Contact)

        override fun systemUiAvatarSize(context: Context): Int = service.systemUiAvatarSize(context)
    }

    /**
     * The telecom call integration: the last two `:app` names the island carried.
     *
     * <p>`CallIntegration` itself implements [uk.xa0.tulkki.xmpp.services.CallIntegrationPort] - it is pair
     * 3's file in this commit window, and pair 4 adds the `implements` clause and widens its
     * `setCallback` parameter to the port's continuation once that pair is in. All this adapter then
     * has to do is forward the statics.
     */
    private enum class CallIntegrationAdapter :
            uk.xa0.tulkki.xmpp.services.CallIntegrationFactory {
        INSTANCE;

        override fun create(context: Context): uk.xa0.tulkki.xmpp.services.CallIntegrationPort =
                CallIntegration(context)

        override fun initialAudioDevice(media: Set<Media>): AudioDevice =
                CallIntegration.initialAudioDevice(media)

        override fun address(contact: Jid): Uri = CallIntegration.address(contact)

        override fun addNewIncomingCall(
                context: Context,
                id: AbstractJingleConnection.Id,
        ): Boolean = CallIntegrationConnectionService.addNewIncomingCall(context, id)

        override fun hasSystemFeature(context: Context): Boolean =
                CallIntegration.hasSystemFeature(context)

        override fun togglePhoneAccountsAsync(context: Context, accounts: Collection<out AccountRef>) {
            CallIntegrationConnectionService.togglePhoneAccountsAsync(context, accounts)
        }

        override fun togglePhoneAccountAsync(context: Context, account: AccountRef) {
            CallIntegrationConnectionService.togglePhoneAccountAsync(context, account)
        }

        override fun unregisterPhoneAccount(context: Context, account: AccountRef) {
            CallIntegrationConnectionService.unregisterPhoneAccount(context, account)
        }
    }

    /**
     * Pair 4's receive-path hooks, answered by the two `:app` classes that own the work.
     *
     * <p>Both live here rather than in a surface of their own because this is already where the
     * engine's host is built: `TranslationHooks.onIncomingMessage` enqueues into the engine's queue
     * and `MamLanguageSampler.offer` is the sample's own latch, and the island can name neither.
     */
    private class IncomingMessageAdapter(private val service: XmppConnectionService) :
            uk.xa0.tulkki.xmpp.services.IncomingMessageHook {

        override fun onIncomingMessage(
                message: MessageRef,
                fromArchive: Boolean,
                delayed: Boolean,
                replacement: Boolean,
        ) {
            val model = message as Message
            TranslationHooks.onIncomingMessage(
                    service, model, fromArchive, delayed, replacement)
            // Tulkki S5-4: the row's `delivery` marker, which is what makes the gap sweep's
            // `delivery = 1` mean "a catch-up handed this over" rather than "some row". The island
            // committed the row before this hook (MessageParser.createMessage), so the update lands.
            val conversation = model.getConversation() as ConversationRef?
            if (conversation != null) {
                val account = conversation.getAccount()
                if (account != null) {
                    SyncEngine.get(service)
                            .onMessageStored(
                                    account, conversation, model.getTimeSent(), fromArchive)
                }
            }
        }

        // Tulkki: the port must keep the nullability its callers actually pass. `MessageParser`
        // computes `queryId` as `result == null ? null : result.getAttribute("queryid")`, so a null
        // reaches here on every non-MAM message; the Java's platform `String` let it through and
        // `MamLanguageSampler.offer` answered false for it (`if (queryId == null || PENDING.isEmpty())`).
        // Kotlin's non-null `String` generated a parameter check instead and threw before that body
        // could run. Widened, not guarded: the null is forwarded and still answers false.
        override fun offerLanguageSample(
                queryId: String?,
                result: uk.xa0.tulkki.xmpp.models.stanza.Message,
        ): Boolean = MamLanguageSampler.offer(queryId, result)
    }

    companion object {

        /** What `TulkkiApplication.onCreate` installs: the factory, not an instance. */
        @JvmStatic
        fun install(service: XmppConnectionService): uk.xa0.tulkki.xmpp.services.TulkkiPorts =
                XmppTulkkiHost(service)

        @JvmStatic
        fun trustPorts(): uk.xa0.tulkki.xmpp.services.TrustPort = CryptoTrustPort.INSTANCE
    }
}
