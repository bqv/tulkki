package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.os.Handler
import android.util.SparseArray
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager.Companion.LOGGER
import java.lang.Object
import java.util.logging.Level

/**
 * The process-wide inbox of open certificate decisions.
 *
 * <p>It owns what the Java class kept as file statics: the monotonically increasing decision id,
 * the `SparseArray` under its own monitor, and the blocker object a socket thread waits on. The
 * `Decision` shape is unchanged - one `int` field, whose four answers are the public constants the
 * decision screen writes.
 *
 * <p><strong>The ordering is behaviour.</strong> [interact] posts the screen to the main handler and
 * then blocks the calling (socket) thread on the monitor; `interactResult` is what wakes it. A
 * socket thread must never hold the main thread, and the answer can only arrive after the screen
 * exists, so the post comes first and the wait second. The `wait`/`notify` pair is the Java one,
 * reached through the `java.lang.Object` cast Kotlin requires.
 */
internal object MtmDecisions {

    internal const val DECISION_INVALID = 0
    internal const val DECISION_ABORT = 1
    internal const val DECISION_ONCE = 2
    internal const val DECISION_ALWAYS = 3

    /** The blocker object `interact` waits on: one field, written by [resolve]. */
    internal class Decision {
        var state: Int = DECISION_INVALID
    }

    private var decisionId = 0

    private val openDecisions = SparseArray<Decision>()

    private fun create(decision: Decision): Int {
        synchronized(openDecisions) {
            val myId = decisionId
            openDecisions.put(myId, decision)
            decisionId += 1
            return myId
        }
    }

    /** The body of `MemorizingTrustManager.interactResult`, which the decision screen calls. */
    fun resolve(decisionId: Int, choice: Int) {
        val decision: Decision?
        synchronized(openDecisions) {
            decision = openDecisions.get(decisionId)
            openDecisions.remove(decisionId)
        }
        if (decision == null) {
            LOGGER.log(
                    Level.SEVERE,
                    "interactResult: aborting due to stale decision reference!")
            return
        }
        synchronized(decision) {
            decision.state = choice
            (decision as Object).notify()
        }
    }

    /**
     * Post the decision screen and block until [resolve] answers it. `uri` is the decision's own
     * URI, which [DecisionScreenPort] turns into its intent's extras.
     */
    fun interact(
            masterHandler: Handler,
            decisionScreen: MemorizingTrustManager.DecisionScreenPort,
            ui: Context,
            message: String,
            titleId: Int
    ): Int {
        val choice = Decision()
        val myId = create(choice)
        masterHandler.post {
            // Pair 11 (D4): the intent this used to build is the decision screen's own vocabulary,
            // and this island may not name a `:ui` class. The port is handed exactly what the intent
            // carried; the try/catch is unchanged, so a missing activity degrades the same way.
            try {
                decisionScreen.open(
                        ui,
                        MemorizingTrustManager::class.java.name + "/" + myId,
                        myId,
                        message,
                        titleId)
            } catch (e: Exception) {
                LOGGER.log(Level.FINE, "startActivity(MemorizingActivity)", e)
            }
        }
        LOGGER.log(Level.FINE, "openDecisions: $openDecisions, waiting on $myId")
        try {
            synchronized(choice) {
                (choice as Object).wait()
            }
        } catch (e: InterruptedException) {
            LOGGER.log(Level.FINER, "InterruptedException", e)
        }
        LOGGER.log(Level.FINE, "finished wait on $myId: ${choice.state}")
        return choice.state
    }
}
