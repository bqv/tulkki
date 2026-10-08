package uk.xa0.tulkki.xmpp.services

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * Tulkki: the contact observer, teardown and the event receivers' switches, lifted out of
 * `XmppConnectionService`.
 *
 * The non-volatile `destroyed` flag travels with the chunk (the race is upstream's and is kept); it
 * is read through the service's old lambda as a fresh read every time. The receivers, the
 * notification port, the file watcher, the three executors and the ongoing-call reference are
 * private state of other groups and arrive by hand. The two presence preferences reuse
 * `PresencePreferences` rather than the service's private one-line delegations, and the two
 * foreground-service toggles travel in as the actions the Java called.
 */
object Teardown {

    private var destroyed = false

    @JvmStatic
    fun isDestroyed(): Boolean = destroyed

    @JvmStatic
    fun setDestroyed(value: Boolean) {
        destroyed = value
    }

    @JvmStatic
    fun startContactObserver(service: XmppConnectionService, restoredFromDatabaseLatch: CountDownLatch) {
        service.getContentResolver()
            .registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        if (restoredFromDatabaseLatch.getCount() == 0L) {
                            service.loadPhoneContacts()
                        }
                    }
                },
            )
    }

    @JvmStatic
    fun onTrimMemory(drawableCache: LruCache<String, Drawable>, level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            Log.d(Config.LOGTAG, "clear cache due to low memory")
            drawableCache.evictAll()
        }
    }

    @JvmStatic
    fun onDestroy(
        service: XmppConnectionService,
        internalEventReceiver: BroadcastReceiver,
        internalRestrictedEventReceiver: BroadcastReceiver,
        internalScreenEventReceiver: BroadcastReceiver,
        notificationService: NotificationPort,
        fileWatcher: FileObserverPort.Watcher,
        internalPingExecutor: ExecutorService,
        storyRetractionExecutor: ExecutorService,
        storyCacheExecutor: ExecutorService,
    ) {
        try {
            service.unregisterReceiver(internalEventReceiver)
            service.unregisterReceiver(internalRestrictedEventReceiver)
            service.unregisterReceiver(internalScreenEventReceiver)
        } catch (e: IllegalArgumentException) {
            // ignored
        }
        // Tulkki: drop any notification this service is holding for a translation; it must not post
        // against a service that is going away.
        notificationService.stop()
        destroyed = false
        fileWatcher.stopWatching()
        // Contract with `XmppConnectionService.onDestroy`: it sets its `shuttingDown` flag before
        // calling here, and `continueAfterDbInit` and the status-listener ping read that flag before
        // scheduling on these pools. Keep the flag-set ahead of these three shutdowns, or the
        // `RejectedExecutionException` race the flag exists to make benign comes back.
        internalPingExecutor.shutdown()
        storyRetractionExecutor.shutdown()
        storyCacheExecutor.shutdown()
    }

    @JvmStatic
    fun restartFileObserver(
        fileObserverExecutor: Executor,
        fileWatcher: FileObserverPort.Watcher,
        checkForDeletedFiles: Runnable,
    ) {
        Log.d(Config.LOGTAG, "restarting file observer")
        fileObserverExecutor.execute { fileWatcher.restartWatching() }
        fileObserverExecutor.execute(checkForDeletedFiles)
    }

    @JvmStatic
    fun toggleScreenEventReceiver(
        service: XmppConnectionService,
        screenEventReceiver: BroadcastReceiver,
    ) {
        if (PresencePreferences.awayWhenScreenLocked(service) &&
            !PresencePreferences.manuallyChangePresence(service)
        ) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            service.registerReceiver(screenEventReceiver, filter)
        } else {
            try {
                service.unregisterReceiver(screenEventReceiver)
            } catch (e: IllegalArgumentException) {
                // ignored
            }
        }
    }

    @JvmStatic
    fun setOngoingCall(
        ongoingCall: AtomicReference<OngoingCall>,
        id: AbstractJingleConnection.Id,
        media: Set<Media>,
        reconnecting: Boolean,
        toggleForegroundService: Runnable,
    ) {
        ongoingCall.set(OngoingCall(id, media, reconnecting))
        toggleForegroundService.run()
    }
}
