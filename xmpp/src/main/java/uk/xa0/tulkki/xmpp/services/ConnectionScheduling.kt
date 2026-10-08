package uk.xa0.tulkki.xmpp.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.OnMessageAcknowledged
import uk.xa0.tulkki.xmpp.OnStatusChanged
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the scheduled work - logout, the post-connectivity-change and wake-up alarms, the idle
 * ping and each account's connection construction - lifted out of `XmppConnectionService`
 *.
 *
 * The Java's own `internalPingExecutor` slot stays where it is; nothing in this chunk schedules on
 * it, so nothing here injects it. What does travel in by hand is what has no public spelling: the
 * **private** `disconnect` (chunk `C41`) as the shared `ConversationLifecycle.AccountDisconnector`
 * the service binds with `this::disconnect`, the two **private** `C76` port accessors
 * `systemEvent()` and `compatibility()`, C13's plain `mScheduledMessages` map, C04's
 * `statusListener` and C25b's `mOnMessageAcknowledgedListener`. C01's private
 * `ACTION_POST_CONNECTIVITY_CHANGE` is passed as the string it is, so the constant stays written
 * once in the file that owns it; `ACTION_PING` and `ACTION_IDLE_PING` are public and read here.
 *
 * The null `AlarmManager` tests, the platform-version branch, the `RuntimeException` swallows and
 * the cast-then-test idiom of `getSystemService(Context.ALARM_SERVICE)` are the Java's, unchanged.
 */
object ConnectionScheduling {

    @JvmStatic
    fun logoutAndSave(
        service: XmppConnectionService,
        stop: Boolean,
        disconnector: ConversationLifecycle.AccountDisconnector,
    ) {
        var activeAccounts = 0
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.isConnectionEnabled()) {
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).writeRoster(account.getRoster())
                activeAccounts++
            }
            if (account.getXmppConnection() != null) {
                Thread { disconnector.disconnect(account, false) }.start()
            }
        }
        if (stop || activeAccounts == 0) {
            Log.d(Config.LOGTAG, "good bye")
            service.stopSelf()
        }
    }

    @JvmStatic
    fun schedulePostConnectivityChange(
        service: XmppConnectionService,
        systemEvent: SystemEventPort,
        compatibility: CompatibilityPort,
        action: String,
    ) {
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        if (alarmManager == null) {
            return
        }
        val triggerAtMillis =
            SystemClock.elapsedRealtime() +
                (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000)
        val intent = Intent(service, systemEvent.receiverClass())
        intent.setAction(action)
        try {
            val pendingIntent =
                PendingIntent.getBroadcast(
                    service,
                    1,
                    intent,
                    if (compatibility.s()) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e)
        }
    }

    @JvmStatic
    fun scheduleWakeUpCall(
        service: XmppConnectionService,
        seconds: Int,
        requestCode: Int,
        systemEvent: SystemEventPort,
    ) {
        scheduleWakeUpCall(
            service,
            (if (seconds < 0) 1 else seconds + 1) * 1000L,
            requestCode,
            systemEvent,
        )
    }

    @JvmStatic
    fun scheduleWakeUpCall(
        service: XmppConnectionService,
        milliSeconds: Long,
        requestCode: Int,
        systemEvent: SystemEventPort,
    ) {
        val timeToWake = SystemClock.elapsedRealtime() + milliSeconds
        val alarmManager = service.getSystemService(AlarmManager::class.java)
        val intent = Intent(service, systemEvent.receiverClass())
        intent.setAction(ServiceActions.ACTION_PING)
        try {
            val pendingIntent =
                PendingIntent.getBroadcast(
                    service,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent)
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e)
        }
    }

    @JvmStatic
    fun scheduleNextIdlePing(
        service: XmppConnectionService,
        scheduledMessages: MutableMap<String, MessageRef>,
        systemEvent: SystemEventPort,
        compatibility: CompatibilityPort,
    ) {
        var timeUntilWake = (Config.IDLE_PING_INTERVAL * 1000).toLong()
        val now = System.currentTimeMillis()
        for (message in scheduledMessages.values) {
            if (message.getTimeSent() <= now) continue // Just in case
            if (message.getTimeSent() - now < timeUntilWake) {
                timeUntilWake = message.getTimeSent() - now
            }
        }
        val timeToWake = SystemClock.elapsedRealtime() + timeUntilWake
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        if (alarmManager == null) {
            Log.d(Config.LOGTAG, "no alarm manager?")
            return
        }
        val intent = Intent(service, systemEvent.receiverClass())
        intent.setAction(ServiceActions.ACTION_IDLE_PING)
        try {
            val pendingIntent =
                PendingIntent.getBroadcast(
                    service,
                    0,
                    intent,
                    if (compatibility.s()) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                timeToWake,
                pendingIntent,
            )
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to schedule alarm for idle ping", e)
        }
    }

    @JvmStatic
    fun createConnection(
        service: XmppConnectionService,
        account: AccountRef,
        statusListener: OnStatusChanged,
        messageAcknowledgedListener: OnMessageAcknowledged,
    ): XmppConnection {
        val connection = XmppConnection(account, service)
        connection.setOnStatusChangedListener(statusListener)
        connection.setOnJinglePacketReceivedListener { accountRef, packet ->
            service.getJingleConnectionManager().deliverPacket(accountRef, packet)
        }
        connection.setOnMessageAcknowledgeListener(messageAcknowledgedListener)
        connection.addOnAdvancedStreamFeaturesAvailableListener(
            service.getMessageArchiveService(),
        )
        connection.addOnAdvancedStreamFeaturesAvailableListener(service.getAvatarService())
        val axolotlService = account.getOmemoSession()
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService)
        }
        return connection
    }
}
