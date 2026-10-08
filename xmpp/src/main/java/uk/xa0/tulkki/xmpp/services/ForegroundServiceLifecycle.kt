package uk.xa0.tulkki.xmpp.services

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import uk.xa0.tulkki.xmpp.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.concurrent.atomic.AtomicReference

/**
 * Tulkki: the foreground service and the ongoing call, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk's three fields stay on the service, because other chunks read them: `ongoingCall` is
 * written by C22's `Teardown.setOngoingCall`, the transcode flag by C05's `ServiceSeams`, and
 * `mOutgoingLiveSessions` (C08) is the live-location set. They therefore arrive by value, as do
 * C18's `mForceDuringOnCreate`, C72's dialler flag and C70's `mNotificationService` — the last
 * **nullable**, so the one bare-field dereference keeps the Java's NPE rather than a
 * `checkNotNullParameter` at the seam — while `compatibility()` (C76, package-private) is resolved
 * Java-side and handed in as its port. C24's private `logoutAndSave` arrives as the `Runnable` bound
 * to the `false` its one call site passes, so `onTaskRemoved` can keep its `super` call in Java.
 *
 * The Java's order is kept: the force flag, the transcode flag, the live-location emptiness, the
 * ongoing call and the keep-foreground preference decide the branch; the two other notification ids
 * are cancelled after the switch; and `startForegroundOrCatch` catches the platform's refusal
 * (`IllegalStateException`/`SecurityException`) rather than crashing, with the two-class catch split
 * into two clauses because Kotlin has no multi-catch.
 */
object ForegroundServiceLifecycle {

    /**
     * Tulkki: the null-guarded entry point the UI calls, moved here from the service's own
     * `public static void toggleForegroundService(XmppConnectionService)` (chunk `C60`). `:ui`'s
     * three call sites hold a service that may be null, and the Java answered by doing nothing;
     * that is the whole of the behaviour, and the instance method it forwards to is unchanged.
     */
    @JvmStatic
    fun toggleForegroundService(service: XmppConnectionService?) {
        if (service == null) {
            return
        }
        service.toggleForegroundService()
    }

    @JvmStatic
    fun removeOngoingCall(
        ongoingCall: AtomicReference<OngoingCall>,
        toggleForegroundService: Runnable,
    ) {
        ongoingCall.set(null)
        toggleForegroundService.run()
    }

    @JvmStatic
    fun toggleForegroundService(
        service: XmppConnectionService,
        force: Boolean,
        needMic: Boolean,
        ongoingCall: AtomicReference<OngoingCall>,
        ongoingVideoTranscoding: AtomicBoolean,
        outgoingLiveSessions: Map<String, *>,
        forceDuringOnCreate: AtomicBoolean,
        compatibility: CompatibilityPort,
        notificationService: NotificationPort?,
        diallerIntegrationActive: AtomicBoolean,
        hasEnabledAccounts: BooleanSupplier,
    ) {
        val status: Boolean
        val ongoing = ongoingCall.get()
        val ongoingVideoTranscodingNow = ongoingVideoTranscoding.get()
        val ongoingLiveLocation = outgoingLiveSessions.isNotEmpty()
        val id: Int
        if (force ||
            forceDuringOnCreate.get() ||
            ongoingVideoTranscodingNow ||
            ongoingLiveLocation ||
            ongoing != null ||
            (compatibility.keepForegroundService(service) && hasEnabledAccounts.asBoolean)
        ) {
            if (compatibility.runsTwentySix()) {
                notificationService!!.initializeChannels()
            }
            val notification: Notification
            if (ongoing != null && !diallerIntegrationActive.get()) {
                notification = notificationService!!.getOngoingCallNotification(ongoing)
                id = notificationService!!.ongoingCallNotificationId()
                startForegroundOrCatch(service, id, notification, true, outgoingLiveSessions)
            } else if (ongoingVideoTranscodingNow) {
                notification = notificationService!!.getIndeterminateVideoTranscoding()
                id = notificationService!!.ongoingVideoTranscodingNotificationId()
                startForegroundOrCatch(service, id, notification, false, outgoingLiveSessions)
            } else {
                notification = notificationService!!.createForegroundNotification()
                id = notificationService!!.foregroundNotificationId()
                startForegroundOrCatch(
                    service,
                    id,
                    notification,
                    needMic || ongoing != null || diallerIntegrationActive.get(),
                    outgoingLiveSessions,
                )
            }
            notificationService!!.notify(id, notification)
            status = true
        } else {
            id = 0
            service.stopForeground(true)
            status = false
        }

        for (toBeRemoved in
            intArrayOf(
                notificationService!!.foregroundNotificationId(),
                notificationService!!.ongoingCallNotificationId(),
                notificationService!!.ongoingVideoTranscodingNotificationId(),
            )
        ) {
            if (toBeRemoved != id) {
                notificationService!!.cancel(toBeRemoved)
            }
        }
        Log.d(
            Config.LOGTAG,
            "ForegroundService: " + (if (status) "on" else "off") + ", notification: " + id,
        )
    }

    private fun startForegroundOrCatch(
        service: XmppConnectionService,
        id: Int,
        notification: Notification,
        requireMicrophone: Boolean,
        outgoingLiveSessions: Map<String, *>,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasLiveLocation =
                    outgoingLiveSessions.isNotEmpty() &&
                        ContextCompat.checkSelfPermission(
                            service,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                var foregroundServiceType: Int
                if (requireMicrophone &&
                    ContextCompat.checkSelfPermission(
                        service,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Active call: claim MICROPHONE (and LOCATION if live location is also running)
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    if (hasLiveLocation) {
                        foregroundServiceType =
                            foregroundServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    Log.d(Config.LOGTAG, "foreground service type: microphone")
                } else if (hasLiveLocation) {
                    // Live location only, no active call - use LOCATION alone.
                    // Do NOT combine with MICROPHONE here: MICROPHONE requires an active recording
                    // session in API 34+, and claiming it without one can invalidate the entire
                    // service type, revoking GPS access.
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    Log.d(Config.LOGTAG, "foreground service type: location")
                } else if (service.getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(service.packageName)
                ) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                } else if (ContextCompat.checkSelfPermission(
                        service,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else if (ContextCompat.checkSelfPermission(
                        service,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    Log.w(Config.LOGTAG, "falling back to special use foreground service type")
                }
                service.startForeground(id, notification, foregroundServiceType)
            } else {
                service.startForeground(id, notification)
            }
        } catch (e: IllegalStateException) {
            Log.e(Config.LOGTAG, "Could not start foreground service", e)
        } catch (e: SecurityException) {
            Log.e(Config.LOGTAG, "Could not start foreground service", e)
        }
    }

    @JvmStatic
    fun foregroundNotificationNeedsUpdatingWhenErrorStateChanges(
        service: XmppConnectionService,
        ongoingVideoTranscoding: AtomicBoolean,
        ongoingCall: AtomicReference<OngoingCall>,
        compatibility: CompatibilityPort,
        hasEnabledAccounts: BooleanSupplier,
    ): Boolean =
        !ongoingVideoTranscoding.get() &&
            ongoingCall.get() == null &&
            compatibility.keepForegroundService(service) &&
            hasEnabledAccounts.asBoolean

    @JvmStatic
    fun onTaskRemoved(
        service: XmppConnectionService,
        ongoingVideoTranscoding: AtomicBoolean,
        ongoingCall: AtomicReference<OngoingCall>,
        compatibility: CompatibilityPort,
        logoutAndSave: Runnable,
        hasEnabledAccounts: BooleanSupplier,
    ) {
        if ((compatibility.keepForegroundService(service) && hasEnabledAccounts.asBoolean) ||
            ongoingVideoTranscoding.get() ||
            ongoingCall.get() != null
        ) {
            Log.d(Config.LOGTAG, "ignoring onTaskRemoved because foreground service is activated")
        } else {
            logoutAndSave.run()
        }
    }
}
