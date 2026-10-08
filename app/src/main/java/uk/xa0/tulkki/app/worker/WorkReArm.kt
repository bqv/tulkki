package uk.xa0.tulkki.app.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.google.common.base.Strings
import com.google.common.primitives.Longs
import uk.xa0.tulkki.app.BuildConfig
import uk.xa0.tulkki.app.TranslationWork
import uk.xa0.tulkki.xmpp.Config

/**
 * The second half of what the rename broke, and the one repair that has to happen at process start.
 *
 * <p>An `AlarmManager` alarm and a posted notification are cleared by the platform eventually; a
 * WorkManager row is not. WorkManager keeps `WorkSpec.worker_class_name` in its own database and
 * instantiates the class by reflection, and the app installs no `WorkerFactory` and no
 * `Configuration.Provider`, so a periodic backup enqueued by the build that was installed before a
 * rename names a class that no longer exists: every run fails to instantiate, the row is not
 * rewritten, and the only code that builds the request is a settings-change listener. The owner's
 * scheduled backups stop, the screen still shows the interval they picked, and nothing says so.
 *
 * <p>So the request is put back at every process start, guarded by a fingerprint so that "every
 * start" means "once per installed build": `REPLACE` deletes the stale spec and inserts this one,
 * which repairs the class name by construction, and it also resets the period - re-arming a daily
 * backup on every cold start would push it past the horizon on a phone that opens the app twice a
 * day. The fingerprint is deliberately not package-shaped, so the next rename pass does not rewrite
 * it.
 *
 * <p>The translation queue's own rows are repaired in the same pass, and they are not repaired the
 * same way. Its periodic run is re-enqueued with `UPDATE` at every process start
 * (`TranslationWork.ensureScheduled`), which rewrites the class name in place and keeps the daily
 * schedule - but the two one-shot rows are enqueued with policies that cannot, so they are cancelled
 * here instead: `staleOneShots()`. Cancelling costs nothing, because the rows that matter live in
 * `TranslationStore`, not in WorkManager.
 *
 * <p>What this cannot do is decide for the owner: `null` (the screen was never opened, so nothing was
 * ever scheduled) and `<= 0` (the interval is off) both cancel and schedule nothing, so "off" and
 * "enqueued" can never disagree. Failures are swallowed with a log line, like the translation
 * queue's own scheduling: a WorkManager that cannot be reached must not take the process down at
 * start.
 *
 * <p>This class is called from `TulkkiApplication.onCreate`, so it reads no Keystore and builds no
 * `EncryptedSharedPreferences`: a plain default-preferences read, one WorkManager call, and one
 * integer write per installed build.
 *
 * <p>**A Java test reads the package-private half, so those members keep their JVM names.**
 * `WorkReArmTest` is Java in this package and calls `intervalSeconds`, `schedulesNothing`, `stale`
 * and `staleOneShots`, and reads `KEY_BUILD`. Kotlin's `internal` mangles a function's JVM name
 * (`intervalSeconds$app`), which a Java caller cannot spell, so the four decision functions are
 * `public @JvmStatic`; `KEY_BUILD` stays an `internal const val`, whose static field is *not*
 * mangled. `ifNeeded` is `@JvmStatic` for its Java caller, `TulkkiApplication`.
 */
class WorkReArm private constructor() {

    companion object {

        /**
         * The build that last re-armed; a build's own versionCode, so it is not a name (or a
         * package).
         */
        internal const val KEY_BUILD = "work_rearm_build"

        /** Called from `TulkkiApplication.onCreate`, before anything can read the queued row. */
        @JvmStatic
        fun ifNeeded(context: Context) {
            try {
                val preferences: SharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(context)
                val versionCode = BuildConfig.VERSION_CODE
                if (!stale(preferences.getInt(KEY_BUILD, 0), versionCode)) {
                    return
                }
                val workManager = WorkManager.getInstance(context)
                for (oneShot in staleOneShots()) {
                    workManager.cancelUniqueWork(oneShot)
                }
                val interval = intervalSeconds(preferences.getString(RecurringBackup.NAME, null))
                if (schedulesNothing(interval)) {
                    workManager.cancelUniqueWork(RecurringBackup.NAME)
                } else {
                    // `schedulesNothing` has just excluded null and non-positive, so this is a real
                    // recurrence - the Java unboxed it without saying so.
                    workManager.enqueueUniquePeriodicWork(
                            RecurringBackup.NAME,
                            ExistingPeriodicWorkPolicy.REPLACE,
                            RecurringBackup.request(interval!!))
                }
                preferences.edit().putInt(KEY_BUILD, versionCode).apply()
            } catch (e: RuntimeException) {
                Log.e(Config.LOGTAG, "could not re-arm the recurring backup", e)
            }
        }

        /**
         * The unique names this pass cancels rather than re-enqueues, and why exactly these two.
         *
         * <p>`TranslationWork.ensureScheduled` re-enqueues `-now` with `KEEP`, and `KEEP` is a no-op
         * whenever a spec with that name exists: it never compares class names, so a stale `-now`
         * whose network constraint has kept it pending would be protected by it indefinitely.
         * `-later` is re-enqueued with `REPLACE`, so it repairs itself the next time the queue
         * computes a due time, but it is cancelled here too - the two are one decision, and a pending
         * stale `-later` would otherwise fire once and fail before that. The periodic row is
         * deliberately not in this list: `UPDATE` repairs it in place, and cancelling it would throw
         * away the schedule the owner is on.
         *
         * <p>What makes the cancellation free is where the state lives. A queued translation, its
         * attempt count and its next-attempt time are rows in `TranslationStore`'s own table (the
         * persisted `next_attempt_at`), and `TranslationWork` schedules the next wake-up from
         * `TranslationService.nextWakeUpMillis`, which reads that stored due time back out of the
         * database. No WorkManager row carries anything that is not recomputed from the store, so the
         * `-now` that `ensureScheduled` enqueues immediately after this pass picks up everything that
         * is due and lays the next `-later` back down.
         */
        @JvmStatic
        fun staleOneShots(): List<String> =
                listOf(TranslationWork.UNIQUE_NOW, TranslationWork.UNIQUE_LATER)

        /**
         * The stored recurrence in seconds, or `null` when the preference says nothing usable. The
         * same read and the same parse as the interval listener's, kept identical on purpose: the two
         * callers must agree about what "off" is.
         */
        @JvmStatic
        fun intervalSeconds(raw: String?): Long? = Longs.tryParse(Strings.nullToEmpty(raw))

        /** True when the stored value is not a recurrence at all: never scheduled, or turned off. */
        @JvmStatic
        fun schedulesNothing(intervalSeconds: Long?): Boolean =
                intervalSeconds == null || intervalSeconds <= 0L

        /** True when this build has not put its own request in place yet. */
        @JvmStatic
        fun stale(recordedBuild: Int, versionCode: Int): Boolean = recordedBuild != versionCode
    }
}
