package uk.xa0.tulkki.app.worker

import org.junit.Assert
import org.junit.Test

/**
 * The decision half of the process-start re-arm, on the JVM.
 *
 * <p>[WorkReArm.ifNeeded] itself cannot run here - it needs a `Context` and a real
 * WorkManager, and the JVM unit tests have neither. What can be pinned is everything that decides
 * <em>what</em> it does: the stored interval is read exactly as the interval listener reads it (so
 * "off" means the same thing to both callers), the fingerprint is a build number rather than a
 * name, so the next rename pass has nothing to rewrite, and the rows it cancels are named - because
 * a WorkManager unique name is stored on the device beside the worker's class name, so the spelling
 * is data.
 *
 * <p><strong>What is a device check rather than a JVM one</strong>, and is not claimed by these
 * tests: that WorkManager really does rewrite `worker_class_name` out of the `UPDATE`
 * policy and instantiate the new class on the next run, that the `REPLACE` re-arm of the
 * recurring backup really lands, and that the daily translation run resumes on the owner's phone. The
 * policies are read out of the work-runtime 2.11.2 artifact (see the commit that landed this), but a
 * database that has already been rewritten can only be seen on a device.
 */
class WorkReArmTest {

    @Test
    fun aStoredIntervalIsReadAsSeconds() {
        Assert.assertEquals(86400L, WorkReArm.intervalSeconds("86400"))
        Assert.assertEquals(3600L, WorkReArm.intervalSeconds("3600"))
    }

    @Test
    fun anAbsentOrUnreadableIntervalSchedulesNothing() {
        // The preference XML's default is only written when the screen is inflated, so "never
        // opened" really does read as null - and that must mean "nothing was ever scheduled".
        Assert.assertNull(WorkReArm.intervalSeconds(null))
        Assert.assertNull(WorkReArm.intervalSeconds(""))
        Assert.assertNull(WorkReArm.intervalSeconds("daily"))
        Assert.assertNull(WorkReArm.intervalSeconds("86400s"))
    }

    @Test
    fun anExplicitZeroIsOff() {
        Assert.assertEquals(0L, WorkReArm.intervalSeconds("0"))
    }

    @Test
    fun anIntervalThatIsNoRecurrenceCancelsAndOneThatIsSchedules() {
        // The decision `ifNeeded` takes with the value above, split out so it can be pinned: the
        // repair cancels the row when the preference says nothing or says off, and only enqueues a
        // real recurrence. "off" and "enqueued" must not be able to disagree.
        Assert.assertTrue(WorkReArm.schedulesNothing(null))
        Assert.assertTrue(WorkReArm.schedulesNothing(0L))
        Assert.assertTrue(WorkReArm.schedulesNothing(-1L))
        Assert.assertFalse(WorkReArm.schedulesNothing(86400L))
    }

    @Test
    fun theCancelledRowsAreTheTwoOneShotsAndNeverTheDaily() {
        // The text, not the constants: a unique name WorkManager holds a row under is device data,
        // and renaming one leaves the old row scheduled and unreachable. Pinning the literals makes
        // such a rename a decision that fails a test, which is what the two renames that caused this
        // repair did not have.
        Assert.assertEquals(
            listOf("tulkki-translation-now", "tulkki-translation-later"),
            WorkReArm.staleOneShots())
        // The periodic row is repaired in place by ExistingPeriodicWorkPolicy.UPDATE, at every
        // process start; cancelling it would throw away the daily schedule instead.
        Assert.assertFalse(
            "the daily row is repaired in place, never cancelled",
            WorkReArm.staleOneShots().contains("tulkki-translation-daily"))
    }

    @Test
    fun onlyTheBuildThatReArmedIsNotStale() {
        Assert.assertTrue("a build that never re-armed must be stale", WorkReArm.stale(0, 2050))
        Assert.assertTrue("an older build's fingerprint is stale", WorkReArm.stale(2049, 2050))
        Assert.assertFalse(
            "the same build must not reset the period again", WorkReArm.stale(2050, 2050))
    }

    @Test
    fun theFingerprintKeyIsNotPackageShaped() {
        // docs/MIGRATION.md "The upgrade audit"'s own instruction: the marker must not look like a
        // package, or the next rename pass rewrites it and every install re-arms (or, worse, none
        // does).
        Assert.assertFalse(
            "the fingerprint key must not carry a package root: " + WorkReArm.KEY_BUILD,
            WorkReArm.KEY_BUILD.contains("."))
    }
}
