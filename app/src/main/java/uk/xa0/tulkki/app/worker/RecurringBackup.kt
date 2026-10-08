package uk.xa0.tulkki.app.worker

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.PeriodicWorkRequest
import java.util.concurrent.TimeUnit

/**
 * The one recurring backup, and the one place that says what it is.
 *
 * <p>Before this, the unique name, the constraints and the input flag lived only inside
 * `BackupSettingsFragment`'s interval listener. That is why the persisted row had no other code that
 * could rebuild it: WorkManager stores the worker's fully-qualified class name in its own database,
 * and the one listener that builds the request only runs when the owner changes the interval. Both
 * halves are here now, so `WorkReArm` and the settings listener build the same request by
 * construction rather than by two copies agreeing.
 *
 * <p>`request` is a companion `@JvmStatic fun` because its callers are all Java
 * (`UiAppHost`, `WorkReArm` and `RecurringBackupTest`), and `NAME` a companion `const val` so Java
 * still reads it as the static field it always was.
 */
class RecurringBackup private constructor() {

    companion object {

        /**
         * The string the whole feature is persisted under - three times over: the preference key the
         * owner's interval is written to, the unique work name WorkManager keeps beside the worker's
         * class name, and the worker's own input flag. All three are on the device and all three are
         * the same text by history, so there is one constant and no second spelling to drift.
         */
        const val NAME = "recurring_backup"

        /** The request the interval preference builds, whichever caller needs it. */
        @JvmStatic
        fun request(intervalSeconds: Long): PeriodicWorkRequest {
            val constraints =
                    Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresStorageNotLow(true)
                            .build()
            return PeriodicWorkRequest.Builder(
                            ExportBackupWorker::class.java, intervalSeconds, TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .setInputData(Data.Builder().putBoolean(NAME, true).build())
                    .build()
        }
    }
}
