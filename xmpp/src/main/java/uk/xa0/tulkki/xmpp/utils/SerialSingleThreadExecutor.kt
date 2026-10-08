package uk.xa0.tulkki.xmpp.utils

import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import uk.xa0.tulkki.xmpp.Config

/**
 * One background thread, a queue of tasks, and the first task starts the queue draining.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The class is `open`** because [ReplacingSerialSingleThreadExecutor] extends it; Kotlin classes
 *    are final by default and the Java one was not.
 * 2. **`tasks` is `@JvmField internal`**, which is the tree's established shape for a Java
 *    package-private field a Kotlin subclass reads (`ServiceDiscoveryResult.forms`): the JVM field
 *    keeps its name and the subclass's `tasks.clear()` is the Java line.
 * 3. **`active` stays a `protected var` of type `Runnable?`, as a `@JvmField`.** Java's was null
 *    until the first poll and was a *field*, and the subclass's `instanceof` test is written as a
 *    safe cast (`as?`) because Kotlin cannot smart-cast a mutable property.
 * 4. **`scheduleNext` assigns before testing, as Java did**, but through a local: Kotlin cannot
 *    smart-cast a property, so `val next = tasks.poll()` is what makes `executor.execute(next)`
 *    compile without changing the assignment's timing.
 * 5. **`Runner` stays a `private inner` class**, so it keeps the outer instance and the
 *    `scheduleNext()` call in its `finally`, as the Java inner class did.
 * 6. **Both `synchronized` methods keep `@Synchronized`** (they lock `this`, as Java did), and
 *    `execute` is `override` because the class still implements [Executor]; Java instantiates it
 *    directly (`XmppConnectionService:322-329`), so the constructor and the class name are unchanged.
 */
open class SerialSingleThreadExecutor(private val name: String) : Executor {

    /**
     * Tulkki: what this executor knows how to stop.
     *
     * <p>Pair 4 of {@code docs/MIGRATION.md} "The cycle rules" §3. The interface used to be {@code
     * uk.xa0.tulkki.app.utils.Cancellable} and this file imported it, which is what made the island
     * name the composition root; it is declared here now, where the only code that consumes the
     * contract lives, and {@code uk.xa0.tulkki.app.utils.Cancellable} extends it so the one `:app`
     * implementer ({@code MessageSearchTask}) still satisfies the {@code instanceof} below without
     * naming the island. Nesting it rather than giving it a file of its own keeps the tree's
     * top-level names unchanged: a second top-level {@code Cancellable} would be a name in two
     * modules, which {@code tools/check-modules} reports.
     */
    interface Cancellable {
        fun cancel()
    }

    @JvmField internal val tasks: ArrayDeque<Runnable> = ArrayDeque()
    private val executor: Executor = Executors.newSingleThreadExecutor()

    /**
     * The task in flight, or null. Java's `protected Runnable active` was a *field*, and the
     * `@JvmField` keeps it one: the subclass reads it, and the Java half of the tree that still
     * exists for one commit longer reads `active instanceof Cancellable` as a field access.
     */
    @JvmField protected var active: Runnable? = null

    @Synchronized
    override fun execute(r: Runnable) {
        tasks.offer(Runner(r))
        if (active == null) {
            scheduleNext()
        }
    }

    @Synchronized
    private fun scheduleNext() {
        val next = tasks.poll()
        active = next
        if (next != null) {
            executor.execute(next)
            val remaining = tasks.size
            if (remaining > 0) {
                Log.d(Config.LOGTAG, "$remaining remaining tasks on executor '$name'")
            }
        }
    }

    private inner class Runner(private val runnable: Runnable) : Runnable, Cancellable {

        override fun cancel() {
            (runnable as? Cancellable)?.cancel()
        }

        override fun run() {
            try {
                runnable.run()
            } finally {
                scheduleNext()
            }
        }
    }
}
