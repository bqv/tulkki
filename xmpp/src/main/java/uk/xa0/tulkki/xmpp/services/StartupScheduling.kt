package uk.xa0.tulkki.xmpp.services

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import uk.xa0.tulkki.xmpp.Config
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/**
 * Tulkki: the post-open scheduling tail of `continueAfterDbInit`, lifted out of
 * `XmppConnectionService`.
 *
 * The chunk is hub-scale - `continueAfterDbInit` reaches about twenty private fields, executors and
 * receivers - so it is taken in slices, and this is the coherent tail: what the service starts once
 * the database is up. The reaches travel in by value. `shuttingDown` is the private volatile flag
 * (C22's teardown sets it), so it arrives as a [BooleanSupplier] and is re-read exactly where the
 * Java re-read it, including inside the `catch`; C13's **private**
 * `manageAccountConnectionStatesInternal` and the two story passes arrive as [Runnable]s the service
 * binds, so nothing is widened and the private bodies stay where their own chunks will take them.
 *
 * The Java's guard order is the Java's: a service torn down while the database was still opening is
 * not scheduled for, a rejection is rethrown unless the flag says the service really is going away
 * (defence in depth - this runs on the main looper, so `onDestroy` cannot interleave with the check
 * above), and the preference listener is registered before the ready flag is set and its callbacks
 * released. `DatabaseReadiness` is chunk C02's; the ready flag and the callbacks are its own members
 * and keep their order.
 */
object StartupScheduling {

    @JvmStatic
    fun startBackgroundTasks(
        service: XmppConnectionService,
        shuttingDown: BooleanSupplier,
        pingExecutor: ScheduledExecutorService,
        storyRetractionExecutor: ScheduledExecutorService,
        storyCacheExecutor: ScheduledExecutorService,
        manageAccountStates: Runnable,
    ) {
        if (shuttingDown.asBoolean) {
            Log.d(
                Config.LOGTAG,
                "database opened after service teardown; not scheduling background tasks",
            )
        } else {
            try {
                pingExecutor.scheduleWithFixedDelay(manageAccountStates, 10, 10, TimeUnit.SECONDS)
                storyRetractionExecutor.scheduleWithFixedDelay(
                    Runnable { service.retractOldStories() },
                    1,
                    60,
                    TimeUnit.MINUTES,
                )
                storyCacheExecutor.scheduleWithFixedDelay(
                    Runnable { service.cleanupStoryCache() },
                    1,
                    1,
                    TimeUnit.HOURS,
                )
            } catch (e: RejectedExecutionException) {
                if (!shuttingDown.asBoolean) {
                    throw e
                }
                Log.d(
                    Config.LOGTAG,
                    "service is shutting down; not scheduling background tasks",
                )
            }
        }

        val sharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(service)
        sharedPreferences.registerOnSharedPreferenceChangeListener { _, key ->
            Log.d(Config.LOGTAG, "preference '$key' has changed")
            if (XmppConnectionService.DataStatics.KEEP_FOREGROUND_SERVICE == key) {
                service.toggleForegroundService()
            }
        }

        DatabaseReadiness.markReady()
        DatabaseReadiness.notifyReadyCallbacks()
    }
}
