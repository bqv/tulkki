package uk.xa0.tulkki.crypto.axolotl

import java.lang.reflect.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tulkki: the activation clock moves only when the key really was inactive - the port of upstream's
 * fix in `055b57172d` (port-11's span read, group F).
 *
 * `toActive()` used to test the **freshly constructed** result instead of the object it is called
 * on: `if (!status.active)` where `status` is the new instance, which is always inactive. Every
 * reactivation therefore rewrote `last_activation` - the field the auto-expiry of inactive own
 * devices measures - silently restarting that clock. The fixed condition is `!this.active`, and the
 * effect is that reactivating an already-active key leaves `lastActivation` at `DO_NOT_OVERWRITE`
 * (`-1`), so `persist` does not write the column at all.
 *
 * `lastActivation` has no accessor - it exists only to be persisted - so the cell reads the field
 * directly. That is the honest instrument here: the alternative is a `ContentValues`, an Android
 * stub off-device, and the field is the fact.
 */
class FingerprintStatusTest {

    private val DO_NOT_OVERWRITE = -1L

    @Test
    fun aSecondReactivationDoesNotMoveTheActivationClock() {
        val active = FingerprintStatus.createActiveUndecided()
        assertTrue("the factory stamps an activation time", lastActivation(active) > 0)

        val again = active.toActive()

        assertTrue("toActive keeps the key active", again.isActive())
        assertEquals(
            "a reactivation must leave the stored clock alone",
            DO_NOT_OVERWRITE,
            lastActivation(again))
    }

    @Test
    fun anInactiveKeyIsStampedWhenItBecomesActive() {
        val inactive = FingerprintStatus.createInactiveVerified()
        assertEquals(
            "an inactive key carries no activation time",
            DO_NOT_OVERWRITE,
            lastActivation(inactive))

        val nowActive = inactive.toActive()

        assertTrue(nowActive.isActive())
        assertTrue(
            "an inactive key that becomes active is stamped now",
            lastActivation(nowActive) > 0)
    }

    private fun lastActivation(status: FingerprintStatus): Long {
        val field: Field = FingerprintStatus::class.java.getDeclaredField("lastActivation")
        field.isAccessible = true
        return field.getLong(status)
    }
}
