package uk.xa0.tulkki.xmpp.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.provider.ContactsContract
import android.security.KeyChain
import android.telecom.CallAudioState
import android.util.Log
import android.util.LruCache
import android.util.Pair
import androidx.annotation.BoolRes
import androidx.annotation.IntegerRes
import androidx.annotation.Nullable
import androidx.annotation.StyleRes
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.common.base.Objects
import com.google.common.base.Optional
import com.google.common.base.Strings
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.common.collect.Maps
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import com.kedia.ogparser.JsoupProxy
import com.kedia.ogparser.OpenGraphCallback
import com.kedia.ogparser.OpenGraphParser
import com.kedia.ogparser.OpenGraphResult
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import io.ipfs.cid.Cid
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.Hashtable
import java.util.Iterator
import java.util.ListIterator
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import me.leolin.shortcutbadger.ShortcutBadger
import net.java.otr4j.session.Session
import net.java.otr4j.session.SessionID
import net.java.otr4j.session.SessionImpl
import net.java.otr4j.session.SessionStatus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.app.generator.MessageGenerator
import uk.xa0.tulkki.app.generator.PresenceGenerator
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.CommentRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.libs.EmojiRef
import uk.xa0.tulkki.libs.FilePathInfoRef
import uk.xa0.tulkki.libs.PostRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.PresenceTemplateRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.LocalizedContent
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnAdvancedStreamFeaturesLoaded
import uk.xa0.tulkki.xmpp.OnBindListener
import uk.xa0.tulkki.xmpp.OnContactStatusChanged
import uk.xa0.tulkki.xmpp.OnGatewayResult
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnMessageAcknowledged
import uk.xa0.tulkki.xmpp.OnStatusChanged
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.jid.OtrJidHelper
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.JingleRtpConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.mam.SyncAnchors
import uk.xa0.tulkki.xmpp.mam.SyncEvents
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.AccountRegistryRef
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.ReactionRef
import uk.xa0.tulkki.xmpp.refs.RosterRef
import uk.xa0.tulkki.libs.StoryRef
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import uk.xa0.tulkki.xmpp.utils.ReplacingSerialSingleThreadExecutor
import uk.xa0.tulkki.xmpp.utils.ReplacingTaskManager
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.xmpp.utils.SerialSingleThreadExecutor
import uk.xa0.tulkki.xmpp.utils.StringUtils
import uk.xa0.tulkki.xmpp.utils.TorServiceUtils
import uk.xa0.tulkki.xmpp.utils.XmppUri

// Tulkki: the class-level statics live in the `companion object` at the end of this file, so that
// `:app` and the island keep spelling them `XmppConnectionService.X` exactly as they did in Java.
class XmppConnectionService : Service() {

    // Tulkki: the action vocabulary moved to `ServiceActions` 
    // under the owner's 2026-10-08 licence, and every caller inside and outside the island was swept
    // to `ServiceActions.ACTION_...` in the same commit. Three stay here: the two `private` ones
    // cannot be a Kotlin `const val` a Java body reads without widening them, and
    // `ACTION_EXPIRE_MESSAGES` is pinned *in this file* by `OsHeldNamesTest`, because the alarm text
    // is data the operating system holds.
    // Tulkki: these two keep the pre-rename text on purpose. They are not names the compiler
    // resolves - they are data the operating system stores: an AlarmManager alarm survives a package
    // *replace*, so an alarm armed by the previous build is handed back to the new process carrying
    // the old action. Renaming the constant stops `case ACTION_EXPIRE_MESSAGES:` from matching and
    // the post-connectivity ping comparison from firing, so the expiry the alarm was due to run is
    // skipped. Restored and protected in tools/rename-packages' PROTECTED_STRINGS; pinned by
    // uk.xa0.upgrade.OsHeldNamesTest. See docs/MIGRATION.md "The literal audit" F2/F3.
    // (The two constants themselves are declared in the companion object below, where `OsHeldNamesTest`
    // still reads their literal initializers out of this file.)

    @JvmField
    val restoredFromDatabaseLatch: CountDownLatch = CountDownLatch(1)

    private val internalPingExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private val storyRetractionExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val storyCacheExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    // Set on the main thread at the very start of `onDestroy`, before `Teardown.onDestroy` shuts the
    // three pools above down, and never cleared: a destroyed Service instance is not reused. The
    // `db-init` continuation and the delayed ping run on threads that outlive the service, so they
    // read this to tell "the service is already going away" (do nothing, quietly) from a rejection
    // while it is alive (a real bug, which must surface). Volatile because the ping path reads it
    // from `internalPingExecutor` and the status listener, not from the main thread.
    @Volatile
    private var shuttingDown: Boolean = false

    private val mDatabaseWriterExecutor: SerialSingleThreadExecutor =
        SerialSingleThreadExecutor("DatabaseWriter")
    private val mDatabaseReaderExecutor: SerialSingleThreadExecutor =
        SerialSingleThreadExecutor("DatabaseReader")
    private val mNotificationExecutor: SerialSingleThreadExecutor =
        SerialSingleThreadExecutor("NotificationExecutor")
    private val mRosterSyncTaskManager: ReplacingTaskManager = ReplacingTaskManager()
    private val mBinder: IBinder = XmppConnectionBinder(this)
    private val conversationList: MutableList<ConversationRef> = CopyOnWriteArrayList()
    private val mIqGenerator: IqGenerator = IqGenerator(this)
    private val mLowPingTimeoutMode: HashSet<Jid> = HashSet()
    private val mDefaultIqHandler: Consumer<Iq> =
        Consumer<Iq> { packet ->
            if (packet.getType() != Iq.Type.RESULT) {
                val error = packet.getError()
                val text = if (error != null) error.findChildContent("text") else null
                if (text != null) {
                    Log.d(Config.LOGTAG, "received iq error: " + text)
                }
            }
        }

    // Tulkki: the backend slot and the ready/error state moved to `DatabaseReadiness`
    //. The slot is nullable there, exactly as the Java field was:
    // null before `continueAfterDbInit` installs the opened backend and null again after a wrong
    // key, and this getter must not turn that state into a throw.
    //
    // **The conversion's one getter decision.** In Java this method was deliberately **unannotated**
    // - not `@NonNull`, not `@Nullable` - so Kotlin read it as the platform type
    // `DatabaseBackendRef!`: a legal bare dereference at the island sites *and* a legal null check at
    // the three that guard. A Kotlin declaration cannot be a platform type, so one of the two
    // spellings had to be given up. The count decides it: **115 property sites (`service.databaseBackend`)
    // against 2 function sites**, so this is a `val` and the property spelling survives; its type is
    // the honest `DatabaseBackendRef?`, because `DatabaseReadiness.backend()` answers null before the
    // open and after a wrong key. Every bare dereference that the Java would have NPE'd on now says so
    // in as many words (`?: throw NullPointerException("database backend is not open")`), and the
    // null-safe readers (`MessageExpiry.expireOldMessages`'s early return, `PresencePreferences`,
    // `ServiceDiscovery`) keep their check. Java callers still write `getDatabaseBackend()`.
    val databaseBackend: DatabaseBackendRef?
        get() = DatabaseReadiness.backend()

    /** Replace the live backend after a key migration. The only intended outside write. */
    fun swapDatabaseBackend(backend: DatabaseBackendRef) {
        DatabaseReadiness.swap(backend)
    }

    /** What the old nullable field's {@code != null} asked; chunk C02's "no backend yet". */
    fun hasDatabaseBackend(): Boolean = DatabaseReadiness.hasBackend()

    fun getCriticalError(): EncryptionException? = DatabaseReadiness.criticalError()

    fun needsPassword(): Boolean = DatabaseReadiness.needsPassword()

    fun isDatabaseReady(): Boolean = DatabaseReadiness.isReady()

    /** True while the background DB-init thread is running (not yet ready, no error yet). */
    fun isInitializing(): Boolean = DatabaseReadiness.isInitializing()

    /**
     * Run {@code r} on the main thread once the database is ready (or in an error/needs-password
     * state). If already in a terminal state, {@code r} is posted immediately.
     * Must be called from the main thread.
     */
    fun runWhenDatabaseReady(r: Runnable) {
        DatabaseReadiness.runWhenReady(r, mLiveLocationHandler::post)
    }

    // Tulkki: `mutedMucUsers` lives in `MucMuting` now, and the
    // contact-merger executor lives in `RosterSync` (chunk C28).
    private val mStickerScanExecutor: ReplacingSerialSingleThreadExecutor =
        ReplacingSerialSingleThreadExecutor("StickerScan")
    private var mLastActivity: Long = 0
    private var mScheduledMessages: MutableMap<String, MessageRef> = HashMap()
    private val mOutgoingLiveSessions: ConcurrentHashMap<String, LiveLocation.OutgoingLiveInfo> =
        ConcurrentHashMap()
    private val mLiveLocationHandler: Handler = Handler(Looper.getMainLooper())
    private val appSettings: AppSettingsRef = dataStatics().settings(this)
    // Tulkki: C5-R1 - the island's view, built by the composition root. `FileBackend` needs this
    // service at construction and the island may not name it, so the construction (and the install
    // of `:data`'s first-party holder, which is what `FileBackends.get()` hands back) happen in one
    // body in `DataStaticsHost`.
    private val fileBackend: FileBackendRef = dataStatics().newFileBackend(this)
    // Pair 4: held `:app` instances, assigned from the factory in `onCreate` (see the note above
    // `mAvatarService`).
    private var mNotificationService: NotificationPort? = null
    private var unifiedPushBroker: UnifiedPushPort? = null
    private var mChannelDiscoveryService: ChannelDiscoveryPort? = null
    private var mShortcutService: ShortcutPort? = null
    private val mOngoingVideoTranscoding: AtomicBoolean = AtomicBoolean(false)
    private val mForceDuringOnCreate: AtomicBoolean = AtomicBoolean(false)
    private val ongoingCall: AtomicReference<OngoingCall> = AtomicReference()
    private val mMessageGenerator: MessageGenerator = MessageGenerator(this)
    // Tulkki: the body moved to `AccountStatusListener`. Only the
    // private `sendUnsentMessages` is reached from here, as the bound `Consumer` the old lambda
    // called; `find` and `fetchStories` are public service members.
    @JvmField
    var onContactStatusChanged: OnContactStatusChanged =
        object : OnContactStatusChanged {
            override fun onContactStatusChanged(contactRef: ContactRef, online: Boolean) {
                AccountStatusListener.onContactStatusChanged(
                    this@XmppConnectionService,
                    contactRef,
                    online,
                    this@XmppConnectionService::sendUnsentMessages,
                )
            }
        }
    private val mPresenceGenerator: PresenceGenerator = PresenceGenerator(this)
    private val mJingleConnectionManager: JingleConnectionManager =
        JingleConnectionManager(this)
    private val mHttpConnectionManager: HttpConnectionManager = HttpConnectionManager(this)
    // Pair 4: these are `:app`'s objects. This file may not name their classes, so the composition
    // root's factory builds them (in `onCreate`, beside the other ports) and the accessors below
    // fail loudly while a slot is empty.
    private var mAvatarService: AvatarPort? = null
    private val mMessageArchiveService: MessageArchiveService = MessageArchiveService(this)
    private var mPushManagementService: PushManagementPort? = null
    private var contactListSyncService: ContactListSyncPort? = null
    private val mOnMessageAcknowledgedListener: OnMessageAcknowledged =
        object : OnMessageAcknowledged {
            override fun onMessageAcknowledged(
                accountRef: AccountRef,
                to: Jid,
                id: String,
            ): Boolean {
                val account = accountRef
                if (id.startsWith(AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    val sessionId =
                        id.substring(
                            AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length)
                    mJingleConnectionManager.updateProposedSessionDiscovered(
                        account,
                        to,
                        sessionId,
                        JingleConnectionManager.DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED)
                }

                val bare = to.asBareJid()

                for (conversation in getConversationList()) {
                    if (conversation.getAccount() === account &&
                        (conversation.getJid()
                            ?: throw NullPointerException("conversation has no jid"))
                            .asBareJid() == bare) {
                        val message = conversation.findUnsentMessageWithUuid(id)
                        if (message != null) {
                            message.setStatus(MessageRef.STATUS_SEND)
                            message.setErrorMessage(null)
                            (databaseBackend ?: throw NullPointerException("database backend is not open"))
                                .updateMessage(message, false)
                            return true
                        }
                    }
                }
                return false
            }
        }

    private val diallerIntegrationActive: AtomicBoolean = AtomicBoolean(false)

    fun setDiallerIntegrationActive(active: Boolean) {
        diallerIntegrationActive.set(active)
    }

    // Ui callback listeners
    private val mOnConversationUpdates: MutableSet<OnConversationUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnConversationUpdate, Boolean>())
    private val mOnShowErrorToasts: MutableSet<OnShowErrorToast> =
        Collections.newSetFromMap(WeakHashMap<OnShowErrorToast, Boolean>())
    private val mOnAccountUpdates: MutableSet<OnAccountUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnAccountUpdate, Boolean>())
    private val mOnCaptchaRequested: MutableSet<OnCaptchaRequested> =
        Collections.newSetFromMap(WeakHashMap<OnCaptchaRequested, Boolean>())
    private val mOnRosterUpdates: MutableSet<OnRosterUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnRosterUpdate, Boolean>())
    private val mOnUpdateBlocklist: MutableSet<OnUpdateBlocklist> =
        Collections.newSetFromMap(WeakHashMap<OnUpdateBlocklist, Boolean>())
    private val mOnMucRosterUpdate: MutableSet<OnMucRosterUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnMucRosterUpdate, Boolean>())
    private val mOnKeyStatusUpdated: MutableSet<OnKeyStatusUpdated> =
        Collections.newSetFromMap(WeakHashMap<OnKeyStatusUpdated, Boolean>())
    private val onJingleRtpConnectionUpdate: MutableSet<OnJingleRtpConnectionUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnJingleRtpConnectionUpdate, Boolean>())

    private val LISTENER_LOCK: Any = Any()

    @JvmField
    val FILENAMES_TO_IGNORE_DELETION: MutableSet<String> = HashSet()

    // Tulkki: the body moved to `AccountStatusListener`. The field
    // keeps its name and its `OnStatusChanged` type because C13's `processAccountState` and C24's
    // `ConnectionScheduling` read it; the low-ping set and the ping executor travel by value, the
    // contact-list port lazily (it is assigned after this field), and every private reach is the
    // bound function the old body called. Master's `shuttingDown` guard (`03165687d6`) is read from
    // the listener through the `BooleanSupplier` below, so the delayed aggressive-reconnect ping
    // keeps the check-then-schedule tolerance it needs on a connection thread: the flag is the
    // service's own private `volatile` field, read lazily because it flips in `onDestroy`, and no
    // visibility is widened.
    private val statusListener: OnStatusChanged =
        AccountStatusListener(
            this,
            mLowPingTimeoutMode,
            { contactListSyncService ?: throw NullPointerException("the contact-list sync port is not installed") },
            this::sendUnsentMessages,
            { account -> reconnectAccount(account, true, false) },
            this::manageAccountConnectionStatesInternal,
            this::hasJingleRtpConnection,
            this::sendLiveLocationStopForOrphanedSessions,
            internalPingExecutor,
            { shuttingDown },
        )
    private var pgpServiceConnection: OpenPgpServiceConnection? = null
    private var pgpEngineFactory: PgpEngineFactory? = null
    private var trustPort: TrustPort? = null
    private var omemoSettings: OmemoSettingsPort? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mDrawableCache: LruCache<String, Drawable>? = null
    private val mInternalEventReceiver: BroadcastReceiver = InternalEventReceiver(this)
    private val mInternalRestrictedEventReceiver: BroadcastReceiver =
        RestrictedEventReceiver(this, listOf(TorServiceUtils.ACTION_STATUS))
    private val mInternalScreenEventReceiver: BroadcastReceiver = InternalEventReceiver(this)

private fun isInLowPingTimeoutMode(account: AccountRef): Boolean = ServiceSeams.isInLowPingTimeoutMode(account, mLowPingTimeoutMode)

fun startOngoingVideoTranscodingForegroundNotification() = ServiceSeams.startOngoingVideoTranscodingForegroundNotification(
                mOngoingVideoTranscoding, this::toggleForegroundService)

fun stopOngoingVideoTranscodingForegroundNotification() = ServiceSeams.stopOngoingVideoTranscodingForegroundNotification(
                mOngoingVideoTranscoding, this::toggleForegroundService)

fun areMessagesInitialized(): Boolean = ServiceSeams.areMessagesInitialized(this.restoredFromDatabaseLatch)

fun copyAttachmentToDownloadsFolder(m: MessageRef, callback: UiCallbackPort<Int>?) = ServiceSeams.copyAttachmentToDownloadsFolder(m, callback, fileBackend)

fun copyAttachmentToDownloadsFolder(file: File, callback: UiCallbackPort<Int>?) = ServiceSeams.copyAttachmentToDownloadsFolder(file, callback, fileBackend)

/**
 * Tulkki: the seam's own null contract, on the accessor itself - the Java answered null here too.
 * "No OpenKeychain installed" is the ordinary answer, so an availability check reads this and
 * answers false; a call site that needs an engine says so where it dereferences.
 */
fun getPgpEngine(): PgpEnginePort? =
        ServiceSeams.getPgpEngine(this, pgpServiceConnection, pgpEngineFactory)

fun getOpenPgpApi(): OpenPgpApi? = ServiceSeams.getOpenPgpApi(this, pgpServiceConnection)

fun getAppSettings(): AppSettingsRef = ServiceSeams.getAppSettings(this.appSettings)

    /**
     * Tulkki: C5-R1 - the island's view of the file backend, which is all the island may name.
     * First-party code that needs the model asks `:data`'s own first-party holder, whose object this
     * is. The declaration is kept rather than folded into that holder: 48 island sites reach the
     * instance through this accessor and 13 more through the field.
     */
fun getFileBackend(): FileBackendRef = ServiceSeams.getFileBackend(this.fileBackend)

    // Tulkki: C5-E3 - the answer is the island's view of the file, so this method no longer makes
    // the island name `:data`'s model. `DatabaseBackend.getFileForCid` is deliberately NOT retyped:
    // it is `:data`'s, and a `DownloadableFile` is a `DownloadableFileRef`, so the return still
    // type-checks. Every caller outside the island binds the ref and reaches the JDK `File` half,
    // where it genuinely needs a `File`, through `asFile()`. The return widened to nullable in
    // lane E's kt5-typing commit to follow `ServiceSeams.getFileForCid` (lane G): `UploadStore`
    // answers null for an unknown cid and every caller null-checks.
fun getFileForCid(cid: Cid?): DownloadableFileRef? = ServiceSeams.getFileForCid(cid ?: throw NullPointerException("no cid"), ( databaseBackend ?: throw NullPointerException("database backend is not open") ))

fun getUrlForCid(cid: Cid?): String? = ServiceSeams.getUrlForCid(cid ?: throw NullPointerException("no cid"), ( databaseBackend ?: throw NullPointerException("database backend is not open") ))

fun saveCid(cid: Cid?, file: File) = ServiceSeams.saveCid(cid ?: throw NullPointerException("no cid"), file, ( databaseBackend ?: throw NullPointerException("database backend is not open") ))

fun saveCid(cid: Cid?, file: File, url: String?) = ServiceSeams.saveCid(cid ?: throw NullPointerException("no cid"), file, url, ( databaseBackend ?: throw NullPointerException("database backend is not open") ))

    // Tulkki: the bodies moved to `MucMuting`; the names, the
    // guard order, the nullable account uuid and the NUL-joined cache key are unchanged. The
    // reload `restoreFromDatabase` used to do inline now calls `MucMuting.reloadMutedMucUsers`.
fun muteMucUser(user: MucOptionsRef.UserRef): Boolean = MucMuting.muteMucUser(this, user)

fun unmuteMucUser(user: MucOptionsRef.UserRef): Boolean = MucMuting.unmuteMucUser(this, user)

    /** The roster form: the participant carries the conversation it was read from. */
fun isMucUserMuted(user: MucOptionsRef.UserRef): Boolean = MucMuting.isMucUserMuted(user)

    /**
     * S5-12: the form a caller with only a message in hand uses, and the one the mute cache is
     * keyed by. The account is a parameter because the pair is what a mute is - two of the owner's
     * accounts in one room are two accounts, and the bare JID alone made them one.
     */
fun isMucUserMuted(accountUuid: String?, mucJid: String, occupantId: String?): Boolean = MucMuting.isMucUserMutedForAccount(accountUuid, mucJid, occupantId)

    // Tulkki: the bodies moved to `BlockedMedia`; the names, the
    // signatures and the swallow-everything guard are unchanged.
fun blockMedia(f: File) = BlockedMedia.blockMedia(this, f)

fun blockMedia(cid: Cid) = BlockedMedia.blockMedia(this, cid)

fun clearBlockedMedia() = BlockedMedia.clearBlockedMedia(this)

fun getMessage(conversation: ConversationRef, uuid: String?): MessageRef? = BlockedMedia.getMessage(this, conversation, uuid)

fun getMessageFuzzyIds(conversation: ConversationRef, ids: Collection<String>): Map<String, MessageRef> = BlockedMedia.getMessageFuzzyIds(this, conversation, ids)

    // Tulkki: the getter moved to `BlockedMedia` (chunk C07) too. Its slot is C70's private field and
    // its guard is C76's private `require`, so the service resolves both here and hands the non-null
    // port in: neither visibility is widened for the move.
fun getAvatarService(): AvatarPort = BlockedMedia.avatarService(PortAccessors.require(this.mAvatarService, "avatar"))

    // Tulkki: the bodies moved to `LiveLocation`. The session map
    // and the main-looper handler stay service fields - C23's foreground decision reads the map and
    // C02/C19/C48 post through the handler - so they travel by value; C70's nullable
    // `mNotificationService` travels nullable, keeping the Java's bare-field NPE at its own line.
fun attachLocationToConversation(conversation: ConversationRef, uri: Uri, subject: String?, callback: UiCallbackPort<MessageRef>) = LiveLocation.attachLocationToConversation(this, conversation, uri, subject, callback)

fun startLiveLocationSharing(conversation: ConversationRef, durationMs: Long, initialLat: Double, initialLon: Double, initialAccuracy: Float) = LiveLocation.startLiveLocationSharing(
                this, mOutgoingLiveSessions, mLiveLocationHandler, mNotificationService,
                conversation, durationMs, initialLat, initialLon, initialAccuracy)

fun stopLiveLocationSharing(conversationUuid: String) = LiveLocation.stopLiveLocationSharing(
                this, mOutgoingLiveSessions, mLiveLocationHandler, mNotificationService, conversationUuid)

private fun sendLiveLocationStopForOrphanedSessions(account: AccountRef) = LiveLocation.sendLiveLocationStopForOrphanedSessions(this, mOutgoingLiveSessions, account)
fun attachFileToConversation(conversation: ConversationRef, uri: Uri, type: String?, subject: String?, callback: UiCallbackPort<MessageRef>) = AttachmentSending.attachFileToConversation(
            this,
            conversation,
            uri,
            type,
            subject,
            callback,
            attachFile(),
            FILE_ATTACHMENT_EXECUTOR,
            VIDEO_COMPRESSION_EXECUTOR,
            this::sendMessage)

fun attachImageToConversation(conversation: ConversationRef, uri: Uri, type: String?, subject: String?, callback: UiCallbackPort<MessageRef>) = AttachmentSending.attachImageToConversation(
            this,
            conversation,
            uri,
            type,
            subject,
            callback,
            attachFile(),
            FILE_ATTACHMENT_EXECUTOR,
            VIDEO_COMPRESSION_EXECUTOR,
            this::sendMessage)

    // Tulkki: the body moved to CacheHousekeeping; the name,
    // the preference flag and the one-shot behaviour are unchanged.
private fun migrateCacheToInternalStorage() = CacheHousekeeping.migrateCacheToInternalStorage(this)

    // Tulkki: the body moved to CacheHousekeeping; the name,
    // the guard order and the executor it runs on are unchanged.
protected fun cleanupTemporaryStorage() = CacheHousekeeping.cleanupTemporaryStorage(this, mStickerScanExecutor)

    // Tulkki: the bodies moved to `UiConversationLookup`; the
    // names, signatures and null contracts are unchanged. C76's private `require`/`messageSearch()`
    // stays on this side and is resolved here; C31a's private four-argument `find` goes in as the
    // `CounterpartFinder` method reference.
fun find(bookmark: BookmarkRef): ConversationRef? = UiConversationLookup.findByBookmark(this, bookmark)

fun find(accountRef: AccountRef?, jid: Jid?): ConversationRef? = UiConversationLookup.findByJid(this, accountRef, jid)

fun find(accountRef: AccountRef?, jid: Jid?, counterpart: Jid?): ConversationRef? = UiConversationLookup.findByCounterpart(this, accountRef, jid, counterpart, this::find)

fun isMuc(accountRef: AccountRef?, jid: Jid?): Boolean = UiConversationLookup.isMuc(this, accountRef, jid)

fun search(term: List<String>, uuid: String?, onSearchResultsAvailable: SearchResultsHook) = UiConversationLookup.search(
                messageSearch(), this, term, uuid, onSearchResultsAvailable)

    // Tulkki: the body moved to `StartCommand`. Nothing but the
    // body: every private reach travels in as the bound function the switch called, C70's nullable
    // `mNotificationService` travels nullable, and the `final` fields (the file backend, the jingle
    // manager, both executors) travel by value. Master's `shuttingDown` guard (`03165687d6`) travels
    // in as the `BooleanSupplier` below, so the tail `internalPingExecutor.execute` catch keeps
    // rethrowing a rejection that is not the teardown.
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = StartCommand.onStartCommand(
                this,
                intent,
                fileBackend,
                this::systemEvent,
                { force, needMic -> toggleForegroundService(force, needMic) },
                { contactListSyncService ?: throw NullPointerException("the contact-list sync port is not installed") },
                this::contactListSync,
                this::schedulePostConnectivityChange,
                this::resetAllAttemptCounts,
                this::logoutAndSave,
                mNotificationExecutor,
                mNotificationService,
                mJingleConnectionManager,
                this::handleOrbotStartedEvent,
                this::provisionAccount,
                this::dismissErrorNotifications,
                this::directReply,
                this::dndOnSilentMode,
                this::deactivateGracePeriod,
                this::awayWhenScreenLocked,
                this::refreshAllFcmTokens,
                { unifiedPushBroker ?: throw NullPointerException("the unified-push port is not installed") },
                this::renewUnifiedPushEndpoints,
                this::scheduleNextIdlePing,
                this::expireOldMessages,
                this::quickLog,
                { toggleSoftDisabled(true) },
                this::sendScheduledMessages,
                internalPingExecutor,
                this::manageAccountConnectionStates,
                { shuttingDown })

    // Tulkki: the bodies moved to `AccountConnectionStates`.
    // C76's private `wakeLock()`/`phoneHelper()` go in as the `Supplier`s they are, so their
    // `require` still throws where the Java threw; C45's private `reconnectAccount` is bound to the
    // `true` its call sites passed; C01's private `ACTION_POST_CONNECTIVITY_CHANGE` travels as the
    // string it is. The Java's `synchronized` on the service instance is the Kotlin's
    // `synchronized(service)`.
private fun quickLog(message: String) = AccountConnectionStates.quickLog(this, message)

private fun manageAccountConnectionStatesInternal() = AccountConnectionStates.manageAccountConnectionStatesInternal(
                this, this::wakeLock, this.wakeLock
                    ?: throw NullPointerException("the wake lock is not installed"), this::phoneHelper, mLowPingTimeoutMode,
                statusListener, { a, i -> reconnectAccount(a, true, i) },
                this::hasJingleRtpConnection, ACTION_POST_CONNECTIVITY_CHANGE)

private fun manageAccountConnectionStates(action: String, extras: Bundle?) = AccountConnectionStates.manageAccountConnectionStates(
                this, this::wakeLock, this.wakeLock
                    ?: throw NullPointerException("the wake lock is not installed"), this::phoneHelper, mLowPingTimeoutMode,
                statusListener, { a, i -> reconnectAccount(a, true, i) },
                this::hasJingleRtpConnection, ACTION_POST_CONNECTIVITY_CHANGE, action, extras)

private fun sendScheduledMessages() = AccountConnectionStates.sendScheduledMessages(this, mScheduledMessages)

private fun handleOrbotStartedEvent() = AccountConnectionStates.handleOrbotStartedEvent(
                this, { a, i -> reconnectAccount(a, true, i) })

    // Tulkki: the bodies moved to `PushReply`; the names, the
    // visibility, the signatures and the guard order are unchanged. The push broker is reached
    // through its public getter; the channel-discovery port, the compatibility port and the
    // interpreter's two seams go in by hand, so no private visibility is widened.
private fun toggleSoftDisabled(softDisabled: Boolean) = PushReply.toggleSoftDisabled(this, softDisabled)

fun processUnifiedPushMessage(accountRef: AccountRef, transport: Jid?, push: Element): Boolean =
        PushReply.processUnifiedPushMessage(
            this,
            accountRef,
            transport ?: throw NullPointerException("no push transport"),
            push)

fun reinitializeMuclumbusService() = PushReply.reinitializeMuclumbusService(
            mChannelDiscoveryService ?: throw NullPointerException("the channel-discovery port is not installed"))

fun isDataSaverDisabled(): Boolean = PushReply.isDataSaverDisabled(this, compatibility())

fun getMessagesCountGroupByDay(conversationUuid: String, year: Int, month: Int): Map<Int, Int> = PushReply.getMessagesCountGroupByDay(this, conversationUuid, year, month)

private fun directReply(conversation: ConversationRef, body: String, lastMessageUuid: String?, dismissAfterReply: Boolean) = PushReply.directReply(
                this, conversation, body, lastMessageUuid, dismissAfterReply, sendGate(), tulkkiPorts())

    // Tulkki: the bodies moved to `PresencePreferences`; the
    // names, the visibility, the signatures and the guard order are unchanged. The database-writer
    // executor (chunk C02) and the notification port (chunk C70) are private state of other groups
    // and are passed in, so neither visibility is widened.
private fun dndOnSilentMode(): Boolean = PresencePreferences.dndOnSilentMode(this)

private fun manuallyChangePresence(): Boolean = PresencePreferences.manuallyChangePresence(this)

private fun treatVibrateAsSilent(): Boolean = PresencePreferences.treatVibrateAsSilent(this)

private fun awayWhenScreenLocked(): Boolean = PresencePreferences.awayWhenScreenLocked(this)

private fun getCompressPicturesPreference(): String? = PresencePreferences.getCompressPicturesPreference(this)

private fun getTargetPresence(): PresenceRef.StatusRef = PresencePreferences.getTargetPresence(this)

fun isScreenLocked(): Boolean = PresencePreferences.isScreenLocked(this)

private fun isPhoneSilenced(): Boolean = PresencePreferences.isPhoneSilenced(this)

private fun resetAllAttemptCounts(reallyAll: Boolean, retryImmediately: Boolean) = PresencePreferences.resetAllAttemptCounts(
                this, reallyAll, retryImmediately, mDatabaseWriterExecutor,
                mNotificationService ?: throw NullPointerException("the notification port is not installed"))

private fun dismissErrorNotifications() = PresencePreferences.dismissErrorNotifications(this, mDatabaseWriterExecutor)

private fun expireOldMessages() = expireOldMessages(false)

    // Tulkki: the body moved to `MessageExpiry`; the visibility,
    // the guard order and the executors are unchanged.
fun expireOldMessages(resetHasMessagesLeftOnServer: Boolean) = MessageExpiry.expireOldMessages(
                this,
                resetHasMessagesLeftOnServer,
                mDatabaseWriterExecutor,
                this::deleteFilesAsync)

    // Tulkki: the body moved to `MessageExpiry`; the backend
    // capture stays outside the writer task and the null check stays inside it, as the Java had them.
fun scheduleNextExpiry() = MessageExpiry.scheduleNextExpiry(
                this,
                mDatabaseWriterExecutor,
                systemEvent().receiverClass())

    // Tulkki: the body moved to `DeviceNetworkState`; the
    // signature, the fail-open answer and the thread it runs on are unchanged.
fun hasInternetConnection(): Boolean = DeviceNetworkState.hasInternetConnection(this)

// Tulkki: the body moved to `ServiceConstruction`. Every
        // field it writes belongs to C74, so the assignments stay a private method here and travel in
        // as the `Consumer`; the Java's private accessors travel as the `Supplier`s they are, so
        // their `require` still throws at the same first use when no factory was installed.
@SuppressLint("TrulyRandom")
override fun onCreate() = ServiceConstruction.onCreate(
                this, tulkkiPortsFactory, this::installTulkkiPorts,
                this::liveLocationHook, this::tulkkiPorts, this::themePort, this::compatibility,
                this::omemoSettings, mForceDuringOnCreate, { mNotificationService ?: throw NullPointerException("the notification port is not installed") },
                { mChannelDiscoveryService ?: throw NullPointerException("the channel-discovery port is not installed") }, { v -> this.mDrawableCache = v },
                { mLastActivity }, { v -> this.mLastActivity = v },
                this::initializeDatabaseInBackground, SETTING_LAST_ACTIVITY_TS)

    /**
     * C18: the port installation `onCreate` performed from the composition root's factory. It stays
     * here because every field it writes belongs to C74 and is read by chunks that have not moved;
     * the Kotlin home calls it once with the factory's answer, so no slot was widened.
     */
private fun installTulkkiPorts(tulkkiPorts: TulkkiPorts) {
        this.sendGate = tulkkiPorts.sendGate()
        this.translationQueue = tulkkiPorts.translationQueue()
        // S5-4: the sync engine's two seams, from the same factory and at the same moment. The
        // engine is installed before the first catch-up can run, which is what makes the ledger's
        // "persist first, then query" order hold.
        this.mMessageArchiveService.installSyncEvents(tulkkiPorts.syncEvents())
        this.mMessageArchiveService.installSyncAnchors(tulkkiPorts.syncAnchors())
        this.syncEvents = tulkkiPorts.syncEvents()
        // Pair 7's three crypto-side handles, taken from the same factory. The trust port is
        // also put in the static slot, because two of its five callers (Config's contact-domain
        // check, and HttpConnectionManager's static okHttpClient) have no service to ask.
        this.trustPort = tulkkiPorts.trustPort()
        installTrustPort(this.trustPort ?: throw NullPointerException("the trust port is not installed"))
        this.pgpEngineFactory = tulkkiPorts.pgpEngineFactory()
        this.omemoSettings = tulkkiPorts.omemoSetting()
        // Pair 11's four handles, from the same factory and for the same reason: each is a `:ui`
        // thing this island asks for rather than names. They are taken here, beside the others,
        // and the accessors below name the install point when a slot is empty.
        this.liveLocationHook = tulkkiPorts.liveLocationHook()
        this.rtpSessionPort = tulkkiPorts.rtpSessionPort()
        this.themePort = tulkkiPorts.themePort()
        this.profilePictureActivityPort = tulkkiPorts.profilePictureActivityPort()
        this.decisionScreenPort = tulkkiPorts.decisionScreenPort()
        // Pair 4, from the same factory and for the same reason: every one of these is an `:app`
        // object this island holds or asks for rather than names. The held ones are built here,
        // once, because this file cannot construct them - the avatar cache has to exist before
        // the first parser asks it for a drawable, and the notification service before
        // `toggleForegroundService` below.
        this.compatibility = tulkkiPorts.compatibility()
        this.phoneHelper = tulkkiPorts.phoneHelper()
        this.wakeLockPort = tulkkiPorts.wakeLock()
        this.transcoderStrategies = tulkkiPorts.transcoderStrategies()
        this.systemEvent = tulkkiPorts.systemEvent()
        this.messageSearch = tulkkiPorts.messageSearch()
        this.contactListSync = tulkkiPorts.contactListSync()
        this.attachFile = tulkkiPorts.attachFile()
        this.avatar = tulkkiPorts.avatar()
        this.notification = tulkkiPorts.notification()
        this.channelDiscovery = tulkkiPorts.channelDiscovery()
        this.shortcuts = tulkkiPorts.shortcuts()
        this.unifiedPush = tulkkiPorts.unifiedPush()
        this.pushManagement = tulkkiPorts.pushManagement()
        // The fields the rest of this file already read by their old names.
        this.mAvatarService = this.avatar
        this.mNotificationService = this.notification
        this.mChannelDiscoveryService = this.channelDiscovery
        this.mShortcutService = this.shortcuts
        this.unifiedPushBroker = this.unifiedPush
        this.mPushManagementService = this.pushManagement
        this.contactListSyncService = this.contactListSync
        this.fileWatcher = tulkkiPorts.fileObserver().create(
            Environment.getExternalStorageDirectory().getAbsolutePath(),
            this::markFileDeleted)
        this.tulkkiPorts = tulkkiPorts
    }

    /**
     * Opens the encrypted database on a background thread to avoid blocking the main thread with
     * the AndroidKeyStore key setup and SQLCipher Argon2id KDF. Posts back to the main
     * thread when done (success or failure).
     */
private fun initializeDatabaseInBackground() = DatabaseInitialization.initialize(
                this,
                dataStatics(),
                mLiveLocationHandler,
                { DatabaseReadiness.setNeedsPassword(true) },
                { e -> DatabaseReadiness.setCriticalError(e) },
                DatabaseReadiness::notifyReadyCallbacks,
                this::continueAfterDbInit)

    // Tulkki: the head moved to `PostOpenStartup` - the
    // backend install, the account colours, the enabled-accounts setting, the profile-picture toggle,
    // the push distributor, call integration and the restore with its wrong-key catch. The private
    // `hasEnabledAccounts` and `restoreFromDatabase`, and C76's private `systemEvent`, travel in as
    // bound values, so nothing is widened; the Kotlin home keeps the Java's guard order. The wrong-key
    // path is terminal there and answers false, so this method still returns before the post-restore
    // wiring and the scheduling tail - that ordering is behaviour, not structure.
private fun continueAfterDbInit(backend: DatabaseBackendRef) {
        // Tulkki: the head moved to `PostOpenStartup` - the
        // backend install, the account colours, the enabled-accounts setting, the profile-picture toggle,
        // the push distributor, call integration and the restore with its wrong-key catch. The private
        // `hasEnabledAccounts` and `restoreFromDatabase`, and C76's private `systemEvent`, travel in as
        // bound values, so nothing is widened; the Kotlin home keeps the Java's guard order. The wrong-key
        // path is terminal there and answers false, so this method still returns before the post-restore
        // wiring and the scheduling tail - that ordering is behaviour, not structure.
        if (!PostOpenStartup.run(
                this,
                backend,
                systemEvent(),
                this::hasEnabledAccounts,
                this::toggleSetProfilePictureActivity,
                this::restoreFromDatabase)) {
            return
        }

        // Tulkki: the post-restore wiring moved to `PostRestoreWiring` - the contact and file observers, OpenPGP's `OnBound`, the power manager, the
        // foreground/badge/screen handling, both internal receivers with their filters, and the two
        // cache sweeps. The head above is `PostOpenStartup` (slice 3). The two ports, the
        // file-observer executor, the watcher, the two receivers, the private `checkForDeletedFiles`,
        // `scheduleNextIdlePing` and `migrateCacheToInternalStorage`, and the `protected`
        // `cleanupTemporaryStorage` all travel in as bound values, so nothing is widened; the Java's
        // guard order is kept in the Kotlin home. The "Tulkki's pass over what arrived while the app
        // was away" paragraph that stood here now sits verbatim in `PostRestoreWiring`'s KDoc, with
        // the code it explains.
        PostRestoreWiring.start(
            this,
            contactListSync(),
            compatibility(),
            FILE_OBSERVER_EXECUTOR,
            this.fileWatcher ?: throw NullPointerException("the file observer is not installed"),
            this::checkForDeletedFiles,
            { connection -> this.pgpServiceConnection = connection },
            { lock -> this.wakeLock = lock },
            mForceDuringOnCreate,
            this.mInternalEventReceiver,
            this.mInternalRestrictedEventReceiver,
            this::scheduleNextIdlePing,
            this::migrateCacheToInternalStorage,
            this::cleanupTemporaryStorage,
        )

        // Tulkki: the scheduling tail moved to `StartupScheduling` - the three background pools, the KEEP_FOREGROUND_SERVICE listener, and the ready
        // flag with its callbacks. The private `manageAccountConnectionStatesInternal` (C13), the
        // two story passes (C62) and the volatile `shuttingDown` travel in as bound values, so
        // nothing was widened and the Java's guard order is kept in the Kotlin home.
        StartupScheduling.startBackgroundTasks(
            this,
            { shuttingDown },
            internalPingExecutor,
            storyRetractionExecutor,
            storyCacheExecutor,
            this::manageAccountConnectionStatesInternal,
        )
    }

    // Tulkki: the body moved to `DeletedFileCheck`; the
    // non-volatile `destroyed` flag (C22) and the private `markChangedFiles` (C29) are passed in, so
    // neither visibility is widened. `FILENAMES_TO_IGNORE_DELETION` stays here - `:data`'s
    // CryptoStore names it and this sweep never reads it.
private fun checkForDeletedFiles() = DeletedFileCheck.checkForDeletedFiles(this, { Teardown.isDestroyed() }, this::markChangedFiles)

    // Tulkki: the bodies moved to `Teardown`; the names, the
    // visibility, the signatures and the guard order are unchanged, and the `super` calls stay on
    // this side of the seam. The non-volatile `destroyed` flag moved with the chunk.
fun startContactObserver() = Teardown.startContactObserver(this, restoredFromDatabaseLatch)

override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Teardown.onTrimMemory(
            getDrawableCache() ?: throw NullPointerException("the drawable cache is not built yet"),
            level)
    }

override fun onDestroy() {
        // Mark first, then tear down. A `db-init` continuation or a status-listener ping that is
        // already in flight must see this before `Teardown.onDestroy` terminates the pool it would
        // schedule on; otherwise it schedules on a terminated pool and crashes with
        // `RejectedExecutionException` ("completed tasks = 0" - the pool never ran anything).
        shuttingDown = true
        Teardown.onDestroy(
            this,
            this.mInternalEventReceiver,
            this.mInternalRestrictedEventReceiver,
            this.mInternalScreenEventReceiver,
            this.mNotificationService
                ?: throw NullPointerException("the notification port is not installed"),
            this.fileWatcher ?: throw NullPointerException("the file observer is not installed"),
            internalPingExecutor,
            storyRetractionExecutor,
            storyCacheExecutor,
        )
        super.onDestroy()
    }

fun restartFileObserver() = Teardown.restartFileObserver(
                FILE_OBSERVER_EXECUTOR,
                this.fileWatcher ?: throw NullPointerException("the file observer is not installed"),
                this::checkForDeletedFiles)

fun toggleScreenEventReceiver() = Teardown.toggleScreenEventReceiver(this, this.mInternalScreenEventReceiver)

fun toggleForegroundService() = toggleForegroundService(false, false)

fun setOngoingCall(id: AbstractJingleConnection.Id, media: MutableSet<Media>, reconnecting: Boolean) = Teardown.setOngoingCall(
                ongoingCall, id, media, reconnecting, { toggleForegroundService(false, true) })

    // Tulkki: the bodies moved to `ForegroundServiceLifecycle`;
    // the names and the main-thread contract are unchanged. The chunk's three fields stay here
    // (C22's `Teardown.setOngoingCall` writes `ongoingCall`, C05's `ServiceSeams` reads the transcode
    // flag, C08 owns the live-location set) and travel in by value; `compatibility()` is resolved
    // here, and C24's private `logoutAndSave` arrives as the `Runnable` the `super` call needs.
fun removeOngoingCall() = ForegroundServiceLifecycle.removeOngoingCall(
                ongoingCall, { toggleForegroundService(false, false) })

private fun toggleForegroundService(force: Boolean, needMic: Boolean) = ForegroundServiceLifecycle.toggleForegroundService(
                this,
                force,
                needMic,
                ongoingCall,
                mOngoingVideoTranscoding,
                mOutgoingLiveSessions,
                mForceDuringOnCreate,
                compatibility(),
                mNotificationService,
                diallerIntegrationActive,
                this::hasEnabledAccounts)

fun foregroundNotificationNeedsUpdatingWhenErrorStateChanges(): Boolean = ForegroundServiceLifecycle.foregroundNotificationNeedsUpdatingWhenErrorStateChanges(
                this,
                mOngoingVideoTranscoding,
                ongoingCall,
                compatibility(),
                this::hasEnabledAccounts)

override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ForegroundServiceLifecycle.onTaskRemoved(
            this,
            mOngoingVideoTranscoding,
            ongoingCall,
            compatibility(),
            { this.logoutAndSave(false) },
            this::hasEnabledAccounts,
        )
    }

private fun logoutAndSave(stop: Boolean) = ConnectionScheduling.logoutAndSave(this, stop, this::disconnect)

private fun schedulePostConnectivityChange() = ConnectionScheduling.schedulePostConnectivityChange(
                        this, systemEvent(), compatibility(), ACTION_POST_CONNECTIVITY_CHANGE)

fun scheduleWakeUpCall(seconds: Int, requestCode: Int) = ConnectionScheduling.scheduleWakeUpCall(this, seconds, requestCode, systemEvent())

fun scheduleWakeUpCall(milliSeconds: Long, requestCode: Int) = ConnectionScheduling.scheduleWakeUpCall(this, milliSeconds, requestCode, systemEvent())

private fun scheduleNextIdlePing() = ConnectionScheduling.scheduleNextIdlePing(
                        this, mScheduledMessages, systemEvent(), compatibility())

fun createConnection(account: AccountRef): XmppConnection = ConnectionScheduling.createConnection(
                        this, account, statusListener, mOnMessageAcknowledgedListener)

    // Tulkki: the bodies moved to `SendEntryPoints`; the names,
    // the signatures and the guard order are unchanged. Chunk C25b's private six-argument
    // `sendMessage` stays here and goes in as the `OutgoingStanzaSender` method reference, so its
    // visibility is not widened and the translation hold is still the one place every send passes.
fun sendChatState(conversation: ConversationRef) = SendEntryPoints.sendChatState(this, conversation)

private fun sendFileMessage(message: MessageRef, delay: Boolean, cb: Runnable?, forceP2P: Boolean) = SendEntryPoints.sendFileMessage(this, message, delay, cb, forceP2P)

fun sendMessage(message: MessageRef) = SendEntryPoints.sendMessage(message, this::sendMessage)

fun sendMessage(message: MessageRef, cb: Runnable?) = SendEntryPoints.sendMessage(message, cb, this::sendMessage)

fun sendEphemeralImplicitNegotiation(conversation: ConversationRef, timer: Int) = SendEntryPoints.sendEphemeralImplicitNegotiation(this, conversation, timer)

fun sendEphemeralIWantOut(conversation: ConversationRef) = SendEntryPoints.sendEphemeralIWantOut(this, conversation)

private fun sendMessage(message: MessageRef, resend: Boolean, previewedLinks: Boolean, delay: Boolean, cb: Runnable?, forceP2P: Boolean) {
        // Tulkki: nothing is sent untranslated. This is the one place every outgoing stanza passes,
        // so a message that still needs a translation is held back here - the composer, a retry, a
        // reconnect and a quick reply all go through it - and picked up again on its own.
        if (sendGate().holdBack(message)) {
            return
        }
        val account = message.getConversation()?.getAccount()
            ?: throw NullPointerException("message has no conversation with an account")
        if (account.setShowErrorNotification(true)) {
            (databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            (mNotificationService
                ?: throw NullPointerException("the notification port is not installed"))
                .updateErrorNotification()
        }
        val conversation = message.getConversation() as ConversationRef
        account.deactivateGracePeriod()

        if (message.getEphemeralTimer() > 0 && message.getExpireAt() == 0L) {
            message.setExpireAt(
                System.currentTimeMillis() + message.getEphemeralTimer() * 1000L)
        }

        if (contactListSync().quicksy() &&
            conversation.getMode() == ConversationalRef.MODE_SINGLE) {
            val contact = conversation.getContact()
            if (!contact.showInRoster() &&
                contact.getOption(ContactRef.OptionsRef.SYNCED_VIA_OTHER)) {
                Log.d(
                    Config.LOGTAG,
                    "" + account.getJid().asBareJid() + ": adding " + contact.getJid() +
                        " on sending message")
                createContact(contact, true)
            }
        }

        var packet: uk.xa0.tulkki.xmpp.models.stanza.Message? = null
        val addToConversation = !message.edited() && message.getRawBody() != null
        var saveInDb = addToConversation
        message.setStatus(MessageRef.STATUS_WAITING)

        if (message.getEncryption() != MessageRef.ENCRYPTION_NONE &&
            conversation.getMode() == ConversationalRef.MODE_MULTI &&
            conversation.isPrivateAndNonAnonymous()) {
            if (conversation.setAttribute(
                    ConversationRef.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, true)) {
                (databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
            }
        }

        if (!resend && message.getEncryption() != MessageRef.ENCRYPTION_OTR) {
            conversation.endOtrIfNeeded()
            conversation.findUnsentMessagesWithEncryption(
                MessageRef.ENCRYPTION_OTR,
                object : ConversationRef.OnMessageFound {
                    override fun onMessageFound(message1: MessageRef) {
                        markMessage(message1, MessageRef.STATUS_SEND_FAILED)
                    }
                },
            )
        }

        val inProgressJoin = isJoinInProgress(conversation)

        if (message.getCounterpart() == null && !message.isPrivateMessage()) {
            message.setCounterpart(
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid())
        }

        var waitForPreview = false
        if (getPreferences().getBoolean("send_link_previews", true) && !previewedLinks &&
            !message.needsUploading() &&
            message.getEncryption() != MessageRef.ENCRYPTION_AXOLOTL) {
            message.clearLinkDescriptions()
            val links = message.getLinks()
            if (!links.isEmpty()) {
                waitForPreview = true
                if (account.isOnlineAndConnected()) {
                    FILE_ATTACHMENT_EXECUTOR.execute {
                        for (link in links) {
                            if ("https" == link.getScheme()) {
                                try {
                                    val url =
                                        link.toString().toHttpUrlOrNull()
                                            ?: throw NullPointerException(
                                                "could not parse link " + link)
                                    val http =
                                        getHttpConnectionManager()
                                            .buildHttpClient(url, account, 5, false)
                                    val request =
                                        okhttp3.Request.Builder().url(url).head().build()
                                    var response: okhttp3.Response? = null
                                    if ("www.amazon.com" == link.getHost() ||
                                        "www.amazon.ca" == link.getHost()) {
                                        // Amazon blocks HEAD
                                        response =
                                            okhttp3.Response.Builder()
                                                .request(request)
                                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                                .code(200)
                                                .message("OK")
                                                .addHeader("Content-Type", "text/html")
                                                .build()
                                    } else {
                                        response = http.newCall(request).execute()
                                    }
                                    val mimeType = response.header("Content-Type") ?: ""
                                    val image = mimeType.startsWith("image/")
                                    val audio = mimeType.startsWith("audio/")
                                    val video = mimeType.startsWith("video/")
                                    val pdf = mimeType == "application/pdf"
                                    val html =
                                        mimeType.startsWith("text/html") ||
                                            mimeType.startsWith("application/xhtml+xml")
                                    if (response.isSuccessful && (image || audio || video || pdf)) {
                                        val params = message.getFileParams()
                                        params.setUrl(url.toString())
                                        val contentLength = response.header("Content-Length")
                                        if (contentLength != null) {
                                            params.setSize(contentLength.toLong(10))
                                        }
                                        if (!dataStatics().configurePrivateFileMessage(message)) {
                                            message.setType(
                                                if (image) MessageRef.TYPE_IMAGE
                                                else MessageRef.TYPE_FILE)
                                        }
                                        params.setName(
                                            HttpConnectionManager
                                                .extractFilenameFromResponse(response))

                                        if (link.toString() == message.getRawBody()) {
                                            val fallback =
                                                Element("fallback", "urn:xmpp:fallback:0")
                                                    .setAttribute("for", Namespace.OOB)
                                            fallback.addChild("body", "urn:xmpp:fallback:0")
                                            message.addPayload(fallback)
                                        } else if ((message.getRawBody()
                                                ?: throw NullPointerException(
                                                    "message has no raw body"))
                                                .indexOf(link.toString()) >= 0) {
                                            // Part of the real body, not just a fallback
                                            val fallback =
                                                Element("fallback", "urn:xmpp:fallback:0")
                                                    .setAttribute("for", Namespace.OOB)
                                            fallback.addChild("body", "urn:xmpp:fallback:0")
                                                .setAttribute("start", "0")
                                                .setAttribute("end", "0")
                                            message.addPayload(fallback)
                                        }

                                        val encryption = message.getEncryption()
                                        getHttpConnectionManager()
                                            .createNewDownloadConnection(message, false) { _ ->
                                                message.setEncryption(encryption)
                                                synchronized(
                                                    message.getConversation()
                                                        ?: throw NullPointerException(
                                                            "message has no conversation")) {
                                                    if (message.getStatus() ==
                                                        MessageRef.STATUS_WAITING) {
                                                        sendMessage(
                                                            message, true, true, false, cb, false)
                                                    }
                                                }
                                            }
                                        return@execute
                                    } else if (response.isSuccessful && html) {
                                        val waiter = Semaphore(0)
                                        var openGraphBuilder =
                                            OpenGraphParser.Builder(
                                                object : OpenGraphCallback {
                                                    override fun onPostResponse(
                                                        result: OpenGraphResult) {
                                                        val rdf =
                                                            Element(
                                                                "Description",
                                                                "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                                                        rdf.setAttribute(
                                                            "xmlns:rdf",
                                                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                                                        rdf.setAttribute("rdf:about", link.toString())
                                                        if (result.title != null &&
                                                            "" != result.title) {
                                                            rdf.addChild(
                                                                    "title",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.title)
                                                        }
                                                        if (result.description != null &&
                                                            "" != result.description) {
                                                            rdf.addChild(
                                                                    "description",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.description)
                                                        }
                                                        if (result.url != null) {
                                                            rdf.addChild("url", "https://ogp.me/ns#")
                                                                .setContent(result.url)
                                                        }
                                                        if (result.image != null) {
                                                            rdf.addChild(
                                                                    "image",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.image)
                                                        }
                                                        if (result.type != null) {
                                                            rdf.addChild(
                                                                    "type",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.type)
                                                        }
                                                        if (result.siteName != null) {
                                                            rdf.addChild(
                                                                    "site_name",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.siteName)
                                                        }
                                                        if (result.video != null) {
                                                            rdf.addChild(
                                                                    "video",
                                                                    "https://ogp.me/ns#")
                                                                .setContent(result.video)
                                                        }
                                                        message.addPayload(rdf)
                                                        waiter.release()
                                                    }

                                                    override fun onError(error: String) {
                                                        waiter.release()
                                                    }
                                                })
                                                .showNullOnEmpty(true)
                                                .maxBodySize(90000)
                                                .timeout(5000)
                                        if (useTorToConnect()) {
                                            openGraphBuilder =
                                                openGraphBuilder.jsoupProxy(
                                                    JsoupProxy("127.0.0.1", 8118))
                                        }
                                        openGraphBuilder.build().parse(link.toString())
                                        waiter.tryAcquire(10L, TimeUnit.SECONDS)
                                    }
                                } catch (e: IOException) {
                                    // as in the Java: a link that cannot be probed is not previewed
                                } catch (e: InterruptedException) {
                                    // as in the Java: an interrupted wait is not previewed
                                }
                            }
                        }
                        synchronized(
                            message.getConversation()
                                ?: throw NullPointerException("message has no conversation")) {
                            if (message.getStatus() == MessageRef.STATUS_WAITING) {
                                sendMessage(message, true, true, false, cb, false)
                            }
                        }
                    }
                }
            }
        }

        var passedCbOn = false
        if (account.isOnlineAndConnected() && !inProgressJoin && !waitForPreview &&
            message.getTimeSent() <= System.currentTimeMillis()) {
            when (message.getEncryption()) {
                MessageRef.ENCRYPTION_NONE -> {
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                fileBackend.getFile(message, false).getSize()) ||
                            conversation.getMode() == ConversationalRef.MODE_MULTI ||
                            message.fixCounterpart()) {
                            this.sendFileMessage(message, delay, cb, forceP2P)
                            passedCbOn = true
                        }
                    } else {
                        packet = mMessageGenerator.generateChat(message)
                    }
                }
                MessageRef.ENCRYPTION_PGP, MessageRef.ENCRYPTION_DECRYPTED -> {
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                fileBackend.getFile(message, false).getSize()) ||
                            conversation.getMode() == ConversationalRef.MODE_MULTI ||
                            message.fixCounterpart()) {
                            this.sendFileMessage(message, delay, cb, forceP2P)
                            passedCbOn = true
                        }
                    } else {
                        packet = mMessageGenerator.generatePgpChat(message)
                    }
                }
                MessageRef.ENCRYPTION_OTR -> {
                    val otrSession = conversation.getOtrSession()
                    if (otrSession != null &&
                        otrSession.getSessionStatus() == SessionStatus.ENCRYPTED) {
                        val sessionJid =
                            try {
                                OtrJidHelper.fromSessionID(otrSession.getSessionID())
                            } catch (e: IllegalArgumentException) {
                                null
                            }
                        if (sessionJid != null) {
                            message.setCounterpart(sessionJid)
                            if (message.needsUploading()) {
                                mJingleConnectionManager.startJingleFileTransfer(message)
                            } else {
                                packet = mMessageGenerator.generateOtrChat(message)
                            }
                        }
                    } else if (otrSession == null) {
                        if (message.fixCounterpart()) {
                            val counterpart =
                                message.getCounterpart()
                                    ?: throw NullPointerException("message has no counterpart")
                            conversation.startOtrSession(
                                counterpart.getResource()
                                    ?: throw NullPointerException("counterpart has no resource"),
                                true)
                        } else {
                            Log.d(
                                Config.LOGTAG,
                                "" + account.getJid().asBareJid() +
                                    ": could not fix counterpart for OTR message to contact " +
                                    message.getCounterpart())
                        }
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "" + account.getJid().asBareJid() + " OTR session with " +
                                message.getContact() + " is in wrong state: " +
                                otrSession.getSessionStatus().toString())
                    }
                }
                MessageRef.ENCRYPTION_AXOLOTL -> {
                    val omemoSession =
                        account.getOmemoSession()
                            ?: throw NullPointerException("account has no omemo session")
                    message.setFingerprint(omemoSession.getOwnFingerprint())
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                fileBackend.getFile(message, false).getSize()) ||
                            conversation.getMode() == ConversationalRef.MODE_MULTI ||
                            message.fixCounterpart()) {
                            this.sendFileMessage(message, delay, cb, forceP2P)
                            passedCbOn = true
                        }
                    } else {
                        val axolotlMessage = omemoSession.fetchAxolotlMessageFromCache(message)
                        if (axolotlMessage == null) {
                            omemoSession.preparePayloadMessage(message, delay)
                        } else {
                            packet = mMessageGenerator.generateAxolotlChat(message, axolotlMessage)
                        }
                    }
                }
            }
            if (packet != null) {
                if ((account.getXmppConnection()
                        ?: throw NullPointerException("account has no connection"))
                        .getFeatures().sm() ||
                    (conversation.getMode() == ConversationalRef.MODE_MULTI &&
                        (message.getCounterpart()
                            ?: throw NullPointerException("message has no counterpart"))
                            .isBareJid())) {
                    message.setStatus(MessageRef.STATUS_UNSEND)
                } else {
                    message.setStatus(MessageRef.STATUS_SEND)
                }
            }
        } else {
            when (message.getEncryption()) {
                MessageRef.ENCRYPTION_DECRYPTED -> {
                    if (!message.needsUploading()) {
                        val pgpBody = message.getEncryptedBody()
                        val decryptedBody = message.getBody()
                        message.setBody(pgpBody) // TODO might throw NPE
                        message.setEncryption(MessageRef.ENCRYPTION_PGP)
                        if (message.edited()) {
                            message.setBody(decryptedBody)
                            message.setEncryption(MessageRef.ENCRYPTION_DECRYPTED)
                            if (!(databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, message.getEditedId())) {
                                Log.e(Config.LOGTAG, "error updated message in DB after edit")
                            }
                            updateConversationUi()
                            if (!waitForPreview && cb != null) cb.run()
                            return
                        } else {
                            (databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message)
                            saveInDb = false
                            message.setBody(decryptedBody)
                            message.setEncryption(MessageRef.ENCRYPTION_DECRYPTED)
                        }
                    }
                }
                MessageRef.ENCRYPTION_OTR -> {
                    val counterpart = message.getCounterpart()
                    if (!conversation.hasValidOtrSession() && counterpart != null) {
                        Log.d(
                            Config.LOGTAG,
                            "" + account.getJid().asBareJid() +
                                ": create otr session without starting for " +
                                (message.getContact()
                                    ?: throw NullPointerException("message has no contact"))
                                    .getJid())
                        conversation.startOtrSession(
                            counterpart.getResource()
                                ?: throw NullPointerException("counterpart has no resource"),
                            false)
                    }
                }
                MessageRef.ENCRYPTION_AXOLOTL -> {
                    message.setFingerprint(
                        (account.getOmemoSession()
                            ?: throw NullPointerException("account has no omemo session"))
                            .getOwnFingerprint())
                }
            }
        }

        synchronized(mScheduledMessages) {
            if (message.getTimeSent() > System.currentTimeMillis()) {
                mScheduledMessages.put(
                    message.getUuid() ?: throw NullPointerException("message has no uuid"),
                    message,
                )
                scheduleNextIdlePing()
            } else {
                mScheduledMessages.remove(message.getUuid())
            }
        }

        val mucMessage =
            conversation.getMode() == ConversationalRef.MODE_MULTI && !message.isPrivateMessage()
        if (mucMessage) {
            message.setCounterpart(conversation.getMucOptions().getSelf().getFullJid())
        }

        // Tulkki: a held message has been in this conversation - and in the database - since before
        // its translation was bought (see OutgoingTranslation.hold), so handing it back here must not
        // insert it a second time. Upstream's own resend route is exactly "this row already exists":
        // it marks the stored row instead of adding and creating it. The composer's hand-off does not
        // take that route - it comes through the ordinary send - so the row is recognised here, where
        // the insert decision is made. Without this, conversation.add() puts a second entry in the
        // list for the one message and the conversation shows the same bubble twice.
        val stored =
            resend ||
                sendGate().alreadyHeld(conversation, message)
        if (stored) {
            if (packet != null && addToConversation) {
                if ((account.getXmppConnection()
                        ?: throw NullPointerException("account has no connection"))
                        .getFeatures().sm() || mucMessage) {
                    markMessage(message, MessageRef.STATUS_UNSEND)
                } else {
                    markMessage(message, MessageRef.STATUS_SEND)
                }
            }
        } else {
            if (addToConversation) {
                conversation.add(message)
            }
            if (saveInDb) {
                (databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message)
                if (message.isEphemeral()) {
                    scheduleNextExpiry()
                }
            } else if (message.edited()) {
                if (!(databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, message.getEditedId())) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit")
                }
                if (message.isEphemeral()) {
                    scheduleNextExpiry()
                }
            }
            updateConversationUi()
        }
        if (packet != null) {
            if (delay) {
                mMessageGenerator.addDelay(packet, message.getTimeSent())
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                if (this.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()))
                }
            }
            sendMessagePacket(account, packet)
            val latestConversation =
                message.getConversation() ?: throw NullPointerException("message has no conversation")
            if (latestConversation.getMode() == ConversationalRef.MODE_MULTI &&
                message.hasCustomEmoji()) {
                if (latestConversation is ConversationRef) {
                    presenceToMuc(latestConversation)
                }
            }
        }
        if (!waitForPreview && !passedCbOn && cb != null) cb.run()
    }

    // Tulkki: the bodies moved to `ResendPlumbing`; the names,
    // the signatures and the lock on the conversation are unchanged. The resends re-enter chunk
    // C25b's private six-argument `sendMessage` through the same `OutgoingStanzaSender` reference
    // C25a uses; that shared seam is why C25a and C25c land in one commit.
private fun isJoinInProgress(conversation: ConversationRef): Boolean = ResendPlumbing.isJoinInProgress(conversation)

private fun sendUnsentMessages(conversation: ConversationRef) = ResendPlumbing.sendUnsentMessages(conversation, this::sendMessage)

fun resendMessage(message: MessageRef, delay: Boolean) = ResendPlumbing.resendMessage(message, delay, this::sendMessage)

fun resendMessage(message: MessageRef, delay: Boolean, cb: Runnable?) = ResendPlumbing.resendMessage(message, delay, cb, this::sendMessage)

fun resendMessage(message: MessageRef, delay: Boolean, previewedLinks: Boolean) = ResendPlumbing.resendMessage(message, delay, previewedLinks, this::sendMessage)

// Tulkki: `Config.ONBOARDING_DOMAIN` is deleted, and that domain was the only way an account
        // could be recognised as the onboarding one, so nothing can be. The predicate and its callers
        // (the onboarding UI) stay where they are and this answers false; retiring them is C5's full
        // removal, which the owner did not choose. Labelled island divergence.
fun isOnboarding(): Boolean = ResendPlumbing.isOnboarding()

fun requestEasyOnboardingInvite(account: AccountRef, callback: OnboardingInviteHook) = MdsBookmarks.requestEasyOnboardingInvite(this, account, callback)

fun fetchBookmarks(account: AccountRef) = MdsBookmarks.fetchBookmarks(this, account)

fun fetchBookmarks2(account: AccountRef) = MdsBookmarks.fetchBookmarks2(this, account)

fun fetchMessageDisplayedSynchronization(account: AccountRef) = MdsBookmarks.fetchMessageDisplayedSynchronization(this, account)

fun processMdsItem(accountRef: AccountRef, item: Element?) = MdsBookmarks.processMdsItem(this, accountRef, item)

fun markReadUpToStanzaId(conversation: ConversationRef, stanzaId: String) = MdsBookmarks.markReadUpToStanzaId(this, conversation, stanzaId)

fun markReadUpTo(conversation: ConversationRef?, message: MessageRef) =
        MdsBookmarks.markReadUpTo(
            this,
            conversation ?: throw NullPointerException("no conversation"),
            message)

    /**
     * Tulkki: 3.7 pair 9, part 12 - the map parameter is the ref's wildcard now.
     *
     * <p>`MessageParser` gets its bookmarks from `DataStatics.parseBookmarksFromStorage`, whose return
     * is `Map<Jid, ? extends BookmarkRef>` because the model's static answers `Map<Jid, Bookmark>` and
     * generics are invariant. The parameter is **retyped rather than overloaded**, and the reason is a
     * language fact worth recording: `Map<Jid, Bookmark>` and `Map<Jid, ? extends BookmarkRef>` erase
     * to the same `Map`, so a second method would be a name clash, not an overload - the same class of
     * fact as {@code AccountRef}'s missing `setBookmarks` and {@code MessageRef}'s
     * `replaceReactions`.
     *
     * <p>**C5-E2 removed the last two model spellings from this method**: the loop variable's
     * `(Bookmark)` cast is gone (`BookmarkRef.getJid()` carries the `Jid`), and the last line is
     * {@code accountRef.replaceBookmarks}, the differently named member the erasure clash above
     * forces - which is also what let the `(Account) accountRef` cast go.
     */
fun processBookmarksInitial(accountRef: AccountRef, bookmarks: Map<Jid, BookmarkRef>, pep: Boolean) = MdsBookmarks.processBookmarksInitial(this, accountRef, bookmarks, pep)

fun processDeletedBookmarks(accountRef: AccountRef, bookmarks: Collection<Jid>) = MdsBookmarks.processDeletedBookmarks(this, accountRef, bookmarks)

fun processDeletedBookmark(accountRef: AccountRef, jid: Jid) = MdsBookmarks.processDeletedBookmark(this, accountRef, jid)

private fun processModifiedBookmark(bookmark: BookmarkRef, pep: Boolean) = MdsBookmarks.processModifiedBookmark(this, bookmark, pep)

    // Tulkki: the bodies moved to `BookmarkPublication`; the
    // names, the visibility and the signatures are unchanged, and the private helpers moved whole.
    // The IQ generator is reached through the service's own public getter; the one-shot notification
    // handler, which has no accessor, goes in by hand.
fun ensureBookmarkIsAutoJoin(conversation: ConversationRef) = BookmarkPublication.ensureBookmarkIsAutoJoin(this, conversation, mDefaultIqHandler)

fun createBookmark(account: AccountRef, bookmark: BookmarkRef) = BookmarkPublication.createBookmark(this, account, bookmark, mDefaultIqHandler)

fun deleteBookmark(account: AccountRef, bookmark: BookmarkRef) = BookmarkPublication.deleteBookmark(this, account, bookmark, mDefaultIqHandler)


    // Tulkki: the bodies moved to `ConversationReload`; the
    // private `restoreMessages` had no caller outside the chunk and moved whole. The live
    // conversation list, the database-reader executor and the latch are private state of other
    // groups and travel in unchanged.
    //
    // Tulkki: the fifth argument is the live story cache, and since C62 it is read through
    // `StoryCache.stories()` rather than as the field it used to be. The restore merges each
    // persisted story into that same instance and re-sorts it, so the object handed over must be
    // the one `getStories()` answers - which `StoryCache.stories()` is. A **field** read is the
    // one reference a moved method's delegation does not cover, and this call site is the one
    // C62's first sweep missed; the other, `checkListeners()`'s `mOnStoriesUpdates`, was caught.
private fun restoreFromDatabase() = ConversationReload.restoreFromDatabase(
                this, conversationList, mDatabaseReaderExecutor, restoredFromDatabaseLatch,
                StoryCache.stories())

    // Tulkki: the bodies moved to `RosterSync`; the names, the
    // visibility and the signatures are unchanged. The contact-merger executor and the one-shot
    // address-book flag moved with the chunk; the roster sync task manager is also read by
    // `deleteAccount` (chunk C33) and so stays here and is passed in.
fun loadPhoneContacts() = RosterSync.loadPhoneContacts(
            this,
            mShortcutService ?: throw NullPointerException("the shortcut port is not installed"),
            contactListSyncService ?: throw NullPointerException("the contact-list sync port is not installed"))

fun syncRoster(accountRef: AccountRef) = RosterSync.syncRoster(this, accountRef, mRosterSyncTaskManager)

fun getConversationList(): MutableList<ConversationRef> =
        RosterSync.getConversationList(this.conversationList)

    // Tulkki: the body moved to `ConversationBookkeeping`; the
    // live list and the `FILENAMES_TO_IGNORE_DELETION` monitor stay on the service and travel in,
    // so the observer's `Consumer<File>` binding and the lock target are the Java's.
private fun markFileDeleted(file: File) = ConversationBookkeeping.markFileDeleted(this, file)

private fun markChangedFiles(infos: List<FilePathInfoRef>) = ConversationBookkeeping.markChangedFiles(this, infos)

fun populateWithOrderedConversationList(list: MutableList<ConversationRef>) = ConversationBookkeeping.populateWithOrderedConversationList(this, list)

fun populateWithOrderedConversationList(list: MutableList<ConversationRef>, includeNoFileUpload: Boolean) = ConversationBookkeeping.populateWithOrderedConversationList(
                this, list, includeNoFileUpload)

fun populateWithOrderedConversationList(list: MutableList<ConversationRef>, includeNoFileUpload: Boolean, sort: Boolean) = ConversationBookkeeping.populateWithOrderedConversationList(
                this, list, includeNoFileUpload, sort)

fun jumpToMessage(conversation: ConversationRef, uuid: String?, listener: JumpToMessageListener) = ConversationPaging.jumpToMessage(this, conversation, uuid, listener, mDatabaseReaderExecutor)

fun loadMoreMessages(conversation: ConversationRef, timestamp: Long, isForward: Boolean, callback: OnMoreMessagesLoaded) = ConversationPaging.loadMoreMessages(
                this, conversation, timestamp, isForward, callback, mDatabaseReaderExecutor)

    /**
     * This will find all conferences with the contact as member and also the conference that is the
     * contact (that 'fake' contact is used to store the avatar)
     */
    // Tulkki: the bodies moved to `ConversationScans`; the
    // signatures, the guard order, the reference comparisons and the lock on the live conversation
    // list are unchanged. The list is passed in, so the scans still read the service's own
    // `CopyOnWriteArrayList` and the two `is…` methods still synchronize on that same object.
fun findAllConferencesWith(contact: ContactRef): List<ConversationRef> = ConversationScans.findAllConferencesWith(this.conversationList, contact)

fun find(contact: ContactRef): ConversationRef? = ConversationScans.find(this.conversationList, contact)

fun find(haystack: Iterable<ConversationRef>?, accountRef: AccountRef?, jid: Jid?): ConversationRef? = ConversationScans.find(haystack, accountRef, jid)

private fun find(haystack: Iterable<ConversationRef>, accountRef: AccountRef?, jid: Jid?, counterpart: Jid?): ConversationRef? = ConversationScans.find(haystack, accountRef, jid, counterpart)

fun isConversationListEmpty(ignore: ConversationRef?): Boolean = ConversationScans.isConversationListEmpty(this.conversationList, ignore)

fun isConversationStillOpen(conversation: ConversationRef): Boolean = ConversationScans.isConversationStillOpen(this.conversationList, conversation)

fun maybeRegisterWithMuc(c: ConversationRef, nickArg: String?) = ConversationCreation.maybeRegisterWithMuc(this, c, nickArg)

fun deregisterWithMuc(c: ConversationRef) = ConversationCreation.deregisterWithMuc(this, c)

fun findOrCreateConversation(accountRef: AccountRef, jid: Jid, muc: Boolean, async: Boolean): ConversationRef = ConversationCreation.findOrCreateConversation(
            this, accountRef, jid, muc, async, conversationList, mDatabaseReaderExecutor)

fun findOrCreateConversation(accountRef: AccountRef, jid: Jid, muc: Boolean, joinAfterCreate: Boolean, async: Boolean): ConversationRef = ConversationCreation.findOrCreateConversation(
            this, accountRef, jid, muc, joinAfterCreate, async, conversationList, mDatabaseReaderExecutor)

fun findOrCreateConversation(accountRef: AccountRef, jid: Jid, muc: Boolean, joinAfterCreate: Boolean, query: MessageArchiveService.Query?, async: Boolean): ConversationRef = ConversationCreation.findOrCreateConversation(
            this, accountRef, jid, muc, joinAfterCreate, query, async, conversationList, mDatabaseReaderExecutor)

fun findOrCreateConversation(accountRef: AccountRef, jid: Jid, muc: Boolean, joinAfterCreate: Boolean, query: MessageArchiveService.Query?, async: Boolean, password: String?): ConversationRef = ConversationCreation.findOrCreateConversation(
            this, accountRef, jid, muc, joinAfterCreate, query, async, password, conversationList, mDatabaseReaderExecutor)

    // Tulkki: the bodies moved to `ConversationRestore`; the
    // signatures and the guard order are unchanged, and the database-reader executor is passed in
    // because it belongs to chunk C02. The live conversation list is reached through
    // `getConversationList()` inside the Kotlin, so the write lands on the service's own list.
fun findConversationByUuidReliable(uuid: String): ConversationRef? = ConversationRestore.findConversationByUuidReliable(this, uuid, mDatabaseReaderExecutor)

private fun restoreFromArchive(conversation: ConversationRef, jid: Jid, muc: Boolean): Boolean = ConversationRestore.restoreFromArchive(this, conversation, jid, muc)

private fun restoreFromArchive(conversation: ConversationRef): Boolean = ConversationRestore.restoreFromArchive(this, conversation)

private fun postProcessConversation(c: ConversationRef, loadMessagesFromDb: Boolean, joinAfterCreate: Boolean, query: MessageArchiveService.Query?) = ConversationRestore.postProcessConversation(
                this, c, loadMessagesFromDb, joinAfterCreate, query)

    // Tulkki: the bodies moved to `ConversationArchival`; the
    // signatures, the guard order and the lock on the live conversation list are unchanged.
fun archiveConversation(conversation: ConversationRef) = ConversationArchival.archiveConversation(this, conversation)

private fun archiveConversation(conversation: ConversationRef, maySynchronizeWithBookmarks: Boolean) = ConversationArchival.archiveConversation(this, conversation, maySynchronizeWithBookmarks)

fun stopPresenceUpdatesTo(contact: ContactRef) = ConversationArchival.stopPresenceUpdatesTo(this, contact)

    // Tulkki: the bodies moved to `AccountLifecycle`. Every field
    // they reach stays here: `conversationList` is the same live list `deleteAccount` locks, the
    // writer executor and the roster-sync manager travel by value, C70's nullable
    // `mNotificationService` travels nullable, and C76's private accessors travel as Suppliers so
    // their `require` still throws where the Java threw. `getUnifiedPushBroker()` stays a plain
    // getter of C74's field.
fun createAccount(account: AccountRef) = AccountLifecycle.createAccount(
                this, account, this::callIntegration, this::syncEnabledAccountSetting)

private fun syncEnabledAccountSetting() = AccountLifecycle.syncEnabledAccountSetting(
                this, this::systemEvent, this::hasEnabledAccounts,
                this::toggleSetProfilePictureActivity)

private fun toggleSetProfilePictureActivity(enabled: Boolean) = AccountLifecycle.toggleSetProfilePictureActivity(
                this, enabled, this::profilePictureActivityPort)

fun reconfigurePushDistributor(): Boolean = AccountLifecycle.reconfigurePushDistributor(this.unifiedPushBroker ?: throw NullPointerException("the unified-push port is not installed"))

private fun renewUnifiedPushEndpoints(pushTargetMessenger: UnifiedPushPort.PushTarget?): Optional<UnifiedPushPort.Transport> = AccountLifecycle.renewUnifiedPushEndpoints(this.unifiedPushBroker ?: throw NullPointerException("the unified-push port is not installed"), pushTargetMessenger)

fun renewUnifiedPushEndpoints(): Optional<UnifiedPushPort.Transport> = AccountLifecycle.renewUnifiedPushEndpoints(this.unifiedPushBroker ?: throw NullPointerException("the unified-push port is not installed"), null)

fun getUnifiedPushBroker(): UnifiedPushPort =
        this.unifiedPushBroker ?: throw NullPointerException("the unified-push port is not installed")
            ?: throw NullPointerException("the unified-push port is not installed")

private fun provisionAccount(address: String, password: String) = AccountLifecycle.provisionAccount(this, address, password, this::createAccount)

fun createAccountFromKey(alias: String, callback: OnAccountCreated) = AccountLifecycle.createAccountFromKey(this, alias, callback, this::createAccount)

fun updateKeyInAccount(account: AccountRef, alias: String) = AccountLifecycle.updateKeyInAccount(this, account, alias)

fun updateAccount(account: AccountRef): Boolean = AccountLifecycle.updateAccount(
                this, account, statusListener, this::callIntegration,
                { mChannelDiscoveryService ?: throw NullPointerException("the channel-discovery port is not installed") }, this::syncEnabledAccountSetting)

fun updateAccountPasswordOnServer(account: AccountRef, newPassword: String, callback: OnAccountPasswordChanged) = AccountLifecycle.updateAccountPasswordOnServer(this, account, newPassword, callback)

fun unregisterAccount(account: AccountRef, callback: Consumer<Boolean>) = AccountLifecycle.unregisterAccount(this, account, callback)

fun deleteAccount(account: AccountRef) = AccountLifecycle.deleteAccount(
                this, account, conversationList, mNotificationService, mDatabaseWriterExecutor,
                mRosterSyncTaskManager, { a, sync -> disconnect(a, sync) }, this::callIntegration,
                this::syncEnabledAccountSetting)

    // Tulkki: the bodies moved to `ListenerRegistration`. The
    // nine weak sets and `LISTENER_LOCK` stay here - `threadSafeList` (C48) and every C49 delegation
    // read them - and travel in by value, so nothing was widened; C62's weak story set (which now
    // lives on `StoryCache`) and C70's nullable `mNotificationService` arrive by hand, and the
    // foreground/background switch
    // is the private method as a `Runnable`. The Java's guard order is kept exactly: a setter reads
    // the remaining-listener answer before the add, a remover after the drop.
fun setOnConversationListChangedListener(listener: OnConversationUpdate) = ListenerRegistration.setOnConversationListChangedListener(
                listener,
                this.mOnConversationUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this.mNotificationService
                ?: throw NullPointerException("the notification port is not installed"),
                this::switchToForeground)

fun removeOnConversationListChangedListener(listener: OnConversationUpdate) = ListenerRegistration.removeOnConversationListChangedListener(
                listener,
                this.mOnConversationUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this.mNotificationService
                ?: throw NullPointerException("the notification port is not installed"),
                this::switchToBackground)

fun setOnShowErrorToastListener(listener: OnShowErrorToast) = ListenerRegistration.add(
                listener,
                this.mOnShowErrorToasts,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnShowErrorToastListener")

fun removeOnShowErrorToastListener(onShowErrorToast: OnShowErrorToast) = ListenerRegistration.remove(
                onShowErrorToast,
                this.mOnShowErrorToasts,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnAccountListChangedListener(listener: OnAccountUpdate) = ListenerRegistration.add(
                listener,
                this.mOnAccountUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnAccountListChangedtListener")

fun removeOnAccountListChangedListener(listener: OnAccountUpdate) = ListenerRegistration.remove(
                listener,
                this.mOnAccountUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnCaptchaRequestedListener(listener: OnCaptchaRequested) = ListenerRegistration.add(
                listener,
                this.mOnCaptchaRequested,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnCaptchaRequestListener")

fun removeOnCaptchaRequestedListener(listener: OnCaptchaRequested) = ListenerRegistration.remove(
                listener,
                this.mOnCaptchaRequested,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnRosterUpdateListener(listener: OnRosterUpdate) = ListenerRegistration.add(
                listener,
                this.mOnRosterUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnRosterUpdateListener")

fun removeOnRosterUpdateListener(listener: OnRosterUpdate) = ListenerRegistration.remove(
                listener,
                this.mOnRosterUpdates,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnUpdateBlocklistListener(listener: OnUpdateBlocklist) = ListenerRegistration.add(
                listener,
                this.mOnUpdateBlocklist,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnUpdateBlocklistListener")

fun removeOnUpdateBlocklistListener(listener: OnUpdateBlocklist) = ListenerRegistration.remove(
                listener,
                this.mOnUpdateBlocklist,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnKeyStatusUpdatedListener(listener: OnKeyStatusUpdated) = ListenerRegistration.add(
                listener,
                this.mOnKeyStatusUpdated,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnKeyStatusUpdateListener")

fun removeOnNewKeysAvailableListener(listener: OnKeyStatusUpdated) = ListenerRegistration.remove(
                listener,
                this.mOnKeyStatusUpdated,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnRtpConnectionUpdateListener(listener: OnJingleRtpConnectionUpdate) = ListenerRegistration.add(
                listener,
                this.onJingleRtpConnectionUpdate,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnJingleRtpConnectionUpdate")

fun removeRtpConnectionUpdateListener(listener: OnJingleRtpConnectionUpdate) = ListenerRegistration.remove(
                listener,
                this.onJingleRtpConnectionUpdate,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun setOnMucRosterUpdateListener(listener: OnMucRosterUpdate) = ListenerRegistration.add(
                listener,
                this.mOnMucRosterUpdate,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToForeground,
                "OnMucRosterListener")

fun removeOnMucRosterUpdateListener(listener: OnMucRosterUpdate) = ListenerRegistration.remove(
                listener,
                this.mOnMucRosterUpdate,
                LISTENER_LOCK,
                this::checkListeners,
                this::switchToBackground)

fun checkListeners(): Boolean = ListenerRegistration.checkListeners(
                this.mOnAccountUpdates,
                this.mOnConversationUpdates,
                this.mOnRosterUpdates,
                this.mOnCaptchaRequested,
                this.mOnMucRosterUpdate,
                this.mOnUpdateBlocklist,
                this.mOnShowErrorToasts,
                this.onJingleRtpConnectionUpdate,
                this.mOnKeyStatusUpdated,
                StoryCache.listeners())

    // Tulkki: the bodies moved to `ForegroundState`; the names,
    // the visibility and the guard order are unchanged. The live conversation list is handed in, the
    // last-activity field is written through a `LongConsumer`, and chunk C74's nullable sync engine
    // and the notification port arrive by hand.
private fun switchToForeground() = ForegroundState.switchToForeground(
                this,
                getConversationList(),
                this::toggleSoftDisabled,
                syncEvents,
                { account, includeIdleTimestamp -> sendPresence(account, includeIdleTimestamp) })

private fun switchToBackground() = ForegroundState.switchToBackground(
                this,
                { lastActivity -> this.mLastActivity = lastActivity },
                this.mNotificationService
                ?: throw NullPointerException("the notification port is not installed"),
                SETTING_LAST_ACTIVITY_TS,
                syncEvents,
                { account, includeIdleTimestamp -> sendPresence(account, includeIdleTimestamp) })

fun connectMultiModeConversationList(account: AccountRef) = ForegroundState.connectMultiModeConversationList(
                getConversationList(), account, this::joinMuc)

    // Tulkki: the bodies moved to `MucJoinEntry`; the names,
    // the signatures and the guard order are unchanged. C36b's private three-argument join is the
    // one seam and goes in as the `MucJoiner` method reference, so its visibility is not widened.
fun mucSelfPingAndRejoin(conversation: ConversationRef) = MucJoinEntry.mucSelfPingAndRejoin(this, conversation, this::joinMuc)

fun joinMuc(conversation: ConversationRef) = MucJoinEntry.joinMuc(conversation, false, this::joinMuc)

fun joinMuc(conversation: ConversationRef, followedInvite: Boolean) = MucJoinEntry.joinMuc(conversation, followedInvite, this::joinMuc)

private fun joinMuc(conversation: ConversationRef, onConferenceJoined: OnConferenceJoined) = MucJoinEntry.joinMucWithCallback(conversation, onConferenceJoined, this::joinMuc)

private fun joinMuc(conversation: ConversationRef, onConferenceJoined: OnConferenceJoined?, followedInvite: Boolean) = MucJoin.joinMuc(this, conversation, onConferenceJoined, followedInvite, this::sendMessage)

private fun fetchConferenceMembers(conversation: ConversationRef) = MucJoin.fetchConferenceMembers(this, conversation)

fun providePasswordForMuc(conversation: ConversationRef, password: String?) = MucJoin.providePasswordForMuc(this, conversation, password)

fun deleteAvatar(account: AccountRef) = AvatarNodes.deleteAvatar(this, account, mIqGenerator)

fun deletePepNode(account: AccountRef, node: String) = AvatarNodes.deletePepNode(this, account, node, mIqGenerator)

    // Tulkki: the bodies moved to `Attachments`; the seven
    // overloads nest as they did, the null-tolerant `jid`/`query`, the query-only overload's
    // explicit nulls and `deleteMedia`'s wildcard are unchanged, and no call site moves.
private fun hasEnabledAccounts(): Boolean = Attachments.hasEnabledAccounts()

fun getAttachments(conversation: ConversationRef, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromConversation(this, conversation, limit, onMediaLoaded)

fun getAttachments(conversation: ConversationRef, query: String?, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromConversationWithQuery(this, conversation, query, limit, onMediaLoaded)

fun getAttachments(account: AccountRef, jid: Jid, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromAccount(this, account, jid, limit, onMediaLoaded)

fun getAttachments(account: AccountRef, jid: Jid, query: String?, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromAccountWithQuery(this, account, jid, query, limit, onMediaLoaded)

fun getAttachments(account: String?, jid: Jid?, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromAccountUuid(this, account, jid, limit, onMediaLoaded)

// Tulkki: C5-R1 - the body moved to `DataStaticsHost.loadAttachments`. It spawns the same
        // thread over the same call; `convertToAttachments`' parameter and return are `:data` names
        // the island cannot write, so the island keeps the entry point and the port keeps the call.
fun getAttachments(account: String?, jid: Jid?, query: String?, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.load(this, account, jid, query, limit, onMediaLoaded)

fun getAttachments(query: String?, limit: Int, onMediaLoaded: MediaLoadedHook) = Attachments.fromQueryOnly(this, query, limit, onMediaLoaded)

    // Tulkki: C5-E3 - the parameter is the wildcard, and it has to be: `MediaBrowserActivity:283`
    // builds its list from a `HashSet` of the ref, and the invariant spelling would make this
    // signature depend on how `:ui` types that set. The body reads only `getUri()` and `getUuid()`,
    // both on the ref, and it does not copy the list.
fun deleteMedia(attachments: List<AttachmentRef>) = Attachments.deleteMedia(this, attachments)

    /**
     * Tulkki: 3.7 pair 9, part 11, finished by C5-E2.
     *
     * <p>The parser's participant is a `MucOptionsRef.UserRef`. Part 11 kept a model-typed twin and
     * cast once; C5-E2 retyped the twin, so the two became the same method twice and the cast-and-
     * delegate form is gone. `PresenceParser:131` is the only caller and it holds the ref.
     */
    // Tulkki: the bodies moved to `MucMembership`; the names,
    // the signatures, the visibility and the guard order are unchanged. The two private methods
    // keep their Java name as one-line delegations because both are called from outside the chunk;
    // the private `conversationList` (C29) and `mPresenceGenerator` (C55) are passed by hand.
fun persistSelfNick(self: MucOptionsRef.UserRef, modified: Boolean) = MucMembership.persistSelfNick(this, self, modified)

fun presenceToMuc(conversation: ConversationRef) = MucMembership.presenceToMuc(this, conversation, mPresenceGenerator)

fun renameInMuc(conversation: ConversationRef, nick: String, callback: UiCallbackPort<ConversationRef>): Boolean = MucMembership.renameInMuc(this, conversation, nick, callback, mPresenceGenerator)

fun checkMucRequiresRename() = MucMembership.checkMucRequiresRename(this, this.conversationList, mPresenceGenerator)

private fun checkMucRequiresRename(conversation: ConversationRef) = MucMembership.checkMucRequiresRename(this, conversation, mPresenceGenerator)

fun leaveMuc(conversation: ConversationRef) = leaveMuc(conversation, false)

private fun leaveMuc(conversation: ConversationRef, now: Boolean) = MucMembership.leaveMuc(this, conversation, now, mPresenceGenerator)

fun findConferenceServer(account: AccountRef): String? = MucMembership.findConferenceServer(account)

    // Tulkki: the bodies moved to `ConversationChannels`; the
    // names, the signatures and the guard order are unchanged. The private two-argument
    // `joinMuc(ConversationRef, OnConferenceJoined)` is C36b's and goes in as the
    // `MucJoinerWithCallback` method reference, so its visibility is not widened; the private
    // `mIqGenerator` (C55) is passed by hand.
fun createPublicChannel(account: AccountRef, name: String?, address: Jid, callback: UiCallbackPort<ConversationRef>) = ConversationChannels.createPublicChannel(
                this, account, name, address, callback, this::joinMuc)

fun createAdhocConference(account: AccountRef, name: String?, jids: Iterable<@JvmSuppressWildcards Jid>, callback: UiCallbackPort<ConversationRef>?): Boolean = ConversationChannels.createAdhocConference(
                this, account, name, jids, callback, this::joinMuc)

fun checkIfMuc(account: AccountRef, jid: Jid, cb: Consumer<Boolean>) = ConversationChannels.checkIfMuc(this, account, jid, cb, mIqGenerator)

fun fetchConferenceConfiguration(conversation: ConversationRef) = ConferenceConfiguration.fetchConferenceConfiguration(this, conversation)

fun fetchConferenceConfiguration(conversation: ConversationRef, callback: OnConferenceConfigurationFetched?) = ConferenceConfiguration.fetchConferenceConfiguration(this, conversation, callback)

fun pushNodeConfiguration(account: AccountRef, node: String, options: Bundle?, callback: OnConfigurationPushed?) =
        ConferenceConfiguration.pushNodeConfiguration(
            this, account, node, options ?: throw NullPointerException("no node options"), callback)

fun pushNodeConfiguration(account: AccountRef, jid: Jid, node: String, options: Bundle?, callback: OnConfigurationPushed?) =
        ConferenceConfiguration.pushNodeConfiguration(
            this, account, jid, node, options ?: throw NullPointerException("no node options"), callback)


fun pushConferenceConfiguration(conversation: ConversationRef, options: Bundle?, callback: OnConfigurationPushed?) =
        ConferenceConfiguration.pushConferenceConfiguration(
            this, conversation, options ?: throw NullPointerException("no conference options"), callback)

fun pushSubjectToConference(conference: ConversationRef, subject: String?) = ConferenceConfiguration.pushSubjectToConference(this, conference, subject)

fun requestVoice(account: AccountRef, jid: Jid) = ConferenceAdmin.requestVoice(this, account, jid)

    /**
     * Tulkki: 3.7 C5-E2 - the affiliation parameter is the island enum now. The method only
     * stringifies it for `IqGenerator.changeAffiliation` and hands it to the model's own ref-typed
     * `MucOptions.changeAffiliation`, so no identity crosses.
     */
fun changeAffiliationInConference(conference: ConversationRef, user: Jid, affiliation: MucOptionsRef.AffiliationRef, callback: OnAffiliationChanged?) = ConferenceAdmin.changeAffiliationInConference(
                this, conference, user, affiliation, callback, mIqGenerator)

    /**
     * Tulkki: 3.7 C5-E2 - the role parameter is {@link MucOptionsRef.RoleRef}, whose `toString()` is
     * the model's copied. This method only stringifies it, which is why the island enum needs no
     * `ranks` and no mapping back.
     */
fun changeRoleInConference(conference: ConversationRef, nick: String, role: MucOptionsRef.RoleRef) = ConferenceAdmin.changeRoleInConference(this, conference, nick, role, mIqGenerator)

fun moderateMessage(account: AccountRef, m: MessageRef, reason: String?) = ConferenceAdmin.moderateMessage(this, account, m, reason, mIqGenerator)

fun destroyRoom(conversation: ConversationRef, callback: OnRoomDestroy?) = ConferenceAdmin.destroyRoom(this, conversation, callback)

private fun disconnect(account: AccountRef, force: Boolean) = ConferenceAdmin.disconnect(
                this,
                account,
                force,
                { conversation -> leaveMuc(conversation, true) },
                this::sendOfflinePresence,
                syncEvents)

    // Tulkki: the bodies moved to `MessageContacts`; the names,
    // the visibility, the signatures and the guard order are unchanged. The binder, the scheduled
    // messages, the database-writer executor and the three generators are private state of other
    // groups and go in by hand, so neither visibility is widened.
override fun onBind(intent: Intent): IBinder = MessageContacts.onBind(mBinder)

fun deleteMessage(message: MessageRef) = MessageContacts.deleteMessage(this, message, mScheduledMessages)

fun deleteFileIfUnused(message: MessageRef) = MessageContacts.deleteFileIfUnused(this, message)

fun updateMessageGeoPayload(conversationUuid: String, messageUuid: String?, lat: Double, lon: Double) = MessageContacts.updateMessageGeoPayload(this, conversationUuid, messageUuid, lat, lon)

fun updateMessage(message: MessageRef) = MessageContacts.updateMessage(this, message)

fun updateMessage(message: MessageRef, includeBody: Boolean) = MessageContacts.updateMessage(this, message, includeBody)

fun createMessageAsync(message: MessageRef) = MessageContacts.createMessageAsync(this, message, mDatabaseWriterExecutor)

fun updateMessage(message: MessageRef, uuid: String) = MessageContacts.updateMessage(this, message, uuid)

fun syncDirtyContacts(account: AccountRef) = MessageContacts.syncDirtyContacts(this, account, mDefaultIqHandler, mPresenceGenerator)

internal fun unregisterPhoneAccounts(account: AccountRef) = MessageContacts.unregisterPhoneAccounts(account, this)

fun createContact(contact: ContactRef, autoGrant: Boolean) = MessageContacts.createContact(
                this, contact, autoGrant, null, mDefaultIqHandler, mPresenceGenerator)

fun createContact(contact: ContactRef, autoGrant: Boolean, preAuth: String?) = MessageContacts.createContact(
                this, contact, autoGrant, preAuth, mDefaultIqHandler, mPresenceGenerator)

fun onOtrSessionEstablished(conversation: ConversationRef) = MessageContacts.onOtrSessionEstablished(
                this, conversation, mJingleConnectionManager, mMessageGenerator)

fun pushContactToServer(contact: ContactRef) = MessageContacts.pushContactToServer(
                this, contact, null, mDefaultIqHandler, mPresenceGenerator)

fun publishMucAvatar(conversation: ConversationRef, image: Uri, callback: AvatarPublicationHook) = AvatarPublishing.publishMucAvatar(this, conversation, image, callback)

fun publishAvatarAsync(account: AccountRef, image: Uri, open: Boolean, callback: AvatarPublicationHook) = AvatarPublishing.publishAvatarAsync(this, account, image, open, callback)





fun publishAvatar(account: AccountRef, avatar: Avatar, open: Boolean, callback: AvatarPublicationHook?) = AvatarPublishing.publishAvatar(this, account, avatar, open, callback)

fun publishAvatar(account: AccountRef, avatar: Avatar, options: Bundle?, retry: Boolean, callback: AvatarPublicationHook?) = AvatarPublishing.publishAvatar(this, account, avatar, options, retry, callback)

fun publishAvatarMetadata(account: AccountRef, avatar: Avatar, options: Bundle?, retry: Boolean, callback: AvatarPublicationHook?) = AvatarPublishing.publishAvatarMetadata(this, account, avatar, options, retry, callback)

fun republishAvatarIfNeeded(account: AccountRef) = AvatarPublishing.republishAvatarIfNeeded(this, account)

    // Tulkki: C5-C - the parameter is the ref. The body already read nothing but `AccountRef`'s own
    // surface, and every caller passes a model `Account`, which the ref accepts. Widened rather than
    // overloaded because `BindProcessor`'s `Account` import can only go if no model-typed entry point
    // is left for it to reach.
fun cancelAvatarFetches(account: AccountRef) = AvatarFetching.cancelAvatarFetches(account)

fun fetchAvatar(accountRef: AccountRef, avatar: Avatar) = AvatarFetching.fetchAvatar(this, accountRef, avatar, null, { acc, avatar -> generateFetchKey(acc, avatar) })

fun fetchAvatar(accountRef: AccountRef, avatar: Avatar, callback: UiCallbackPort<Avatar>?) = AvatarFetching.fetchAvatar(this, accountRef, avatar, callback, { acc, avatar -> generateFetchKey(acc, avatar) })

fun checkForAvatar(account: AccountRef, callback: UiCallbackPort<Avatar>) = AvatarFetching.checkForAvatar(this, account, callback, { acc, avatar -> generateFetchKey(acc, avatar) })

fun notifyAccountAvatarHasChanged(accountRef: AccountRef) = AvatarFetching.notifyAccountAvatarHasChanged(this, accountRef, conversationList)

fun fetchVcard4(account: AccountRef, contact: ContactRef, callback: Consumer<Element?>?) = AvatarFetching.fetchVcard4(this, account, contact, callback)

fun deleteContactOnServer(contact: ContactRef) = AvatarFetching.deleteContactOnServer(contact, mDefaultIqHandler)

    // Tulkki: the bodies moved to `ConversationLifecycle`; the
    // names, the signatures, the visibility and the guard order are unchanged. The private
    // database-writer executor (C02) and message generator (C55) are passed by hand, and the private
    // `disconnect` (C41) goes in as the `AccountDisconnector` method reference.
fun updateConversation(conversation: ConversationRef) = ConversationLifecycle.updateConversation(this, conversation, mDatabaseWriterExecutor)

private fun reconnectAccount(account: AccountRef, force: Boolean, interactive: Boolean) = ConversationLifecycle.reconnectAccount(
                this, account, force, interactive, this::disconnect)

fun reconnectAccountInBackground(account: AccountRef) = ConversationLifecycle.reconnectAccountInBackground(this, account, this::disconnect)

fun invite(conversation: ConversationRef, contact: Jid) = ConversationLifecycle.invite(this, conversation, contact, mMessageGenerator)

fun directInvite(conversation: ConversationRef, jid: Jid) = ConversationLifecycle.directInvite(this, conversation, jid, mMessageGenerator)

    // Tulkki: C5-C - the ref, and the body gets *simpler* rather than merely compiling:
    // `ConversationRef.getAccount()` already answers `AccountRef`, so the identity comparison below
    // now compares two refs to the same object instead of relying on the model type to line them up.
    // Identity is the right comparison here and is preserved exactly - `Account` is the only
    // implementor of `AccountRef`, so the two sides are the same object either way.
    // Tulkki: the bodies moved to `MessageStatus`; the names,
    // the signatures and the guard order are unchanged. The private `mNotificationService` (C70)
    // goes in by hand, nullable, so the one branch that dereferenced it keeps the Java's NPE.
fun resetSendingToWaiting(account: AccountRef) = MessageStatus.resetSendingToWaiting(this, account, mNotificationService)

    // Tulkki: the nullable faces of the four- and five-argument `markMessage` below, for the
    // callers that must tolerate a miss. A remote stanza chooses the uuid it names, so it can name
    // one this device need not hold - a MAM gap, another resource, a row archived or deleted. A
    // `<displayed/>` receipt (XEP-0333), a `<received/>` receipt (XEP-0184) and an `<error/>` on a
    // message we sent all arrive that way, and each caller already answers a null by marking
    // nothing or by skipping what it guards. The throwing overloads stay loud for every other
    // caller; these exist so that a stanza the server will replay on the next connect cannot take
    // the connection thread down for a message that is simply not here.
fun markMessageOrNull(accountRef: AccountRef, recipient: Jid, uuid: String?, status: Int): MessageRef? =
        MessageStatus.markMessage(this, accountRef, recipient, uuid, status, mNotificationService)

fun markMessageOrNull(
        accountRef: AccountRef,
        recipient: Jid,
        uuid: String?,
        status: Int,
        errorMessage: String?,
    ): MessageRef? =
        MessageStatus.markMessage(
            this, accountRef, recipient, uuid, status, errorMessage, mNotificationService)

fun markMessage(accountRef: AccountRef, recipient: Jid, uuid: String?, status: Int): MessageRef =
        MessageStatus.markMessage(this, accountRef, recipient, uuid, status, mNotificationService)
            ?: throw NullPointerException("no message with that id")

fun markMessage(
        accountRef: AccountRef,
        recipient: Jid,
        uuid: String?,
        status: Int,
        errorMessage: String?,
    ): MessageRef =
        MessageStatus.markMessage(
            this, accountRef, recipient, uuid, status, errorMessage, mNotificationService)
            ?: throw NullPointerException("no message with that id")

fun markMessage(
        conversation: ConversationRef,
        uuid: String?,
        status: Int,
        serverMessageId: String?,
    ): Boolean =
        MessageStatus.markMessage(
            this,
            conversation,
            uuid ?: throw NullPointerException("no message uuid"),
            status,
            serverMessageId ?: throw NullPointerException("no server message id"),
            mNotificationService)

fun markMessage(conversation: ConversationRef, uuid: String?, status: Int, serverMessageId: String?, body: LocalizedContent?, html: Element?, subject: String?, thread: Element?, attachments: Set<@JvmSuppressWildcards MessageRef.FileParamsRef>?): Boolean = MessageStatus.markMessage(
                this, conversation, uuid, status, serverMessageId, body, html, subject, thread,
                attachments, mNotificationService)

fun markMessage(message: MessageRef, status: Int) = MessageStatus.markMessage(this, message, status, mNotificationService)

fun markMessage(message: MessageRef, status: Int, errorMessage: String?) = MessageStatus.markMessage(this, message, status, errorMessage, mNotificationService)

fun markMessage(message: MessageRef, status: Int, errorMessage: String?, includeBody: Boolean) = MessageStatus.markMessage(
                this, message, status, errorMessage, includeBody, mNotificationService)

    // Tulkki: the preference reads moved to `ServicePreferences`;
    // the names, the visibility and the resource defaults are unchanged.
fun getPreferences(): SharedPreferences = ServicePreferences.getPreferences(this)

fun getAutomaticMessageDeletionDate(): Long = ServicePreferences.getAutomaticMessageDeletionDate(this)

fun getOmemoAutoExpiry(): Long = ServicePreferences.getOmemoAutoExpiry(this)

fun getLongPreference(name: String, res: Int): Long = ServicePreferences.getLongPreference(this, name, res)

fun getBooleanPreference(name: String, res: Int): Boolean = ServicePreferences.getBooleanPreference(this, name, res)

fun getStringPreference(name: String, res: Int): String = ServicePreferences.getStringPreference(this, name, res)

fun confirmMessages(): Boolean = ServicePreferences.confirmMessages(this)

fun allowMessageCorrection(): Boolean = ServicePreferences.allowMessageCorrection(this)

fun showTextFormatting(): Boolean = ServicePreferences.showTextFormatting(this)

fun sendChatStates(): Boolean = ServicePreferences.sendChatStates(this)

fun useTorToConnect(): Boolean = ServicePreferences.useTorToConnect(this)

fun useI2PToConnect(): Boolean = ServicePreferences.useI2PToConnect(this)

fun broadcastLastActivity(): Boolean = ServicePreferences.broadcastLastActivity(this)

fun unreadCount(): Int = ServicePreferences.unreadCount(this)

private fun <T> threadSafeList(set: MutableSet<T>): List<T> {
        synchronized(LISTENER_LOCK) {
            return if (set.isEmpty()) Collections.emptyList() else ArrayList(set)
        }
    }

fun showErrorToastInUi(resId: Int) = UiNotificationDispatch.showErrorToast(resId, threadSafeList(this.mOnShowErrorToasts))

fun updateConversationUi() = updateConversationUi(false)

fun updateConversationUi(newCaps: Boolean) = UiNotificationDispatch.updateConversation(newCaps,
                threadSafeList(this.mOnConversationUpdates))

    // Tulkki: the call-log listener type moved to `OnCallLogUpdated`, and its weak set, its
    // registration pair and its fan-out to `UiNotificationDispatch`. The monitor travels in — the Java guarded these three with `synchronized
    // (LISTENER_LOCK)`, so the Kotlin still locks that same object.
fun setOnCallLogUpdatedListener(listener: OnCallLogUpdated) = UiNotificationDispatch.addCallLogListener(listener, LISTENER_LOCK)

fun removeOnCallLogUpdatedListener(listener: OnCallLogUpdated) = UiNotificationDispatch.removeCallLogListener(listener, LISTENER_LOCK)

fun updateCallLogUi() = UiNotificationDispatch.updateCallLog(LISTENER_LOCK)

    // Tulkki: the bodies moved to `UiUpdateDispatch`. The
    // snapshot is still taken here, under `LISTENER_LOCK`, and handed in, so the listeners are
    // called outside the lock as before; `updateRosterUi`'s PRESENCE contract and the captcha
    // guard live in the Kotlin home.
    // Tulkki: C5-C - the ref, and with it `OnJingleRtpConnectionUpdate`'s own parameter. This is the
    // one C5-C widening that costs a *second* file: `:ui`'s `RtpSessionActivity` and `:app`'s
    // `ConnectionService` implement the listener, so their overrides move too. `:app` imports the ref
    // freely; `:ui` must write it fully qualified in the signature and import nothing, because a
    // `:ui -> :xmpp` import would move `ui-reaches-island` off its 241 (rounds 151/161).
fun notifyJingleRtpConnectionUpdate(account: AccountRef, with: Jid, sessionId: String, state: RtpEndUserState) = UiUpdateDispatch.notifyRtpConnection(
                account, with, sessionId, state, threadSafeList(this.onJingleRtpConnectionUpdate))

fun notifyJingleRtpConnectionUpdate(selectedAudioDevice: AudioDevice, availableAudioDevices: Set<AudioDevice>) = UiUpdateDispatch.notifyAudioDeviceChanged(
                selectedAudioDevice,
                availableAudioDevices,
                threadSafeList(this.onJingleRtpConnectionUpdate))

fun updateAccountUi() = UiUpdateDispatch.updateAccount(threadSafeList(this.mOnAccountUpdates))

fun updateRosterUi(reason: UpdateRosterReason) = updateRosterUi(reason, null)

    /**
     * Tulkki: the roster-change fan-out, in island vocabulary.
     *
     * <p>3.7 pair 9, part 11 gave this a `ContactRef` overload beside the model-typed one and left
     * `OnRosterUpdate` model-typed, because its three `:ui` implementors declared
     * `onRosterUpdate(UpdateRosterReason, Contact)`. Part 15 removed the model type's name from this
     * file, so the pair collapses into this one method and the interface below is retyped with it:
     * the three `:ui` implementations now spell `uk.xa0.tulkki.xmpp.refs.ContactRef` **fully qualified in
     * the signature and import nothing** (rounds 151/161), which keeps `ui-reaches-island` at 241
     * while `tools/fqn-refs` reports the three `:ui -> :xmpp` type hits honestly - a declared
     * direction, so not a blocker.
     */
fun updateRosterUi(reason: UpdateRosterReason, contact: ContactRef?) = UiUpdateDispatch.updateRoster(reason, contact, threadSafeList(this.mOnRosterUpdates))

    // Tulkki: C5-C - the ref, and with it `OnCaptchaRequested`'s own parameter. The body only forwards
    // the account to the listener, so nothing here needed the model type. The listener's one
    // non-island implementor is `:ui`'s `EditAccountActivity`, which takes the FQN-in-signature device
    // for the same 241 reason as the RTP listener above; `XmppActivity`'s two `instanceof` tests name
    // the *interface*, not its parameter, so they are untouched.
fun displayCaptchaRequest(account: AccountRef, id: String?, data: Data?, captcha: Bitmap): Boolean = UiUpdateDispatch.displayCaptchaRequest(
                getApplicationContext(),
                this.mOnCaptchaRequested.size > 0,
                account,
                id ?: throw NullPointerException("no captcha id"),
                data ?: throw NullPointerException("no captcha data"),
                captcha,
                threadSafeList(this.mOnCaptchaRequested))

fun updateBlocklistUi(status: OnUpdateBlocklist.Status) = UiUpdateDispatch.updateBlocklist(status, threadSafeList(this.mOnUpdateBlocklist))

fun updateMucRosterUi() = UiUpdateDispatch.updateMucRoster(threadSafeList(this.mOnMucRosterUpdate))

    // Tulkki: `report` is nullable, as it was before the port — see `UiUpdateDispatch.keyStatusUpdated`.
fun keyStatusUpdated(report: OmemoSessionPort.FetchStatus?) = UiUpdateDispatch.keyStatusUpdated(report, threadSafeList(this.mOnKeyStatusUpdated))

    // Tulkki: the bodies moved to `ConversationLookup`; the
    // signature, the null answer and the thread it runs on are unchanged.
fun findConversationByUuid(uuid: String?): ConversationRef? = ConversationLookup.byUuid(getConversationList(), uuid)

fun findUniqueConversationByJid(xmppUri: XmppUri): ConversationRef? = ConversationLookup.uniqueByJid(getConversationList(), xmppUri)

fun markRead(conversation: ConversationRef, dismiss: Boolean): Boolean = markRead(conversation, null, dismiss).size > 0

fun markRead(conversation: ConversationRef) = markRead(conversation, null, true)

fun markRead(conversation: ConversationRef, upToUuid: String?, dismiss: Boolean): List<MessageRef> = ReadMarkers.markRead(this, conversation, upToUuid, dismiss, mNotificationService, mDatabaseWriterExecutor)

fun markNotificationDismissed(messages: List<MessageRef>) = ReadMarkers.markNotificationDismissed(this, messages, mDatabaseWriterExecutor)

@Synchronized
fun updateUnreadCountBadge() = ReadMarkers.updateUnreadCountBadge(this)

fun sendReadMarker(conversation: ConversationRef, upToUuid: String?) = ReadMarkers.sendReadMarker(this, conversation, upToUuid, mNotificationService, mDatabaseWriterExecutor)

fun publishUserTuneAsync(metadata: MediaMetadata): Boolean = ReadMarkers.publishUserTuneAsync(this, metadata)

fun stopPublishingUserTuneAsync() = ReadMarkers.stopPublishingUserTuneAsync(this)

fun sendReactions(message: MessageRef, reactions: Collection<String>): Boolean = ReactionPublisher.sendReactions(this, message, reactions)

    // Tulkki: the bodies moved to `TrustState`; the names, the
    // visibility, the signatures and the guard order are unchanged. The memorising trust manager is
    // the chunk's own state and moved with it; the drawable cache stays (it is built by `onCreate`
    // and read by chunk C58), the database-writer executor goes in by hand, and `getApplicationContext`
    // is the Java's own argument.
fun getMemorizingTrustManager(): MemorizingTrustManager =
        TrustState.memorizingTrustManager()
            ?: throw NullPointerException("the memorizing trust manager is not installed")

fun setMemorizingTrustManager(trustManager: MemorizingTrustManager?) = TrustState.setMemorizingTrustManager(trustManager)

fun updateMemorizingTrustManager() = TrustState.updateMemorizingTrustManager(
                getApplicationContext(),
                trustPort(),
                decisionScreenPort(),
                appSettings.isTrustSystemCAStore())

fun syncRosterToDisk(account: AccountRef) = TrustState.syncRosterToDisk(this, account, mDatabaseWriterExecutor)

fun getDrawableCache(): LruCache<String, Drawable> =
        TrustState.drawableCache(this.mDrawableCache)
            ?: throw NullPointerException("the drawable cache is not built yet")

fun getKnownHosts(): Collection<String> = TrustState.knownHosts()

fun getKnownConferenceHosts(): Collection<String> = TrustState.knownConferenceHosts()

    // Tulkki: the bodies moved to `StanzaDispatch`; the names,
    // the visibility, the signatures and the guard order are unchanged. The presence generator, the
    // last-activity stamp and the push-management port are private state and go in by hand; chunk
    // C15's two private presence reads arrive as suppliers so their short-circuit is preserved.
fun sendMessagePacket(accountRef: AccountRef, packet: uk.xa0.tulkki.xmpp.models.stanza.Message) = StanzaDispatch.sendMessagePacket(accountRef, packet)

fun sendPresencePacket(accountRef: AccountRef, packet: uk.xa0.tulkki.xmpp.models.stanza.Presence) = StanzaDispatch.sendPresencePacket(accountRef, packet)

fun sendCreateAccountWithCaptchaPacket(account: AccountRef, id: String?, data: Data?) = StanzaDispatch.sendCreateAccountWithCaptchaPacket(account, id, data)

fun sendIqPacket(accountRef: AccountRef, packet: Iq, callback: Consumer<Iq>?) = StanzaDispatch.sendIqPacket(accountRef, packet, callback)

fun sendIqPacket(accountRef: AccountRef, packet: Iq, callback: Consumer<Iq>?, timeout: Long?) = StanzaDispatch.sendIqPacket(accountRef, packet, callback, timeout)

fun sendPresence(account: AccountRef) = StanzaDispatch.sendPresence(
                this,
                account,
                mPresenceGenerator,
                mLastActivity,
                this::manuallyChangePresence,
                this::getTargetPresence)

private fun sendPresence(account: AccountRef, includeIdleTimestamp: Boolean) = StanzaDispatch.sendPresence(
                this,
                account,
                includeIdleTimestamp,
                mPresenceGenerator,
                mLastActivity,
                this::manuallyChangePresence,
                this::getTargetPresence)

private fun deactivateGracePeriod() = StanzaDispatch.deactivateGracePeriod(this)

fun refreshAllPresences() = StanzaDispatch.refreshAllPresences(
                this,
                mPresenceGenerator,
                mLastActivity,
                this::manuallyChangePresence,
                this::getTargetPresence)

private fun refreshAllFcmTokens() = StanzaDispatch.refreshAllFcmTokens(
            this,
            mPushManagementService ?: throw NullPointerException("the push-management port is not installed"))

private fun sendOfflinePresence(account: AccountRef) = StanzaDispatch.sendOfflinePresence(account, mPresenceGenerator)

fun getMessageGenerator(): MessageGenerator = StanzaDispatch.messageGenerator(this.mMessageGenerator)

fun getPresenceGenerator(): PresenceGenerator = StanzaDispatch.presenceGenerator(this.mPresenceGenerator)

fun getIqGenerator(): IqGenerator = StanzaDispatch.iqGenerator(this.mIqGenerator)

    // Tulkki: the bodies moved to `HeldSubManagers`; the names,
    // the visibility, the signatures and the guard order are unchanged. The held sub-managers are
    // private state written by `onCreate`/`continueAfterDbInit` and read by chunks that stay, so
    // they go in by hand; the two port getters' `require` guard stays in chunk C76's shared helper
    // and is resolved on this side of the seam.
fun getJingleConnectionManager(): JingleConnectionManager = HeldSubManagers.jingleConnectionManager(this.mJingleConnectionManager)

private fun hasJingleRtpConnection(account: AccountRef): Boolean = HeldSubManagers.hasJingleRtpConnection(this.mJingleConnectionManager, account)

fun getMessageArchiveService(): MessageArchiveService = HeldSubManagers.messageArchiveService(this.mMessageArchiveService)

fun getContactListSyncService(): ContactListSyncPort = HeldSubManagers.contactListSyncService(
                PortAccessors.require(this.contactListSyncService, "contact-list sync"))

fun findContacts(jid: Jid, accountJid: String?): List<ContactRef> = HeldSubManagers.findContacts(jid, accountJid)

fun findFirstMuc(jid: Jid): ConversationRef? = HeldSubManagers.findFirstMuc(jid, getConversationList())

fun findFirstMuc(jid: Jid, accountJid: String?): ConversationRef? = HeldSubManagers.findFirstMuc(jid, accountJid, getConversationList())

fun getNotificationService(): NotificationPort = HeldSubManagers.notificationService(
                PortAccessors.require(this.mNotificationService, "notification"))

fun getHttpConnectionManager(): HttpConnectionManager = HeldSubManagers.httpConnectionManager(this.mHttpConnectionManager)

fun resendFailedMessages(message: MessageRef, forceP2P: Boolean) = HeldSubManagers.resendFailedMessages(this, message, forceP2P, this::sendMessage)

    // Tulkki: the bodies moved to `ConversationHistory`; the
    // names, the signatures, the visibility and the guard order are unchanged. The private
    // database-writer executor (C02) and the live `conversationList` (C29) go in by hand, and the
    // attachment executor is passed as a value rather than read from C09's static field.
fun clearConversationHistory(conversation: ConversationRef) = ConversationHistory.clearConversationHistory(
                this, conversation, mDatabaseWriterExecutor, FILE_ATTACHMENT_EXECUTOR)

private fun deleteFilesAsync(exclusiveFilePaths: List<String>, jid: String) = ConversationHistory.deleteFilesAsync(this, exclusiveFilePaths, jid)

fun sendBlockRequest(account: AccountRef?, blockedJid: Jid?, reportSpam: Boolean, serverMsgId: String?): Boolean = ConversationHistory.sendBlockRequest(
                this, account, blockedJid, reportSpam, serverMsgId, this.conversationList)

fun removeBlockedConversationEntries(accountRef: AccountRef, blockedJid: Jid): Boolean = ConversationHistory.removeBlockedConversationEntries(
                this, accountRef, blockedJid, this.conversationList)

fun sendUnblockRequest(account: AccountRef?, jid: Jid?, blockedJid: Jid?) = ConversationHistory.sendUnblockRequest(this, account, jid, blockedJid)

    // Tulkki: C5-C - the ref, for the same reason as `cancelAvatarFetches`. `AvatarPort.clear` already
    // had an `AccountRef` overload and `sendIqPacket` already took the ref, so the body is unchanged.
fun publishDisplayName(account: AccountRef) = ServiceDiscovery.publishDisplayName(this, account)

fun getCachedServiceDiscoveryResult(key: Pair<String, String>): ServiceDiscoveryResultRef? = ServiceDiscovery.getCachedServiceDiscoveryResult(this, key)

fun fetchFromGateway(account: AccountRef, jid: Jid, input: String?, callback: OnGatewayResult) = ServiceDiscovery.fetchFromGateway(this, account, jid, input, callback)

    /**
     * Tulkki: 3.7 C5-D - one ref-typed pair where there were three overloads.
     *
     * <p>Part 11 carried a model-typed method beside a ref-typed one and cast in the delegation,
     * because a parameter type is not covariant and the callers sat on different sides of the
     * boundary (`PresenceParser` holds a `PresenceRef` straight out of `DataStatics.parsePresence`;
     * `ConversationFragment` holds the model from `getPresencesMap()`). With the model name gone from
     * this file the pair is the same method twice, so the model half and the casting delegation are
     * both deleted and one ref-typed method remains - both kinds of caller still fit, because
     * `Presence implements PresenceRef`. The `Account` cast went too: the body only ever read
     * `getRoster()`, `getJid()` and the ref-typed `sendIqPacket`/`syncRoster`, all of which the ref
     * answers, so the parameter keeps the name `account` and nothing else in the body moved.
     */
fun fetchCaps(accountRef: AccountRef, jid: Jid, presence: PresenceRef?) = ServiceDiscovery.fetchCaps(this, accountRef, jid, presence)

fun fetchCaps(account: AccountRef, jid: Jid, presence: PresenceRef?, cb: Runnable?) = ServiceDiscovery.fetchCaps(this, account, jid, presence, cb)

fun fetchCommands(account: AccountRef, jid: Jid, callback: Consumer<Iq>) = ServiceDiscovery.fetchCommands(this, account, jid, callback)

    /**
     * Tulkki: 3.7 pair 9, part 14. The island's only declared use of the *type* `Roster` - every other
     * mention is `account.getRoster().member(...)`, where the receiver's static type already answers
     * the model - so retyping this one parameter is what lets the `Roster` import go. The body reads a
     * roster through refs: contacts, their presence sets, the two caps fields, and the account the
     * write is synced for. `disco` is a ref too now, so nothing in the signature or the body names a
     * `:data` type.
     */
private fun injectServiceDiscoveryResult(roster: RosterRef, hash: String?, ver: String?, resource: String?, disco: ServiceDiscoveryResultRef) = ServiceDiscovery.injectServiceDiscoveryResult(this, roster, hash, ver, resource, disco)

fun fetchMamPreferences(account: AccountRef, callback: OnMamPreferencesFetched) = ServiceDiscovery.fetchMamPreferences(this, account, callback)

fun getPushManagementService(): PushManagementPort = ServiceDiscovery.pushManagementService(
                PortAccessors.require(this.mPushManagementService, "push registration"))

    // Tulkki: the bodies moved to `AccountMaintenance`; the
    // names, the visibility, the signatures and the guard order are unchanged. The drawable cache
    // (chunk C53), the shortcut port (chunk C71, through chunk C76's shared `require`) and the
    // account-update listener set (chunk C34) go in by hand; the database backend is the service's
    // public field.
    // Tulkki: `signature` is nullable, as it was before the port — see `AccountMaintenance.changeStatus`.
fun changeStatus(account: AccountRef, template: PresenceTemplateRef, signature: String?) = AccountMaintenance.changeStatus(this, account, template, signature)

fun getPresenceTemplates(account: AccountRef): List<PresenceTemplateRef> = AccountMaintenance.presenceTemplates(this, account)

    // Tulkki: `name` is nullable, as it was before the port — see `AccountMaintenance.saveConversationAsBookmark`.
fun saveConversationAsBookmark(conversation: ConversationRef, name: String?) = AccountMaintenance.saveConversationAsBookmark(this, conversation, name)

fun verifyFingerprints(contact: ContactRef, fingerprints: List<XmppUri.Fingerprint>): Boolean = AccountMaintenance.verifyContactFingerprints(this, contact, fingerprints)

fun verifyFingerprints(account: AccountRef, fingerprints: List<XmppUri.Fingerprint>): Boolean = AccountMaintenance.verifyAccountFingerprints(account, fingerprints)

fun blindTrustBeforeVerification(): Boolean = AccountMaintenance.blindTrustBeforeVerification(this)

fun getShortcutService(): ShortcutPort = AccountMaintenance.shortcutService(
                PortAccessors.require(this.mShortcutService, "launcher shortcut"))

fun pushMamPreferences(account: AccountRef, prefs: Element) = AccountMaintenance.pushMamPreferences(this, account, prefs)

fun evictPreview(f: File?) = AccountMaintenance.evictPreview(
            mDrawableCache ?: throw NullPointerException("the drawable cache is not built yet"),
            f)

fun evictPreview(uuid: String?) = AccountMaintenance.evictPreview(
            mDrawableCache ?: throw NullPointerException("the drawable cache is not built yet"),
            uuid ?: throw NullPointerException("no uuid"))

fun updateAccountOrder() = AccountMaintenance.updateAccountOrder(this, this.mOnAccountUpdates)



    // Tulkki: the seventeen callback interfaces moved to top-level files of their own in this
    // package. They are pure declarations, so Java rather than
    // Kotlin keeps the Java-visible nullability byte for byte, and `OnConversationUpdate`'s two
    // `default` methods need the Java spelling. Every outside spelling moved in the same commit.

    // Tulkki: the binder, the two event receivers and the three value types moved to top-level
    // files of their own in this package under the owner's
    // 2026-10-08 licence. `XmppConnectionBinder` was an inner class and the receivers were private
    // inner classes, so each takes the service it forwarded to as a constructor argument; a private
    // nested class cannot be hoisted without becoming package-private, and that is the only
    // visibility that moved. The null-guarded `toggleForegroundService(XmppConnectionService)`
    // static went to `ForegroundServiceLifecycle` and the interface's `XmppConnectionService.*`
    // call sites moved with it in the same commit.

fun colored_muc_names(): Boolean = ServicePreferences.coloredMucNames(this)

fun publishVCard4(account: AccountRef, vcardElement: Element) = StoryPublication.publishVCard4(this, account, vcardElement)

fun publishStory(account: AccountRef, url: String, type: String, title: String, callback: UiCallbackPort<Void?>?) = StoryPublication.publishStory(this, account, url, type, title, callback)



fun uploadFileForUrl(account: AccountRef?, uri: android.net.Uri, mimeType: String?, callback: UiCallbackPort<String>) = StoryPublication.uploadFileForUrl(
            this, account, uri, mimeType, callback, attachFile(), transcoderStrategies(), FILE_ATTACHMENT_EXECUTOR)

    // Tulkki: 3.7 pair 9, part 15 - the story cache is island vocabulary now, like the contact
    // roster beside it. `getStories()` is read by `:ui` (five screens and two adapters), so the
    // retype reaches `:ui`: every one of those files writes `uk.xa0.tulkki.libs.StoryRef` fully
    // qualified and imports nothing (rounds 151/161), which is what keeps `ui-reaches-island` at 241
    // while `tools/fqn-refs` reports the `:ui -> :xmpp` type hits honestly.
    //
    // Tulkki: the cache, its listener registry and the fetch/retract/cleanup bodies moved to
    // `StoryCache`, and the listener contract to `OnStoriesUpdate`. The names and signatures stay on the service so no call site moves. The live list and
    // the weak listener set moved whole onto the Kotlin object - the same per-instance-to-object
    // move C06's `mutedMucUsers` and C65's comment list already made. `mDatabaseWriterExecutor`
    // (C74) and the `LISTENER_LOCK` monitor travel in by value, so neither visibility is widened
    // and the story set is still excluded from a registration by the same object
    // `ListenerRegistration` locks; C62's two scheduled executors stay here because C20's
    // `StartupScheduling` and C22's `Teardown` are what schedule on them, and neither pass reads
    // its own executor.
fun getStories(): MutableList<StoryRef> = StoryCache.stories()

fun onStoryReceived(storyRef: StoryRef?) = StoryCache.onStoryReceived(this, mDatabaseWriterExecutor, storyRef, LISTENER_LOCK)

fun setOnStoriesUpdateListener(listener: OnStoriesUpdate) = StoryCache.setListener(listener, LISTENER_LOCK)

fun removeOnStoriesUpdateListener(listener: OnStoriesUpdate) = StoryCache.removeListener(listener, LISTENER_LOCK)

fun updateStoriesUi() = StoryCache.updateStoriesUi(LISTENER_LOCK)

fun fetchStories(account: AccountRef, contact: ContactRef) = StoryCache.fetchStories(this, account, contact, mDatabaseWriterExecutor, LISTENER_LOCK)

fun fetchOwnStories(account: AccountRef) = StoryCache.fetchOwnStories(this, account, mDatabaseWriterExecutor, LISTENER_LOCK)

fun retractStory(account: AccountRef, storyId: String, callback: UiCallbackPort<Void?>?) = StoryCache.retractStory(
                this, account, storyId, callback, mDatabaseWriterExecutor, LISTENER_LOCK)

fun onStoryRetracted(storyId: String?) = StoryCache.onStoryRetracted(this, storyId, mDatabaseWriterExecutor, LISTENER_LOCK)

fun retractOldStories() = StoryCache.retractOldStories(this, mDatabaseWriterExecutor, LISTENER_LOCK)

fun cleanupStoryCache() = StoryCache.cleanupStoryCache(this)

    // Tulkki: both bodies and the nested listener type moved to `ContactFeeds` and
    // `OnPubsubItemsFetched`. The names and signatures stay on
    // the service so no call site moves; both bodies reached only public members, so the service
    // travels in whole rather than any field.
fun updateContact(contact: ContactRef) = ContactFeeds.updateContact(this, contact)

fun fetchPubsubItems(server: Jid?, node: String, callback: OnPubsubItemsFetched?) = ContactFeeds.fetchPubsubItems(this, server, node, callback)

fun publishPost(account: AccountRef?, title: String?, content: String?, attachmentUrl: String?, attachmentType: String?, postId: String?, linkUrl: String?, callback: OnPostPublished?) {
        if (account == null) {
            if (callback != null) {
                callback.onPostPublishFailed()
            }
            return
        }

        val isEdit = postId != null
        val idToPublish = if (isEdit) postId else UUID.randomUUID().toString()

        val publicationRunnable =
            Runnable {
                val request =
                    getIqGenerator().publishPost(
                        account, title, content, attachmentUrl, attachmentType, idToPublish, linkUrl)
                sendIqPacket(account, request) { response2 ->
                    if (response2.getType() == Iq.Type.RESULT) {
                        if (!isEdit) {
                            sendIqPacket(account, getIqGenerator().createCommentsNode(idToPublish)) { response3 ->
                                if (response3.getType() != Iq.Type.RESULT) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "could not create comments node for post " + idToPublish + ". " + response3)
                                }
                            }
                        }
                        if (callback != null) {
                            callback.onPostPublished()
                        }
                    } else {
                        Log.e(Config.LOGTAG, "Could not publish post. Server responded with: " + response2)
                        if (callback != null) {
                            callback.onPostPublishFailed()
                        }
                    }
                }
            }

        if (!isEdit) {
            val createRequest = getIqGenerator().createSocialFeedNode()
            sendIqPacket(account, createRequest) { response ->
                if (response.getType() != Iq.Type.RESULT) {
                    val error = response.findChild("error")
                    if (error == null || !error.hasChild("conflict")) {
                        Log.d(Config.LOGTAG, "could not create social feed node " + response)
                    }
                }
                publicationRunnable.run()
            }
        } else {
            publicationRunnable.run()
        }
    }

fun publishComment(account: AccountRef?, nodeUri: String?, title: String?, callback: OnPostPublished?) {
        if (account == null) {
            if (callback != null) {
                callback.onPostPublishFailed()
            }
            return
        }
        val to: Jid?
        val targetNode: String?
        try {
            val uri = uk.xa0.tulkki.xmpp.utils.XmppUri(nodeUri!!)
            to = uri.getJid()
            targetNode = uri.getParameter("node")
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "Invalid URI in publishComment: " + nodeUri, e)
            if (callback != null) {
                callback.onPostPublishFailed()
            }
            return
        }

        if (to == null || targetNode == null) {
            Log.e(Config.LOGTAG, "Could not determine target for publishing comment")
            if (callback != null) {
                callback.onPostPublishFailed()
            }
            return
        }

        val createRequest =
            getIqGenerator().createCommentsNode(targetNode ?: throw NullPointerException("no target node"))
        createRequest.setTo(to) // Comments node might be on a different server
        sendIqPacket(account, createRequest) { response ->
            if (response.getType() != Iq.Type.RESULT) {
                val error = response.findChild("error")
                if (error == null || !error.hasChild("conflict")) {
                    Log.d(Config.LOGTAG, "could not create comments node " + response)
                }
            }
            val publishRequest = getIqGenerator().publishComment(
                account, targetNode, title ?: throw NullPointerException("no comment title"))
            publishRequest.setTo(to)
            sendIqPacket(account, publishRequest) { publishResponse ->
                if (publishResponse.getType() == Iq.Type.RESULT) {
                    if (callback != null) {
                        callback.onPostPublished()
                    }
                } else {
                    Log.e(Config.LOGTAG, "Could not publish comment. Server responded with: " + publishResponse)
                    if (callback != null) {
                        callback.onPostPublishFailed()
                    }
                }
            }
        }
    }

fun retractPost(account: AccountRef?, node: String?, id: String?, callback: OnPostRetracted?) {
        if (account == null) {
            if (callback != null) {
                callback.onPostRetractionFailed()
            }
            return
        }
        val request = getIqGenerator().retractPost(
            node ?: throw NullPointerException("no post node"),
            id ?: throw NullPointerException("no post id"))
        sendIqPacket(account, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                if (callback != null) {
                    callback.onPostRetracted(id)
                }
            } else {
                if (callback != null) {
                    callback.onPostRetractionFailed()
                }
            }
        }
    }


fun retractPost(account: AccountRef?, to: Jid?, node: String?, id: String?, callback: OnPostRetracted?) {
        val packet = getIqGenerator().retractPost(
            node ?: throw NullPointerException("no post node"),
            id ?: throw NullPointerException("no post id"))
        packet.setTo(to)
        this.sendIqPacket(account ?: throw NullPointerException("no account"), packet) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                if (callback != null) {
                    callback.onPostRetracted(id)
                }
            } else {
                if (callback != null) {
                    callback.onPostRetractionFailed()
                }
            }
        }
    }

interface OnPostPublished {
    fun onPostPublished()
    fun onPostPublishFailed()
}

    /**
     * Tulkki: 3.7 C5-D - the parameter is the island's ref. The implementation in `:ui` names it
     * fully qualified in the signature and imports nothing, so `ui-reaches-island` stays at 241.
     */
interface OnPostReceived {
    fun onPostReceived(post: PostRef)
}

interface OnPostRetracted {
    fun onPostRetracted(postId: String)
    fun onPostRetractionFailed()
}

private val mOnPostReceivedListeners: MutableSet<OnPostReceived> =
        Collections.newSetFromMap(WeakHashMap<OnPostReceived, Boolean>())
private val mOnPostRetractedListeners: MutableSet<OnPostRetracted> =
        Collections.newSetFromMap(WeakHashMap<OnPostRetracted, Boolean>())


fun addOnPostReceivedListener(listener: OnPostReceived) {
        synchronized(LISTENER_LOCK) {
            mOnPostReceivedListeners.add(listener)
        }
    }

fun removeOnPostReceivedListener(listener: OnPostReceived) {
        synchronized(LISTENER_LOCK) {
            mOnPostReceivedListeners.remove(listener)
        }
    }

fun addOnPostRetractedListener(listener: OnPostRetracted) {
        synchronized(LISTENER_LOCK) {
            mOnPostRetractedListeners.add(listener)
        }
    }

fun removeOnPostRetractedListener(listener: OnPostRetracted) {
        synchronized(LISTENER_LOCK) {
            mOnPostRetractedListeners.remove(listener)
        }
    }

fun onPostReceived(postRef: PostRef?, accountRef: AccountRef?) {
        // Tulkki: 3.7 C5-D - no cast left: the parameter was already the ref, the listener takes it,
        // and `DatabaseBackend.createPost` was widened to the refs so the model is named only in
        // `:data`, where the one conversion belongs.
        if (postRef == null || accountRef == null) {
            return
        }
        (databaseBackend ?: throw NullPointerException("database backend is not open"))
            .createPost(postRef, accountRef)
        for (listener in threadSafeList(mOnPostReceivedListeners)) {
            listener.onPostReceived(postRef)
        }
    }

fun onPostRetracted(postId: String?) {
        if (postId == null) {
            return
        }
        (databaseBackend ?: throw NullPointerException("database backend is not open")).deletePost(postId)
        for (listener in threadSafeList(mOnPostRetractedListeners)) {
            listener.onPostRetracted(postId)
        }
    }

fun subscribeTo(account: AccountRef, to: Jid, node: String, callback: Consumer<Iq>) {
        val iq = getIqGenerator().generateSubscriptionIq(to.asBareJid(), node, account.getJid().asBareJid())
        sendIqPacket(account, iq, callback)
    }

fun unsubscribeFrom(account: AccountRef, to: Jid, node: String, callback: Consumer<Iq>) {
        val iq = getIqGenerator().generateUnsubscriptionIq(to.asBareJid(), node, account.getJid().asBareJid())
        sendIqPacket(account, iq, callback)
    }

    // Tulkki: the listener type, the list and the three bodies moved to `OnCommentReceived` and
    // `CommentNotifications`, which is why the nested type is
    // gone rather than delegating. The names and signatures stay on the service so no call site
    // moves; the 3.7 C5-D note (the cast is gone rather than moved, because nothing in the body
    // needed the model) travels with the body.
fun addOnCommentReceivedListener(listener: OnCommentReceived) = CommentNotifications.add(listener)

fun removeOnCommentReceivedListener(listener: OnCommentReceived) = CommentNotifications.remove(listener)

fun notifyOnCommentReceived(originalPostUuid: String?, commentRef: CommentRef?) = CommentNotifications.notify(originalPostUuid, commentRef)

    // ---------------------------------------------------------------------------------------------
    // Tulkki's boundary: the ports this island declares, and the composition root that implements
    // them. Everything below is one block on purpose - it is the only part of this file Tulkki's
    // module boundary owns, so a writer changing the rest of the file does not have to touch it. The
    // ports live *here* rather than in files of their own because this file already imports the model
    // types their signatures name: nesting adds no import site, while a new :xmpp file naming
    // uk.xa0.tulkki.data.model.Conversation or .Message would grow the ratchet by one site each.
    // Tulkki: the ports have since moved to files of their own in this package (see the note
    // below), and the rationale the paragraph names is gone with the model types: the refs
    // replaced `Conversation`/`Message` in every one of these signatures, so no port names a
    // `:data` type at all and nesting bought nothing.
    // ---------------------------------------------------------------------------------------------


    /**
     * Tulkki: this island's ports as one installation, so the composition root installs them once and
     * each service takes its own in {@code onCreate}.
     *
     * <p>The factory is installed at process start ({@code uk.xa0.tulkki.app.TulkkiApplication.onCreate}),
     * which Android runs before any {@code Service.onCreate} in the process - so by the time this
     * service exists the factory is there, and a null {@link #sendGate} can only mean a build that
     * forgot the install. That case is named loudly where the gate is used rather than passing as
     * "this message needed no translation".
     */

    // Tulkki: the eight port interfaces that used to be declared here (SendGate, TrustPort,
    // PgpEnginePort, PgpEngineFactory, PgpDecryptionPort, OtrPeerPort, OmemoSettingsPort,
    // UiCallbackPort) moved to top-level files of their own in this package
    // under the owner's 2026-10-08 licence. They are pure
    // declarations, so Java rather than Kotlin keeps the Java-visible nullability byte for byte;
    // every outside spelling moved in the same commit.

    // ---------------------------------------------------------------------------------------------
    // Pair 11 of docs/MIGRATION.md "The cycle rules" §3 (D4): the nine `:ui` names this island used
    // to carry.
    //
    // Five of them are *continuations* - the caller hands the island something to call back - and are
    // therefore declared here as twins of the `:ui` interfaces, exactly as pair 6 declared
    // `PgpCallback` for `UiCallback`: the `:ui` interface extends the twin with a fully-qualified
    // name (an `import` line would be a new `ui-reaches-island` site, which this pair's gate holds at
    // 242), and not one method or call site moved. Four are things the island *asks for* - a hook
    // into the live-location manager, the call screen, the theme and the profile-picture activity -
    // and arrive as instances from `TulkkiPorts`. Every signature is either a primitive, a JDK type,
    // or a type this file already imports, which is what nesting costs nothing.
    //
    // Where a carrier was pure data rather than an action, it moved instead of becoming a port:
    // `uk.xa0.tulkki.data.utils.Attachment` (pair 8b's remaining `:data` -> `:ui` site) and
    // `uk.xa0.tulkki.data.utils.EmoticonText.existingVariant`. The two `:data` helpers read from here
    // (`EmoticonText`, `GeoUris`, `QuoteHelper`) are written in the body rather than imported, so
    // this commit adds no import site in either direction; the 3.7 pair-11 commit 079c6991a2
    // lists them for pair 9 to retype with the rest of D2.
    // Tulkki: C5-E3 has since done the first half of that: the `Attachment` import this paragraph
    // describes is gone, `deleteMedia` and `MediaLoadedHook` take `uk.xa0.tulkki.libs.AttachmentRef`
    // (`List<? extends>` - the wildcard is forced, see the hook), and the objects on the wire are
    // still the model's. The paragraph is kept as written because it is the record of where the
    // carrier came from.
    // Tulkki: part 14 has since taken `EmoticonText.isEmoji` and `.existingVariant` off the body and
    // onto `DataStatics`, so the helpers still written here are `GeoUris` and `QuoteHelper`.
    // ---------------------------------------------------------------------------------------------


    // Tulkki: the four hook interfaces (LiveLocationHook, MediaLoadedHook, AvatarPublicationHook,
    // SearchResultsHook) moved to top-level files of their own in this package under the owner's 2026-10-08 licence. They are pure declarations, so Java rather
    // than Kotlin keeps the Java-visible nullability byte for byte; every outside spelling moved in
    // the same commit.

    // Tulkki: the twenty-five port interfaces that used to be declared here - every seam this
    // island asks `:app`/`:ui` to fill, from `RtpSessionPort` and `CompatibilityPort` to
    // `CallIntegrationFactory` and the `TulkkiPorts` aggregate - moved to top-level files of their
    // own in this package. They are pure declarations with no body and no field, so Java rather
    // than Kotlin keeps the Java-visible nullability byte for byte and every SAM call site (the
    // factory lambda, the message-search adapter) keeps its shape; the nested `Factory`, `Callback`,
    // `Transport` and `PushTarget` markers travelled inside their interface. The installer and the
    // accessors below keep their plain names because `:app` and the tests name them, and every
    // outside spelling moved in this commit.



    // ---------------------------------------------------------------------------------------------
    // Pair 4 of docs/MIGRATION.md "The cycle rules" §3 (D5): the `:app` names this island used to
    // carry.
    //
    // Pair 4's shape is the same one pairs 6, 7 and 11 used on their own directions: every carrier
    // the island merely *asks for* becomes an interface declared here and implemented in `:app`,
    // reached through the one factory the composition root installs. The island keeps the decisions
    // and the vocabulary; `:app` keeps the Android work. Nothing here is a `:data` type this file
    // did not already import - where a signature needs one (`ListItem`, `Avatarable`, `Room`,
    // `Emoji`) the name is written in the body rather than imported, because an `import` line in an
    // island file naming a non-island module is the one rule `allow` can never legalise.
    //
    // The carriers that are *held instances* (`AvatarPort`, `NotificationPort`, `ShortcutPort`,
    // `UnifiedPushPort`, `PushManagementPort`, `ContactListSyncPort`, `ChannelDiscoveryPort`,
    // `FileObserverPort`) are built by the composition root's factory instead of
    // in this file's field initialisers: this file may not name the classes, and the factory is the
    // one place that may. Their accessors fail loudly rather than returning null, the ruling round
    // 155 wrote down - a missing install is a build fault, and a silent no-op here would look like
    // ordinary behaviour (no notification, no avatar, no shortcut) and quietly change which code the
    // JVM tests exercise.
    //
    // Not ports, and why: `uk.xa0.tulkki.app.utils.Cancellable` is a type the island *implements* rather
    // than calls, so it moved into the island (`uk.xa0.tulkki.xmpp.utils.Cancellable`) and its one `:app`
    // user (`MessageSearchTask`) now names the island's; `TLSSocketFactory`'s
    // `ContactListSyncService` import was dead and is deleted; `TulkkiApplication` and
    // `ExceptionHelper` were dead imports here too.
    // ---------------------------------------------------------------------------------------------






















    /**
     * Tulkki: the `:data` entry points the island reached as statics, in island vocabulary.
     *
     * <p>3.7 pair 9, cluster (b) - commit 167beb22f8 §2.3. A static cannot be carried by an
     * interface the model implements, and several of these callers have no service to ask
     * (`WebRTCDataChannelTransport`, `JingleFileTransferConnection`, `JingleRtpConnection`,
     * `TLSSocketFactory`, `Resolver`), so this port is installed **statically** at process start, the
     * way pair 7's `TrustPort` is and for the same reason. {@link #installDataStatics} is the one
     * installer; {@link #dataStatics()} fails loudly while the slot is empty.
     *
     * <p>The preference keys are compile-time `String` constants copied from `AppSettings`, which is
     * safe because they are the storage contract rather than state: `getBooleanPreference` compares
     * them by value, and a mismatch would fail a preferences read visibly rather than quietly. They
     * are declared here rather than on `AppSettingsRef` because they are statics and this port is
     * where the island's statics live.
     */
    // ---------------------------------------------------------------------------------------------
    // 3.7 pair 9, part 12: the overloads `MessageParser` needed. **Part 17 emptied this block.**
    //
    // `MessageParser` was the third and last parser to leave its `:data` imports: an island method
    // that takes the model type does not accept the ref the island holds, because a parameter type is
    // not covariant in Java, so every method here existed as a ref-typed adapter that cast once and
    // delegated. Part 16 deleted five of them once their model-typed twins were retyped. Part 17
    // retyped the rest of this file, so the remaining eleven are the same method twice and javac
    // rejects them as duplicates; the whole block is gone. C5-E2 retyped the last model-typed twin,
    // so this is now the only `processModifiedBookmark(BookmarkRef)` and it *is* the entry point
    // (`MessageParser:441` is its caller) - it forwards to the private `(BookmarkRef, boolean)`.
    // ---------------------------------------------------------------------------------------------

fun processModifiedBookmark(bookmark: BookmarkRef) = processModifiedBookmark(bookmark, true)

interface DataStatics {
    fun settings(context: Context): AppSettingsRef
    fun accounts(): AccountRegistryRef
    fun secureDomains(): Set<Jid>
    fun clearSessionPassword()
    fun guessMimeTypeFromUriAndMime(context: Context, uri: Uri, type: String?): String?
    fun guessExtensionFromMimeType(mimeType: String): String?
    fun extractRelevantExtension(path: String): String?
    fun close(closeable: Closeable?)
    fun fileSize(context: Context, uri: Uri): Long
    fun newFileBackend(service: XmppConnectionService): FileBackendRef
    fun openDatabase(context: Context): DatabaseBackendRef
    fun closeDatabase()
    fun requiresMessageIndexRebuild(): Boolean
    fun loadAttachments(databaseBackend: DatabaseBackendRef, account: String?, jid: Jid?, query: String?, limit: Int, onMediaLoaded: uk.xa0.tulkki.xmpp.services.MediaLoadedHook)
    fun warmUpUnifiedPushDatabase(context: Context)
    fun quickLoad(haystack: List<out ConversationalRef>): ConversationalRef?
    fun prepareQuote(message: MessageRef, start: Int, end: Int): String
    fun quote(body: String): String
    fun autolinkWebUrl(): Pattern
    fun geoUri(): Pattern
    fun newUser(options: MucOptionsRef, fullJid: Jid?, occupantId: String?, nickname: String?, hats: Set<out MucOptionsRef.HatRef>?): MucOptionsRef.UserRef
    fun newHat(hat: Element): MucOptionsRef.HatRef
    fun hatOrder(): Comparator<MucOptionsRef.HatRef>
    fun newComment(item: Element): CommentRef?
    fun newPost(item: Element): PostRef?
    fun parseStories(pubsub: Element, contact: Jid): List<StoryRef>
    fun newStory(item: Element, contact: Jid): StoryRef?
    fun newMessage(conversation: ConversationalRef, body: String?, encryption: Int, status: Int): MessageRef
    fun parsePresence(show: String?, caps: Element?, message: String?): PresenceRef
    fun newOfflinePresence(): PresenceRef
    fun newServiceDiscoveryResult(packet: Iq): ServiceDiscoveryResultRef
    fun newTransferablePlaceholder(status: Int): Transferable
    fun newDownloadableFile(parent: File, name: String): DownloadableFileRef
    fun isEmoji(input: String): Boolean
    fun existingVariant(original: String, existing: Set<String>): String
    fun newMessage(conversation: ConversationRef, status: Int, type: Int, remoteMsgId: String?): MessageRef
    fun newMessage(conversation: ConversationalRef, body: String?, encryption: Int): MessageRef
    fun newConversation(name: String, account: AccountRef, contactJid: Jid, mode: Int): ConversationRef
    fun configurePrivateMessage(message: MessageRef)
    fun configurePrivateMessage(message: MessageRef, counterpart: Jid)
    fun configurePrivateFileMessage(message: MessageRef): Boolean
    fun newFileParams(element: Element): MessageRef.FileParamsRef
    fun newRtpSessionStatus(successful: Boolean, duration: Long): String
    fun parseBookmarksFromStorage(storage: Element, account: AccountRef): Map<Jid, BookmarkRef>
    fun parseBookmarkFromItem(item: Element, account: AccountRef): BookmarkRef?
    fun parseBookmarksFromPubSub(pubSub: Element, account: AccountRef): Map<Jid, BookmarkRef>
    fun newBookmark(account: AccountRef, jid: Jid): BookmarkRef
    fun loadJabberIdContacts(context: Context): Map<Jid, AbstractPhoneContact>
    fun defaultNick(account: AccountRef): String?
    fun reactionsWithOccupantId(existing: Collection<out ReactionRef>, reactions: Collection<String>, received: Boolean, from: Jid?, trueJid: Jid?, occupantId: String?, envelopeId: String?): Collection<ReactionRef>
    fun reactionsWithFrom(existing: Collection<out ReactionRef>, reactions: Collection<String>, received: Boolean, from: Jid?, envelopeId: String?): Collection<ReactionRef>

    companion object {
        @JvmField val KEEP_FOREGROUND_SERVICE: String = "enable_foreground_service"
        @JvmField val AWAY_WHEN_SCREEN_IS_OFF: String = "away_when_screen_off"
        @JvmField val TREAT_VIBRATE_AS_SILENT: String = "treat_vibrate_as_silent"
        @JvmField val DND_ON_SILENT_MODE: String = "dnd_on_silent_mode"
        @JvmField val MANUALLY_CHANGE_PRESENCE: String = "manually_change_presence"
        @JvmField val BLIND_TRUST_BEFORE_VERIFICATION: String = "btbv"
        @JvmField val AUTOMATIC_MESSAGE_DELETION: String = "automatic_message_deletion"
        @JvmField val BROADCAST_LAST_ACTIVITY: String = "last_activity"
        @JvmField val OMEMO_AUTO_EXPIRY: String = "omemo_auto_expiry"
    }
}

    // Tulkki: `TulkkiPorts` itself moved to a file of its own with the ports above.

private var wakeLockPort: WakeLockPort? = null
private var sendGate: SendGate? = null
private var translationQueue: TranslationQueue? = null

    /**
     * S5-4: the sync engine's event seam, held here as well as installed in the archive service,
     * because the session-end and CSI events are this class's. Null only before `onCreate` has taken
     * the factory, which is a build fault rather than a runtime state.
     */
private var syncEvents: SyncEvents? = null
private var liveLocationHook: LiveLocationHook? = null
private var rtpSessionPort: RtpSessionPort? = null
private var themePort: ThemePort? = null
private var profilePictureActivityPort: ProfilePictureActivityPort? = null
private var decisionScreenPort: MemorizingTrustManager.DecisionScreenPort? = null
    // Pair 4's held instances. They are assigned in onCreate, from the factory, because this file
    // may not name the classes that build them; each accessor below fails loudly while a slot is
    // empty rather than returning null into a path that would look like ordinary behaviour.
private var compatibility: CompatibilityPort? = null
private var phoneHelper: PhoneHelperPort? = null
private var transcoderStrategies: TranscoderStrategiesPort? = null
private var systemEvent: SystemEventPort? = null
private var messageSearch: MessageSearchPort? = null
private var contactListSync: ContactListSyncPort? = null
private var attachFile: AttachFilePort? = null
private var fileWatcher: FileObserverPort.Watcher? = null
private var avatar: AvatarPort? = null
private var notification: NotificationPort? = null
private var channelDiscovery: ChannelDiscoveryPort? = null
private var shortcuts: ShortcutPort? = null
private var unifiedPush: UnifiedPushPort? = null
private var pushManagement: PushManagementPort? = null
    /** The factory's whole answer, kept so pair 11's callback adapter can be asked for by name. */
private var tulkkiPorts: TulkkiPorts? = null

    // Tulkki: the body moved to `PortGuards`; the instance slot
    // stays with chunk C74's fields and travels in by value.
private fun omemoSettings(): OmemoSettingsPort = PortGuards.omemoSettings(this.omemoSettings)

    // Tulkki: the body moved to `PortGuards`; the instance slot
    // stays with chunk C74's fields and travels in by value.
private fun sendGate(): SendGate = PortGuards.sendGate(this.sendGate)

    // Tulkki: the bodies below moved to `PortAccessors`; every
    // slot stays with chunk C74's fields and travels in by value, so each method keeps the single
    // read and its own visibility. The shared `require` guard moved with the chunk.

    /**
     * The translation queue's one question, asked from the parse layer.
     *
     * <p>Pair 10's ruling holds here too and matters more than it looks: {@code MessageParser} runs on
     * the socket thread, so reading an unset slot as "nothing is queued" would silently rotate the
     * uuid of a message the engine is still working on - the orphan the comment in
     * {@code MessageParser} exists to prevent - and nothing would report it. A missing install is
     * therefore named here rather than answered.
     */
fun translationQueue(): TranslationQueue = PortAccessors.translationQueue(this.translationQueue)

    // -- pair 11's four handles --------------------------------------------------------------------
    //
    // All four fail loudly rather than no-op, the ruling round 155 wrote down for pair 8b's
    // `ViewPorts`: a silent fallback would make a missing install look like ordinary behaviour (a
    // position that never updates, a call screen that never opens, a theme that never applies, a
    // component that stays enabled) and would quietly change which code the JVM tests exercise.
    // `liveLocationHook()` is also the one of the four reached from another class
    // (`MessageParser.processLiveLocationUpdate`), which is why it is public.

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
fun liveLocationHook(): LiveLocationHook = PortAccessors.liveLocationHook(this.liveLocationHook)

    /**
     * Public because {@code JingleRtpConnection} answers a call from the system notification and has
     * no other way to reach the call screen; the other three pair-11 handles are asked for here only.
     *
     * @throws IllegalStateException when nothing was installed - a build fault, not a runtime state
     */
fun rtpSessionPort(): RtpSessionPort = PortAccessors.rtpSessionPort(this.rtpSessionPort)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun themePort(): ThemePort = PortAccessors.themePort(this.themePort)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun profilePictureActivityPort(): ProfilePictureActivityPort = PortAccessors.profilePictureActivityPort(this.profilePictureActivityPort)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun decisionScreenPort(): MemorizingTrustManager.DecisionScreenPort = PortAccessors.decisionScreenPort(this.decisionScreenPort)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun tulkkiPorts(): TulkkiPorts = PortAccessors.tulkkiPorts(this.tulkkiPorts)

    // -- pair 4's accessors ------------------------------------------------------------------------
    //
    // Every one of them fails loudly while its slot is empty, for the reason round 155 wrote down
    // for pair 8b's `ViewPorts`: a silent fallback would make a build that forgot the install look
    // like ordinary behaviour. They are package-private where only this package asks (the callers in
    // `eu.siacs.conversations.*` reach them through the service they already hold), and public where
    // another module does.

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    fun compatibility(): CompatibilityPort = PortAccessors.compatibility(this.compatibility)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
fun phoneHelper(): PhoneHelperPort = PortAccessors.phoneHelper(this.phoneHelper)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun wakeLock(): WakeLockPort = PortAccessors.wakeLock(this.wakeLockPort)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun transcoderStrategies(): TranscoderStrategiesPort = PortAccessors.transcoderStrategies(this.transcoderStrategies)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun systemEvent(): SystemEventPort = PortAccessors.systemEvent(this.systemEvent)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun messageSearch(): MessageSearchPort = PortAccessors.messageSearch(this.messageSearch)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun contactListSync(): ContactListSyncPort = PortAccessors.contactListSync(this.contactListSync)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun attachFile(): AttachFilePort = PortAccessors.attachFile(this.attachFile)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun avatar(): AvatarPort = PortAccessors.avatar(this.avatar)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun notification(): NotificationPort = PortAccessors.notification(this.notification)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
fun channelDiscovery(): ChannelDiscoveryPort = PortAccessors.channelDiscovery(this.channelDiscovery)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun shortcuts(): ShortcutPort = PortAccessors.shortcuts(this.shortcuts)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun unifiedPush(): UnifiedPushPort = PortAccessors.unifiedPush(this.unifiedPush)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
private fun pushManagement(): PushManagementPort = PortAccessors.pushManagement(this.pushManagement)

    /** The file observer is only stored; the factory that builds it is asked for on the way. */
private fun fileObserverPort(): FileObserverPort = PortAccessors.fileObserverPort(this.tulkkiPorts)

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
fun bobTransfer(): BobTransferPort = PortAccessors.bobTransfer(this.tulkkiPorts)

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
fun incomingMessageHook(): IncomingMessageHook = PortAccessors.incomingMessageHook(this.tulkkiPorts)

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
fun callIntegration(): CallIntegrationFactory = PortAccessors.callIntegration(this.tulkkiPorts)

    // ---------------------------------------------------------------------------------------------
    // The class-level statics. They stay on the class (not a top-level object) because `:app` and
    // the island spell them `XmppConnectionService.X`, and `dataStatics()` alone has 161 call sites
    // in 49 files - so the shape is a `companion object` with `@JvmStatic`/`@JvmField`, never a
    // file-level declaration, which would change every one of those spellings.
    // ---------------------------------------------------------------------------------------------
    companion object {

        // Tulkki: the two OS-held action strings keep the pre-rename text on purpose. They are not
        // names the compiler resolves - they are data the operating system stores: an AlarmManager
        // alarm survives a package *replace*, so an alarm armed by the previous build is handed back
        // to the new process carrying the old action. Renaming the constant stops
        // `case ACTION_EXPIRE_MESSAGES:` from matching and the post-connectivity ping comparison
        // from firing, so the expiry the alarm was due to run is skipped. Restored and protected in
        // tools/rename-packages' PROTECTED_STRINGS; pinned by uk.xa0.upgrade.OsHeldNamesTest. See
        // docs/MIGRATION.md "The literal audit" F2/F3. No explicit type is written, because that
        // test reads the literal declaration out of this file's own text.
        private const val ACTION_POST_CONNECTIVITY_CHANGE = "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"
        const val ACTION_EXPIRE_MESSAGES = "eu.siacs.conversations.EXPIRE_MESSAGES"

        private const val SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp"

        private val FILE_OBSERVER_EXECUTOR: Executor = Executors.newSingleThreadExecutor()

        @JvmField
        val FILE_ATTACHMENT_EXECUTOR: Executor = Executors.newSingleThreadExecutor()

        private val VIDEO_COMPRESSION_EXECUTOR: SerialSingleThreadExecutor =
            SerialSingleThreadExecutor("VideoCompression")

        private fun generateFetchKey(account: AccountRef, avatar: Avatar): String =
            ServiceSeams.generateFetchKey(account, avatar)

        @Volatile
        private var tulkkiPortsFactory: TulkkiPorts.Factory? = null

        @Volatile
        private var staticTrustPort: TrustPort? = null

        @Volatile
        private var staticDataStatics: DataStatics? = null

        /**
         * Install the `:data` statics the island reaches. Called once, at process start, by the
         * composition root, beside [installTrustPort].
         */
        @JvmStatic
        fun installDataStatics(statics: DataStatics) {
            staticDataStatics = statics
        }

        // Tulkki: the body moved to `PortGuards`; the slot stays
        // here because chunk C74 owns it, and the guard takes it by value so the single read is the
        // Java's.
        /** The `:data` statics, or a loud failure naming the install point. */
        @JvmStatic
        fun dataStatics(): DataStatics = PortGuards.dataStatics(staticDataStatics)

        /** Called once, at process start, by the composition root. */
        @JvmStatic
        fun installPortsFactory(factory: TulkkiPorts.Factory) {
            tulkkiPortsFactory = factory
        }

        /**
         * The trust port for the callers that have no service to ask: `Config`'s contact-domain
         * check (reached from the model) and `HttpConnectionManager`'s static `okHttpClient`.
         * It is installed at process start by the composition root and re-asserted by `onCreate`.
         */
        @JvmStatic
        fun installTrustPort(port: TrustPort) {
            staticTrustPort = port
        }

        // Tulkki: the body moved to `PortGuards`; the slot stays
        // with chunk C74's fields and travels in by value.
        @JvmStatic
        fun trustPort(): TrustPort = PortGuards.trustPort(staticTrustPort)
    }
}
