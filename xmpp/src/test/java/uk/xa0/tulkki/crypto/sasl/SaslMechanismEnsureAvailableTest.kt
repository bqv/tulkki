package uk.xa0.tulkki.crypto.sasl

import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.tulkki.xmpp.utils.SSLSockets

/**
 * Tulkki: the half of the registration crash the JVM can reach.
 *
 * On the handset the app died on the connection thread with
 * `NullPointerException: getQuickStartMechanism(...) must not be null`, thrown while establishing
 * the stream after TLS had switched over. The account genuinely had no quick-start mechanism, and
 * `AccountRef.getQuickStartMechanism()` is Java, so Kotlin saw a platform type `SaslMechanism!` and
 * `ensureAvailable`'s first parameter demanded non-null; the compiler inserted
 * `checkNotNullExpressionValue` and the null the Java would have passed through became the crash.
 *
 * What this cell pins is the Java original's own behaviour with that null, which is the fact the
 * fix rests on and the only half a test can reach - the other half is a real TLS handshake, and the
 * JVM suite cannot perform one (`SaslMechanism.java:161`, `null instanceof ChannelBindingMechanism`
 * is false, so the `requireChannelBinding` arm returns `null` and the final `else` returns the
 * mechanism it was given, which is `null`). It never dereferenced the argument.
 *
 * Only the `requireChannelBinding = false` arm is exercised: the `true` arm logs through
 * `android.util.Log`, which the off-device runtime does not implement. Both return null in the Java.
 */
class SaslMechanismEnsureAvailableTest {

    @Test
    fun aNullMechanismWithoutChannelBindingComesBackAsNull() {
        assertNull(
            "the Java returned the mechanism it was handed, and it was handed null",
            SaslMechanism.ensureAvailable(null, SSLSockets.Version.NONE, false),
        )
    }
}
