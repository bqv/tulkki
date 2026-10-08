package uk.xa0.tulkki.xmpp.services

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import androidx.core.app.RemoteInput
import com.google.common.base.Optional
import com.google.common.base.Strings
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.xmpp.utils.TorServiceUtils
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * Tulkki: `onStartCommand`, the intent switchboard, lifted out of `XmppConnectionService`
 *.
 *
 * Nothing moved but the body. `fileBackend`, the jingle manager, `internalPingExecutor` and the
 * notification executor are `final` fields and travel by value; `systemEvent()`,
 * `contactListSync()`, `unifiedPushBroker` and the two `contactListSyncService` reads travel as
 * `Supplier`s bound Java-side (the bare-field one to `() -> contactListSyncService`, so its NPE point
 * is unchanged, the accessor one to `this::contactListSync`, so its `require` is unchanged); C70's
 * nullable `mNotificationService` arrives nullable and is dereferenced with `!!` exactly where the
 * Java dereferenced the bare field. Every private method the switch calls — C24's
 * `logoutAndSave`/`schedulePostConnectivityChange`/`scheduleNextIdlePing`, C15's
 * `dndOnSilentMode`/`awayWhenScreenLocked`/`resetAllAttemptCounts`/`dismissErrorNotifications`, C14's
 * `directReply`/`toggleSoftDisabled`, C13's `handleOrbotStartedEvent`/`quickLog`/
 * `sendScheduledMessages`/`manageAccountConnectionStates`, C16's no-arg `expireOldMessages`, C33's
 * `renewUnifiedPushEndpoints`/`provisionAccount` and the two-argument
 * `toggleForegroundService` — arrives as the bound `Runnable`/`Consumer`/`BiConsumer`/`Function`/
 * `BooleanSupplier`/`DirectReplier` the Java call site used. The public reaches
 * (`getBooleanPreference`, `hasInternetConnection`, `findConversationByUuid`, `updateConversation`,
 * `sendReadMarker`, `refreshAllPresences`, `stopLiveLocationSharing`, `expireOldMessages(boolean)`,
 * `checkListeners`, `stopSelf`, `rtpSessionPort()`, the public `restoredFromDatabaseLatch`) are called
 * on the service.
 *
 * The Java switch's shape is kept, including its two real pieces of fallthrough: `ACTION_SNOOZE`
 * falls into the ringer/interruption-filter arm (`if (dndOnSilentMode()) refreshAllPresences()`), and
 * `ACTION_SCREEN_ON` falls into `ACTION_USER_PRESENT`/`ACTION_SCREEN_OFF`. Each `break` becomes a
 * branch end; the `return START_*` arms return from the function.
 *
 * Master's `shuttingDown` guard (`03165687d6`, merged as `7b5c661cfc`) travels in with the tail
 * `internalPingExecutor.execute` catch: the service's private `volatile` flag arrives as the
 * `BooleanSupplier` the Java call site binds, so the catch ignores only a rejection that is the
 * teardown and rethrows one that happens while the service is alive.
 */
object StartCommand {

    fun interface DirectReplier {
        fun reply(
            conversation: ConversationRef,
            body: String,
            lastMessageUuid: String?,
            dismissAfterReply: Boolean,
        )
    }

    @JvmStatic
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun onStartCommand(
        service: XmppConnectionService,
        intent: Intent?,
        fileBackend: FileBackendRef,
        systemEvent: Supplier<SystemEventPort>,
        toggleForegroundService: BiConsumer<Boolean, Boolean>,
        contactListSyncService: Supplier<ContactListSyncPort>,
        contactListSync: Supplier<ContactListSyncPort>,
        schedulePostConnectivityChange: Runnable,
        resetAllAttemptCounts: BiConsumer<Boolean, Boolean>,
        logoutAndSave: Consumer<Boolean>,
        notificationExecutor: Executor,
        notificationService: NotificationPort?,
        jingleConnectionManager: JingleConnectionManager,
        handleOrbotStartedEvent: Runnable,
        provisionAccount: BiConsumer<String, String>,
        dismissErrorNotifications: Runnable,
        directReply: DirectReplier,
        dndOnSilentMode: BooleanSupplier,
        deactivateGracePeriod: Runnable,
        awayWhenScreenLocked: BooleanSupplier,
        refreshAllFcmTokens: Runnable,
        unifiedPushBroker: Supplier<UnifiedPushPort>,
        renewUnifiedPushEndpoints:
            Function<
                UnifiedPushPort.PushTarget?,
                Optional<UnifiedPushPort.Transport>,
            >,
        scheduleNextIdlePing: Runnable,
        expireOldMessages: Runnable,
        quickLog: Consumer<String>,
        toggleSoftDisabled: Runnable,
        sendScheduledMessages: Runnable,
        internalPingExecutor: ScheduledExecutorService,
        manageAccountConnectionStates: BiConsumer<String, Bundle?>,
        shuttingDown: BooleanSupplier,
    ): Int {
        val nomedia = service.getBooleanPreference("nomedia", R.bool.default_nomedia)
        fileBackend.setupNomedia(nomedia)
        val action = Strings.nullToEmpty(if (intent == null) null else intent.getAction())
        val needsForegroundService =
            intent != null &&
                intent.getBooleanExtra(systemEvent.get().extraNeedsForegroundService(), false)
        if (needsForegroundService) {
            Log.d(
                Config.LOGTAG,
                "toggle forced foreground service after receiving event (action=" + action + ")",
            )
            toggleForegroundService.accept(
                true,
                action == ServiceActions.ACTION_STARTING_CALL,
            )
        }
        val uuid = if (intent == null) null else intent.getStringExtra("uuid")
        when (action) {
            ServiceActions.SMS_RETRIEVED_ACTION ->
                contactListSyncService.get().handleSmsReceived(intent)
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                if (service.hasInternetConnection()) {
                    if (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0) {
                        schedulePostConnectivityChange.run()
                    }
                    if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                        resetAllAttemptCounts.accept(true, false)
                    }
                    Resolver.clearCache()
                }
            }
            Intent.ACTION_SHUTDOWN -> {
                logoutAndSave.accept(true)
                return Service.START_NOT_STICKY
            }
            ServiceActions.ACTION_CLEAR_MESSAGE_NOTIFICATION ->
                notificationExecutor.execute {
                    try {
                        val c = service.findConversationByUuid(uuid)
                        if (c != null) {
                            notificationService!!.clearMessages(c)
                        } else {
                            notificationService!!.clearMessages()
                        }
                        service.restoredFromDatabaseLatch.await()
                    } catch (e: InterruptedException) {
                        Log.d(Config.LOGTAG, "unable to process clear message notification")
                    }
                }
            ServiceActions.ACTION_CLEAR_MISSED_CALL_NOTIFICATION ->
                notificationExecutor.execute {
                    try {
                        val c = service.findConversationByUuid(uuid)
                        if (c != null) {
                            notificationService!!.clearMissedCalls(c)
                        } else {
                            notificationService!!.clearMissedCalls()
                        }
                        service.restoredFromDatabaseLatch.await()
                    } catch (e: InterruptedException) {
                        Log.d(
                            Config.LOGTAG,
                            "unable to process clear missed call notification",
                        )
                    }
                }
            ServiceActions.ACTION_DISMISS_CALL -> {
                if (intent != null) {
                    val sessionId = intent.getStringExtra(service.rtpSessionPort().sessionIdExtra())
                    Log.d(
                        Config.LOGTAG,
                        "received intent to dismiss call with session id " + sessionId,
                    )
                    jingleConnectionManager.rejectRtpSession(sessionId)
                }
            }
            TorServiceUtils.ACTION_STATUS -> {
                val status =
                    if (intent == null) null else intent.getStringExtra(TorServiceUtils.EXTRA_STATUS)
                // TODO port and host are in 'extras' - but this may not be a reliable source?
                if ("ON" == status) {
                    handleOrbotStartedEvent.run()
                    return Service.START_STICKY
                }
            }
            ServiceActions.ACTION_END_CALL -> {
                if (intent != null) {
                    val sessionId = intent.getStringExtra(service.rtpSessionPort().sessionIdExtra())
                    Log.d(
                        Config.LOGTAG,
                        "received intent to end call with session id " + sessionId,
                    )
                    jingleConnectionManager.endRtpSession(sessionId)
                }
            }
            ServiceActions.ACTION_STOP_LIVE_LOCATION -> {
                if (uuid != null) {
                    service.stopLiveLocationSharing(uuid)
                }
            }
            ServiceActions.ACTION_PROVISION_ACCOUNT -> {
                if (intent != null) {
                    val address = intent.getStringExtra("address")
                    val password = intent.getStringExtra("password")
                    if (!(contactListSync.get().quicksy() ||
                            Strings.isNullOrEmpty(address) ||
                            Strings.isNullOrEmpty(password))
                    ) {
                        provisionAccount.accept(address!!, password!!)
                    }
                }
            }
            ServiceActions.ACTION_DISMISS_ERROR_NOTIFICATIONS ->
                dismissErrorNotifications.run()
            ServiceActions.ACTION_TRY_AGAIN -> resetAllAttemptCounts.accept(false, true)
            ServiceActions.ACTION_REPLY_TO_CONVERSATION -> {
                if (intent != null) {
                    val remoteInput = RemoteInput.getResultsFromIntent(intent)
                    if (remoteInput != null) {
                        val body = remoteInput.getCharSequence("text_reply")
                        val dismissNotification =
                            intent.getBooleanExtra("dismiss_notification", false)
                        val lastMessageUuid = intent.getStringExtra("last_message_uuid")
                        if (!(body == null || body.length <= 0)) {
                            notificationExecutor.execute {
                                try {
                                    service.restoredFromDatabaseLatch.await()
                                    val c = service.findConversationByUuid(uuid)
                                    if (c != null) {
                                        directReply.reply(
                                            c,
                                            body.toString(),
                                            lastMessageUuid,
                                            dismissNotification,
                                        )
                                    }
                                } catch (e: InterruptedException) {
                                    Log.d(Config.LOGTAG, "unable to process direct reply")
                                }
                            }
                        }
                    }
                }
            }
            ServiceActions.ACTION_MARK_AS_READ ->
                notificationExecutor.execute {
                    val c = service.findConversationByUuid(uuid)
                    if (c == null) {
                        Log.d(
                            Config.LOGTAG,
                            "received mark read intent for unknown conversation (" + uuid + ")",
                        )
                        return@execute
                    }
                    try {
                        service.restoredFromDatabaseLatch.await()
                        service.sendReadMarker(c, null)
                    } catch (e: InterruptedException) {
                        Log.d(
                            Config.LOGTAG,
                            "unable to process notification read marker for conversation " +
                                c.getName(),
                        )
                    }
                }
            // The Java's ACTION_SNOOZE has no `break` and falls into the ringer arm below.
            ServiceActions.ACTION_SNOOZE -> {
                notificationExecutor.execute {
                    val c = service.findConversationByUuid(uuid)
                    if (c == null) {
                        Log.d(
                            Config.LOGTAG,
                            "received snooze intent for unknown conversation (" + uuid + ")",
                        )
                        return@execute
                    }
                    c.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000)
                    notificationService!!.clearMessages(c)
                    service.updateConversation(c)
                }
                if (dndOnSilentMode.asBoolean) {
                    service.refreshAllPresences()
                }
            }
            AudioManager.RINGER_MODE_CHANGED_ACTION,
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                if (dndOnSilentMode.asBoolean) {
                    service.refreshAllPresences()
                }
            }
            Intent.ACTION_SCREEN_ON -> {
                deactivateGracePeriod.run()
                if (awayWhenScreenLocked.asBoolean) {
                    service.refreshAllPresences()
                }
            }
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_OFF -> {
                if (awayWhenScreenLocked.asBoolean) {
                    service.refreshAllPresences()
                }
            }
            ServiceActions.ACTION_FCM_TOKEN_REFRESH -> refreshAllFcmTokens.run()
            ServiceActions.ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS -> {
                if (intent != null) {
                    val instance = intent.getStringExtra("instance")
                    val application = intent.getStringExtra("application")
                    val messenger: Messenger? = intent.getParcelableExtra<Messenger>("messenger")
                    val pushTargetMessenger: UnifiedPushPort.PushTarget?
                    if (messenger != null && application != null && instance != null) {
                        pushTargetMessenger =
                            unifiedPushBroker.get().pushTarget(application, instance, messenger)
                        Log.d(Config.LOGTAG, "found push target messenger")
                    } else {
                        pushTargetMessenger = null
                    }
                    val transport = renewUnifiedPushEndpoints.apply(pushTargetMessenger)
                    if (instance != null && transport.isPresent) {
                        unifiedPushBroker
                            .get()
                            .rebroadcastEndpoint(messenger, instance, transport.get())
                    }
                }
            }
            ServiceActions.ACTION_IDLE_PING -> scheduleNextIdlePing.run()
            ServiceActions.ACTION_FCM_MESSAGE_RECEIVED ->
                Log.d(Config.LOGTAG, "push message arrived in service. account")
            XmppConnectionService.ACTION_EXPIRE_MESSAGES -> expireOldMessages.run()
            ServiceActions.ACTION_QUICK_LOG -> {
                val message = if (intent == null) null else intent.getStringExtra("message")
                if (message != null && Config.QUICK_LOG) {
                    quickLog.accept(message)
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (intent == null) null else intent.getData()
                if (uri != null) {
                    Log.d(Config.LOGTAG, "received uri permission for " + uri)
                }
                return Service.START_STICKY
            }
            ServiceActions.ACTION_TEMPORARILY_DISABLE -> {
                toggleSoftDisabled.run()
                if (service.checkListeners()) {
                    service.stopSelf()
                }
                return Service.START_NOT_STICKY
            }
        }
        sendScheduledMessages.run()
        val extras = if (intent == null) null else intent.getExtras()
        try {
            internalPingExecutor.execute { manageAccountConnectionStates.accept(action, extras) }
        } catch (e: RejectedExecutionException) {
            // Master's `shuttingDown` guard (`03165687d6`): a rejection here is the service going
            // away, and only then is it ignored. This path runs on the main thread, so `onDestroy`
            // cannot race it, but the flag is the one condition that makes the rejection benign; a
            // rejection while the service is alive stays visible instead of being swallowed as the
            // base code did (it logged `can not schedule connection states manager` unconditionally).
            // The flag is the service's own private `volatile` field, read through the
            // `BooleanSupplier` the Java call site binds, so no visibility is widened.
            if (!shuttingDown.asBoolean) {
                throw e
            }
            Log.d(
                Config.LOGTAG,
                "service is shutting down; not scheduling connection states manager",
            )
        }
        if (SystemClock.elapsedRealtime() - MessageExpiry.lastExpiryRun() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages.run()
        }
        return Service.START_STICKY
    }
}
