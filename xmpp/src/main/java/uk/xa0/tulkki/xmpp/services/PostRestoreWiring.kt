package uk.xa0.tulkki.xmpp.services

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.core.content.ContextCompat
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.util.OpenPgpServiceConnection
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.TorServiceUtils
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Tulkki: the post-restore wiring of `continueAfterDbInit`, lifted out of `XmppConnectionService`
 *.
 *
 * Slice 1 took the post-open scheduling tail; this is what the service wires once
 * `restoreFromDatabase` has returned: the contact observer, the file observer, OpenPGP's `OnBound`,
 * the power manager's wake lock, the foreground/badge/screen handling, both internal receivers with
 * their filters, and the cache housekeeping. The head - install, account colours, the editor, the
 * push distributor, call integration, and `restoreFromDatabase` with its wrong-key catch - is
 * `PostOpenStartup` (slice 3), which runs before this and answers whether this runs at all.
 *
 * The reaches travel in by value, as slice 1's did. The two ports (`contactListSync`, `compatibility`)
 * are the service's own package-private accessors resolved at the call site; the file-observer
 * executor, the watcher and the two receivers are private state of other groups; the four private or
 * protected method bodies (`checkForDeletedFiles`, `scheduleNextIdlePing`, `migrateCacheToInternalStorage`,
 * `cleanupTemporaryStorage`) arrive as [Runnable]s. **`cleanupTemporaryStorage` has to arrive this
 * way**: it is a Java `protected` member, and Kotlin cannot call one from a non-subclass - the same
 * rule that reddened `JingleConnectionManager` - so the service binds it rather than widening it. The
 * two private fields this slice writes (`pgpServiceConnection`, `wakeLock`) arrive as setters for the
 * same reason, and nothing is widened. `mForceDuringOnCreate` is the service's own `AtomicBoolean`,
 * handed over whole.
 *
 * The Java's guard order is the Java's, and it is kept: the contact observer only when contact-list
 * integration is on and `READ_CONTACTS` is granted, the file observer only behind the storage
 * permission, OpenPGP only when configured, the wake lock only when a `PowerManager` exists; then
 * the foreground toggle, the badge, the screen receiver, the unexported system-filter receiver, the
 * exported Tor-status receiver, the force-during-on-create reset, a second foreground toggle, and
 * the two cache sweeps. `scheduleNextIdlePing` keeps its place between the filter's construction and
 * its first action.
 *
 * The paragraph the Java carried at this point, verbatim:
 *
 * Tulkki's pass over what arrived while the app was away is deliberately NOT started here. At
 * this point no account has connected, so the archive catch-up that carries this session's
 * messages has not run yet, and a pass here would only ever see the previous session's
 * leftovers. It is triggered from MessageArchiveService once the account has no catch-up query
 * left, which is the first moment the rows it exists for are in the database: see
 * StartupBacklog.run. Not being tied to database init is also what lets a resume - a reconnect
 * with the process still alive - translate the gap it brings.
 */
object PostRestoreWiring {

    @JvmStatic
    fun start(
        service: XmppConnectionService,
        contactListSync: ContactListSyncPort,
        compatibility: CompatibilityPort,
        fileObserverExecutor: Executor,
        fileWatcher: FileObserverPort.Watcher,
        checkForDeletedFiles: Runnable,
        setPgpServiceConnection: Consumer<OpenPgpServiceConnection>,
        setWakeLock: Consumer<WakeLock>,
        forceDuringOnCreate: AtomicBoolean,
        internalEventReceiver: BroadcastReceiver,
        internalRestrictedEventReceiver: BroadcastReceiver,
        scheduleNextIdlePing: Runnable,
        migrateCacheToInternalStorage: Runnable,
        cleanupTemporaryStorage: Runnable,
    ) {
        if (contactListSync.contactListIntegration(service) &&
            ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            service.startContactObserver()
        }
        if (compatibility.hasStoragePermission(service)) {
            Log.d(Config.LOGTAG, "starting file observer")
            fileObserverExecutor.execute { fileWatcher.startWatching() }
            fileObserverExecutor.execute(checkForDeletedFiles)
        }
        if (Config.supportOpenPgp()) {
            val connection =
                OpenPgpServiceConnection(
                    service,
                    "org.sufficientlysecure.keychain",
                    object : OpenPgpServiceConnection.OnBound {
                        override fun onBound(pgpService: IOpenPgpService2) {
                            for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                                val pgp = account.getPgpDecryption()
                                if (pgp != null) {
                                    pgp.continueDecryption(true)
                                }
                            }
                        }

                        override fun onError(exception: Exception) {
                            Log.e(Config.LOGTAG, "could not bind to OpenKeyChain", exception)
                        }
                    },
                )
            setPgpServiceConnection.accept(connection)
            connection.bindToService()
        }

        val powerManager = service.getSystemService(PowerManager::class.java)
        if (powerManager != null) {
            setWakeLock.accept(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tulkki:Service"),
            )
        }

        service.toggleForegroundService()
        service.updateUnreadCountBadge()
        service.toggleScreenEventReceiver()
        val systemBroadcastFilter = IntentFilter()
        scheduleNextIdlePing.run()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemBroadcastFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        systemBroadcastFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        ContextCompat.registerReceiver(
            service,
            internalEventReceiver,
            systemBroadcastFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val exportedBroadcastFilter = IntentFilter()
        exportedBroadcastFilter.addAction(TorServiceUtils.ACTION_STATUS)
        ContextCompat.registerReceiver(
            service,
            internalRestrictedEventReceiver,
            exportedBroadcastFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        forceDuringOnCreate.set(false)
        service.toggleForegroundService()
        migrateCacheToInternalStorage.run()
        cleanupTemporaryStorage.run()
    }
}
