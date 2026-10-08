package uk.xa0.tulkki.app

import org.junit.Assert
import org.junit.Test

/**
 * What "nothing is scheduled" is made of, on the JVM.
 *
 * <p>[TranslationWork.cancel] itself cannot run here: it needs a `Context` and a real
 * WorkManager, and the JVM unit tests have neither. What can be pinned is the decision it makes -
 * <em>which</em> unique names it takes, and that it takes them through the one list - and that is the
 * whole of the off state's scheduling half: off, the process start calls this instead of
 * `ensureScheduled`, and the live flip calls it through
 * `UiHost.cancelTranslation`/`UiAppHost.cancelTranslation`; every record this app ever
 * creates has to be in the list, or a row left behind is a row that can still wake the process up and
 * ask the queue to spend.
 *
 * <p>[TranslationWork.cancelEach] is that decision with the platform's own call handed in, and
 * `theLiveFlipCancelsEveryRecordThisAppHolds` drives it with a recording one, so the flip's
 * names are measured rather than assumed. It is the same function both callers reach.
 *
 * <p>The names are pinned as text rather than through the constants. A WorkManager unique name is
 * device data: it is stored in WorkManager's own database beside the worker's class name, so
 * renaming one leaves the old row scheduled, unreachable by `cancelUniqueWork` and immune to
 * repair. `WorkReArmTest` pins the two it repairs as text for the same reason, and the two
 * lists have to name the same rows - a name one pass repairs and the other forgets is exactly the
 * row that survives, which is why the daily row is asserted to be present here and asserted to be
 * absent there.
 *
 * <p><strong>What is a device check rather than a JVM one</strong>, and is not claimed here: that
 * WorkManager really cancels the three rows, that the daily schedule really stops, and that no
 * `TranslationWorker.doWork` runs afterwards on the owner's phone. The swallow itself - a
 * WorkManager that cannot be reached logging a line and returning rather than taking the process
 * down - cannot be exercised here either, and for a mechanical reason: the failure path calls
 * `android.util.Log`, whose unit-test stub throws, so any test that reached it would fail on
 * the log rather than on the behaviour.
 */
class TranslationWorkTest {

    @Test
    fun cancellingTakesEveryRecordThisAppCreates() {
        Assert.assertEquals(
            listOf(
                "tulkki-translation-now",
                "tulkki-translation-later",
                "tulkki-translation-daily"),
            TranslationWork.cancellableNames())
    }

    /**
     * The live flip's half of the same fact, driven through the function `cancel` uses.
     *
     * <p>The flip path is `TranslationSettingsStore` &rarr; `UiHost.cancelTranslation`
     * &rarr; `UiAppHost.cancelTranslation` &rarr; `TranslationWork.cancel`; the first
     * three links are compile-time, and this is the last one's decision - every name, in order, from
     * the one list the process start also takes.
     */
    @Test
    fun theLiveFlipCancelsEveryRecordThisAppHolds() {
        val cancelled = ArrayList<String>()
        TranslationWork.cancelEach { cancelled.add(it) }
        Assert.assertEquals(
            listOf(
                "tulkki-translation-now",
                "tulkki-translation-later",
                "tulkki-translation-daily"),
            cancelled)
        Assert.assertEquals(
            "the live flip and the process start cancel from the one list",
            TranslationWork.cancellableNames(),
            cancelled)
    }

    @Test
    fun theDailySafetyNetIsCancelledTooAndNotOnlyTheOneShots() {
        // The re-arm cancels the two one-shot rows and deliberately leaves the periodic one, because
        // `UPDATE` repairs that one in place. Off there is nothing to repair: a periodic row left
        // behind fires on its own schedule once a day, which is a wake-up the off state must not have.
        Assert.assertTrue(
            "the periodic row is one of the records the off state has to take",
            TranslationWork.cancellableNames().contains("tulkki-translation-daily"))
    }
}
