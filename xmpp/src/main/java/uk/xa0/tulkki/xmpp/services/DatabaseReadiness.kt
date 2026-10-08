package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import java.util.function.Consumer

/**
 * Tulkki: the database-backend slot and the ready/error state, lifted out of `XmppConnectionService`
 *.
 *
 * **The nullability decision.** The Java held the backend in a `public DatabaseBackendRef` field
 * that started null and was set at the end of the background open (`continueAfterDbInit`); the
 * wrong-key catch put it back with `this.databaseBackend = null`. The slot here is therefore
 * **nullable and not `lateinit`**: the Java's null is the real "no backend yet" state, and
 * [invalidate] is that un-install. A `lateinit` -- or an accessor that throws to keep a non-null
 * claim -- turns the state into an `UninitializedPropertyAccessException` at the moment of the read.
 * That is a crash the Java never had, and it is on the **install** path, not an edge case: the
 * `MY_PACKAGE_REPLACED` broadcast starts the service before the database is open, and
 * `MessageExpiry.expireOldMessages`'s `backend == null` early return is the Java's own guard.
 *
 * **Why the 117 Kotlin reads still compile.** [backend] returns `DatabaseBackendRef?`, but its only
 * caller is `XmppConnectionService.getDatabaseBackend()`, which is **Java** and deliberately carries
 * no `@Nullable`/`@NonNull` annotation. Kotlin therefore reads that method as the platform type
 * `DatabaseBackendRef!` - exactly the tolerance the Java field had - so `service.databaseBackend` is
 * still a legal bare dereference at every island site and a legal null check at the three sites that
 * check (`MessageExpiry`, `PresencePreferences`, `ServiceDiscovery`). Annotating the Java accessor
 * would flip the whole tree one way or the other (`@NonNull` kills the three guards, `@Nullable`
 * breaks the 117 dereferences); the Java field it replaces had no annotation.
 *
 * **Why the `installed` flag is gone.** It existed only because `lateinit` cannot be
 * un-initialised, so a boolean had to say what the Java's `= null` said. With a nullable slot the
 * flag is redundant: [hasBackend] is `backendRef != null`, which is literally what the old
 * `databaseBackend != null` asked, and [invalidate] is the assignment the Java's catch made.
 *
 * `restoredFromDatabaseLatch` and the two database executors do **not** move here: the latch is a
 * public field five island files wait on, and the executors are private values the service passes
 * into moved homes by hand.
 */
object DatabaseReadiness {

    @Volatile
    private var backendRef: DatabaseBackendRef? = null

    @Volatile
    private var ready = false

    @Volatile
    private var needsPassword = false

    @Volatile
    private var criticalError: EncryptionException? = null

    private val readyCallbacks = ArrayList<Runnable>()

    private val monitor = Any()

    /** The ordinary install: the background open finished and `continueAfterDbInit` begins. */
    @JvmStatic
    fun install(value: DatabaseBackendRef) {
        synchronized(monitor) {
            backendRef = value
        }
    }

    /** The named replacement the two `:ui` key-migration screens perform under a live process. */
    @JvmStatic
    fun swap(value: DatabaseBackendRef) {
        install(value)
    }

    /** The wrong-key path: `this.databaseBackend = null`, so the closed backend stops being reachable. */
    @JvmStatic
    fun invalidate() {
        synchronized(monitor) {
            backendRef = null
        }
    }

    /** What the old `databaseBackend != null` asked. */
    @JvmStatic
    fun hasBackend(): Boolean = backendRef != null

    /**
     * The Java field read, null before the open and after a wrong key - never a throw. The Java's
     * callers were split: some dereferenced it bare and some checked it, and each keeps what it did.
     */
    @JvmStatic
    fun backend(): DatabaseBackendRef? = backendRef

    @JvmStatic
    fun markReady() {
        ready = true
    }

    @JvmStatic
    fun isReady(): Boolean = ready

    @JvmStatic
    fun setNeedsPassword(value: Boolean) {
        needsPassword = value
    }

    @JvmStatic
    fun needsPassword(): Boolean = needsPassword

    @JvmStatic
    fun setCriticalError(value: EncryptionException?) {
        criticalError = value
    }

    @JvmStatic
    fun criticalError(): EncryptionException? = criticalError

    @JvmStatic
    fun isInitializing(): Boolean = !ready && !needsPassword && criticalError == null

    /**
     * The Java's `runWhenDatabaseReady`: main thread only, and in a terminal state the callback is
     * posted rather than queued. The list stays a plain `ArrayList` for the same reason the Java's
     * was one - it is only ever touched on the main thread.
     */
    @JvmStatic
    fun runWhenReady(r: Runnable, post: Consumer<Runnable>) {
        if (!isInitializing()) {
            post.accept(r)
        } else {
            readyCallbacks.add(r)
        }
    }

    @JvmStatic
    fun notifyReadyCallbacks() {
        for (r in readyCallbacks) r.run()
        readyCallbacks.clear()
    }
}
