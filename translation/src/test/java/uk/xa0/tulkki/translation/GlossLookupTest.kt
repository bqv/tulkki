package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * {@link GlossLookup}'s class initialisation, which is the whole reason this test could not exist.
 *
 * <p>Its main-thread poster used to be an eagerly-initialised static {@code Handler}, and the JVM
 * unit-test runtime's {@code android.jar} has no main looper: touching the class at all threw
 * {@code ExceptionInInitializerError}, so no JVM test of anything in it was possible. The merge that
 * moved every poster to {@link TulkkiMainThread} made it lazy, and this is the proof - a JVM test of
 * the class, with the eager shape kept here as the control that shows the laziness is load-bearing
 * rather than cosmetic.
 *
 * <p>What is still not testable here is the delivery: {@code onNothingToGloss} is a hop to the main
 * thread, and there is no main thread in a JVM. That stays a device check.
 */
class GlossLookupTest {

    /**
     * The shape the merge deleted, kept as the control. Under the mockable {@code android.jar} its
     * static initialiser cannot run, which is exactly what happened to {@link GlossLookup} before.
     */
    private class EagerlyHoldsAHandler {
        companion object {
            val HANDLER = android.os.Handler(android.os.Looper.getMainLooper())
        }
    }

    @Test
    fun theClassInitialisesWithoutAMainLooper() {
        // Class.forName runs the static initialiser. All four of these used to be unnecessary
        // hazards; GlossLookup was the one that actually threw.
        Class.forName("uk.xa0.tulkki.translation.GlossLookup")
        Class.forName("uk.xa0.tulkki.translation.EnglishLookup")
        Class.forName("uk.xa0.tulkki.translation.OutgoingTranslation")
        Class.forName("uk.xa0.tulkki.translation.TulkkiMainThread")
    }

    @Test
    fun anEagerlyHeldHandlerIsWhatMadeThisClassUntestable() {
        try {
            Class.forName(EagerlyHoldsAHandler::class.java.name)
            Assert.fail(
                    "an eager Handler initialised under this runtime, so the test above proves "
                            + "nothing about laziness")
        } catch (expected: ExceptionInInitializerError) {
            // The error GlossLookup's own static initialiser used to throw.
        } catch (expected: NoClassDefFoundError) {
            // The same error again on a runtime that caches the failed initialisation.
        }
    }

    @Test
    fun aCallWithNothingToAskStopsBeforeThePoster() {
        // The guard is reachable without a looper because there is nobody to tell: post(...) returns
        // at a null callback before it ever reaches the handler.
        GlossLookup.request(null, "sana", null as GlossLookup.Callback?)
        GlossLookup.request(null, null, null as GlossLookup.Callback?)
        GlossLookup.request(null, "sana", null as String?, null as GlossLookup.Callback?)
    }
}
