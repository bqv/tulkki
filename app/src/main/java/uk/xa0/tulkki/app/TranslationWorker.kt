package uk.xa0.tulkki.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * One pass of the translation queue, as a job the platform can run when the phone is awake and
 * online - including after the app process was killed with work still owed.
 *
 * <p>The worker decides nothing: [TranslationService.pump] does the work and
 * [TranslationService.nextWakeUpMillis] says when to come back, which is either a retry's due time or
 * the start of the next day after the cap was reached.
 */
class TranslationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {

    override fun doWork(): Result {
        val context = applicationContext
        val service = TranslationHooks.get(context)
        val now = System.currentTimeMillis()
        val outcome = service.pump(now)
        val delay = service.nextWakeUpMillis(now, outcome)
        if (delay >= 0) {
            TranslationWork.wakeAt(context, delay)
        }
        return Result.success()
    }
}
