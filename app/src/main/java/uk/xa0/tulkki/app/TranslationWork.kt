package uk.xa0.tulkki.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * When the queue runs.
 *
 * <p>There is no polling loop anywhere in Tulkki: every run is either the direct consequence of an
 * event (a message arrived, the app started) or a single scheduled wake-up for the moment the queue
 * says it is worth trying again - a retry's backoff, or the next calendar day after the cap stopped
 * the day's spending.
 *
 * <p>Two unique one-shot names, on purpose:
 *
 * <ul>
 *   <li>{@code -now} is the chain of runs caused by arriving messages. It is appended to, so a burst
 *       of messages does not cancel a run that is already talking to the API, and each run drains
 *       everything that is due.
 *   <li>{@code -later} is the single scheduled wake-up. It is replaced whenever the due time is
 *       recomputed, so a long cap wait never traps a newly arrived message behind it.
 * </ul>
 *
 * <p>All of it requires a network, so an offline phone does not burn a message's retries while it
 * has nothing to talk to.
 *
 * <p><strong>The three unique names are device data, not ours to tidy.</strong> WorkManager stores
 * each one in its own database beside the worker's class name, so a unique name that changes leaves
 * the old row behind: still scheduled, under the old name, and no longer reachable by
 * {@code cancelUniqueWork} or corrected by re-enqueueing. They keep the spelling they were
 * persisted under.
 *
 * <p>Every entry point is a companion `@JvmStatic` function, because the callers are still Java on
 * both sides of this port: `TulkkiApplication`, `UiAppHost`, `XmppTulkkiHost` and `TranslationHooks`
 * call `TranslationWork.ensureScheduled/kick/wakeAt/cancel`, and the Java
 * `TranslationWorkTest` calls `TranslationWork.cancellableNames()` and `cancelEach()`. A companion
 * member with `internal` visibility would mangle that name, so the five functions stay `public`.
 */
class TranslationWork private constructor() {

    companion object {

        const val UNIQUE_NOW = "tulkki-translation-now"
        const val UNIQUE_LATER = "tulkki-translation-later"
        const val UNIQUE_DAILY = "tulkki-translation-daily"

        /** The periodic run is a safety net, not the mechanism: it catches what no event woke up. */
        private const val DAILY_PERIOD_HOURS = 24L

        /** Called when the process starts: nothing pending can be forgotten. */
        @JvmStatic
        fun ensureScheduled(context: Context) {
            try {
                val workManager = WorkManager.getInstance(context)
                // UPDATE, not KEEP, and the difference is the whole point (docs/MIGRATION.md "The
                // upgrade audit" S1.2). KEEP is a no-op whenever *any* spec with this unique name
                // exists, and WorkManager does not compare class names: a row left by a build whose
                // worker class has since been renamed fails to instantiate on every period, is reset
                // to ENQUEUED by that failure, and would be protected by a KEEP forever. UPDATE copies
                // this request's fields onto the existing spec - workerClassName included - and keeps
                // its state, run attempt count, period count and enqueue time, so the daily schedule
                // is not pushed and the stale class name is gone. It refuses only a change of *kind*
                // (one-time to periodic or back), which this is not: work-runtime 2.11.2's
                // WorkerUpdater.updateWorkImpl and the UPDATE branch of
                // WorkManagerImpl.enqueueUniquePeriodicWork, both checked in the artifact.
                workManager.enqueueUniquePeriodicWork(
                        UNIQUE_DAILY,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequest.Builder(TranslationWorker::class.java, DAILY_PERIOD_HOURS, TimeUnit.HOURS)
                                .setConstraints(network())
                                .build())
                // KEEP here, unlike the periodic row above: this fires at every process start, including
                // the one a WorkManager run of -now itself caused, and a REPLACE would cancel that run's
                // own chain. The price is that KEEP cannot rewrite a stale class name, so the one-shot
                // rows are repaired by cancellation instead - see
                // uk.xa0.tulkki.app.worker.WorkReArm, which cancels both of them once per installed build before
                // this method runs.
                workManager.enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request(0))
            } catch (e: RuntimeException) {
                Log.e("Tulkki", "could not schedule translation work", e)
            }
        }

        /** A message arrived: run as soon as the platform lets us. */
        @JvmStatic
        fun kick(context: Context) {
            try {
                WorkManager.getInstance(context)
                        .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request(0))
            } catch (e: RuntimeException) {
                Log.e("Tulkki", "could not schedule translation work", e)
            }
        }

        /** Run again in {@code delayMillis}, replacing any earlier wake-up for that moment. */
        @JvmStatic
        fun wakeAt(context: Context, delayMillis: Long) {
            try {
                WorkManager.getInstance(context)
                        .enqueueUniqueWork(
                                UNIQUE_LATER, ExistingWorkPolicy.REPLACE, request(delayMillis))
            } catch (e: RuntimeException) {
                Log.e("Tulkki", "could not schedule the next translation attempt", e)
            }
        }

        /**
         * Cancels every record this app has in WorkManager: both one-shot chains and the daily safety
         * net. This is what a process start does <em>instead of</em> [ensureScheduled] when the
         * interpreter is off, and what the live flip calls through
         * `uk.xa0.tulkki.app.UiAppHost.cancelTranslation` the moment the settings screen observes it.
         *
         * <p>A pending WorkManager record is the one place the off state could still wake the process up
         * on its own - the system starts the app for a job it holds, with no owner in sight - so "off"
         * has to be a state and not a promise: with the three rows cancelled there is no record left to
         * fire, so {@code TranslationWorker.doWork} cannot run and nothing can order the queue. The
         * spending is guarded inside the pass as well ({@code TranslationService.pump}); this is the half
         * that stops the wake-up happening at all, which is what §3.9 means by "no WorkManager record".
         *
         * <p>It is also the repair for a stale worker class name on the two one-shot rows
         * ({@code WorkReArm.staleOneShots}), so cancellation must not care why it is being called, and a
         * failure is swallowed with a log line exactly like the three enqueue paths above: a WorkManager
         * that cannot be reached must not take the process down at start.
         */
        @JvmStatic
        fun cancel(context: Context) {
            try {
                val workManager = WorkManager.getInstance(context)
                cancelEach { uniqueName -> workManager.cancelUniqueWork(uniqueName) }
            } catch (e: RuntimeException) {
                Log.e("Tulkki", "could not cancel translation work", e)
            }
        }

        /**
         * The decision [cancel] makes, with the platform's own call handed in: every name in
         * [cancellableNames], in that order, and nothing else.
         *
         * <p>Split out because {@code WorkManager} needs a device, so this is the half of the off
         * state's cancellation a JVM test can drive. It is deliberately the <em>same</em> function the
         * two callers reach - the process start and the live flip both go through [cancel] - so there is
         * one list behind the two rather than a second one written at a flip.
         */
        @JvmStatic
        fun cancelEach(cancelUniqueWork: Consumer<String>) {
            for (uniqueName in cancellableNames()) {
                cancelUniqueWork.accept(uniqueName)
            }
        }

        /**
         * The unique names [cancel] takes, in the order it takes them.
         *
         * <p>Split out so the list is a value a JVM test can read: {@code WorkManager} itself needs a
         * device, and the one thing that must hold off is that <em>every</em> record this app creates is
         * in this list - the two one-shots and the periodic row. The text is pinned by the test rather
         * than the constants, because a WorkManager unique name is stored on the device beside the
         * worker's class name: renaming one leaves the old row scheduled, unreachable by
         * {@code cancelUniqueWork} and protected from repair.
         */
        @JvmStatic
        fun cancellableNames(): List<String> = listOf(UNIQUE_NOW, UNIQUE_LATER, UNIQUE_DAILY)

        private fun request(delayMillis: Long): OneTimeWorkRequest =
                OneTimeWorkRequest.Builder(TranslationWorker::class.java)
                        .setInitialDelay(maxOf(0L, delayMillis), TimeUnit.MILLISECONDS)
                        .setConstraints(network())
                        .build()

        private fun network(): Constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
