package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.libs.PresenceRef

/**
 * Tulkki: 3.7 C5-D. `PresenceRef.StatusRef` is a type of its own for the same language reason the
 * class comment in `PresenceRef` records, and C5-D added the second thing that can drift silently
 * with it: `StatusRef.toShowString()`, a copy of the model's pure table that `PresenceGenerator`
 * now asks the island enum for.
 *
 * A build cannot see either drift - a constant added on one side only would fall through to the
 * throwing default at runtime, on a path only a real connection reaches, and a `show` string edited
 * on one side only is a wire-behaviour change no test would otherwise notice. So this pins both, and
 * it is deliberately a pure JVM test: neither enum's static initialiser touches an Android type.
 */
class PresenceStatusRefTest {

    @Test
    fun everyModelStatusHasARefAndRoundTripsBack() {
        for (status in Presence.Status.values()) {
            val ref = status.toRef()
            Assert.assertNotNull("no StatusRef for " + status.name, ref)
            Assert.assertEquals(status.name, ref.name)
            Assert.assertSame(status, Presence.Status.fromRef(ref))
        }
    }

    @Test
    fun everyRefStatusHasAModelStatus() {
        for (ref in PresenceRef.StatusRef.values()) {
            val status = Presence.Status.fromRef(ref)
            Assert.assertEquals(ref.name, status.name)
            Assert.assertSame(ref, status.toRef())
        }
    }

    /** The two constants must be the same set, which is what makes the two loops above equivalent. */
    @Test
    fun theTwoEnumsHaveTheSameConstants() {
        Assert.assertEquals(
            Presence.Status.values().size, PresenceRef.StatusRef.values().size)
    }

    /** The island's copied `show` table must answer exactly what the model's does, for every value. */
    @Test
    fun theShowStringIsTheModels() {
        for (status in Presence.Status.values()) {
            Assert.assertEquals(
                "show string drifted for " + status.name,
                status.toShowString(),
                status.toRef().toShowString())
        }
    }
}
