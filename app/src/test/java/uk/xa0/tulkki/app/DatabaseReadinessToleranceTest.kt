package uk.xa0.tulkki.app

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.xmpp.services.DatabaseReadiness

/**
 * Tulkki: the database-backend slot tolerates being unset, exactly as the Java field did.
 *
 * <p>The install broadcast (`MY_PACKAGE_REPLACED`) starts `XmppConnectionService` before the
 * background open has finished, and `onStartCommand`'s tail calls `expireOldMessages()`; the Java's
 * `final DatabaseBackend backend = databaseBackend; if (backend == null) return;` answered that with
 * an early return. A Kotlin `lateinit` (or an accessor that throws to keep a non-null claim) turned
 * the same read into `UninitializedPropertyAccessException: databaseBackend` and killed the process.
 *
 * <p>This pins the declaration half of the repair: [DatabaseReadiness.backend] is a null read before
 * the open and after a wrong key, [DatabaseReadiness.hasBackend] is the old `databaseBackend != null`,
 * and install/un-install round-trips the instance. The caller half
 * (`MessageExpiry.expireOldMessages`'s `?: return`) is the Java's own line and needs a live
 * `Service`, so it is not reachable from a JVM test.
 */
class DatabaseReadinessToleranceTest {

    @Test
    fun backendReadsNullBeforeTheOpenAndAfterAKeyFailure() {
        DatabaseReadiness.invalidate()
        assertFalse(DatabaseReadiness.hasBackend())
        assertNull(DatabaseReadiness.backend())

        val backend =
            Proxy.newProxyInstance(
                DatabaseBackendRef::class.java.classLoader,
                arrayOf<Class<*>>(DatabaseBackendRef::class.java),
                InvocationHandler { _, _, _ -> null },
            ) as DatabaseBackendRef

        DatabaseReadiness.install(backend)
        assertTrue(DatabaseReadiness.hasBackend())
        assertSame(backend, DatabaseReadiness.backend())

        // The wrong-key path's `this.databaseBackend = null`.
        DatabaseReadiness.invalidate()
        assertFalse(DatabaseReadiness.hasBackend())
        assertNull(DatabaseReadiness.backend())
    }
}
