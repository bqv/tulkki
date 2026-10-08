package uk.xa0.tulkki.translation

/**
 * Which gloss question is the current one, and what a finished one is allowed to keep.
 *
 * <p>[GlossLookup] runs one lookup at a time on one thread on purpose: the cache is read when
 * a lookup starts, so two lookups in flight can both miss the entry the other is buying - the same
 * word paid for twice - and both would count against the same daily cap at the same time. The cost
 * of that thread is that a word tapped while another lookup is still out waits behind it, and for up
 * to the client's timeout the second popup can show nothing but its spinner.
 *
 * <p>The cure is not a wider pool, which would buy the double purchase back, but
 * <strong>supersession</strong>: the tap that arrives last is the question worth asking, so it
 * cancels the question already out and takes its place. An answer to a question the owner has moved
 * on from is worth less than the tokens it cost, so it is dropped rather than delivered.
 *
 * <p><strong>Cancelling has to reach the socket, not merely the callback.</strong> A request that is
 * ignored still runs to completion and is paid for. [Attempt.attach] takes the live call the
 * moment it exists, and a cancellation that arrived before the call did cancels it on the spot, so
 * there is no window in which the work is out and unstoppable.
 *
 * <p>Pure Kotlin - no Android, no okhttp - so the bookkeeping is exercised by JVM unit tests, which
 * is why it lives here rather than inside [GlossLookup]: that class posts through a
 * `Handler` and reads a `Context`, and neither exists in a unit test. `mayBegin` is `@JvmStatic`,
 * and `Attempt`'s constructor is `internal` because it was private in the Java - the outer class is
 * the only thing that makes one, and Kotlin's nested classes do not let the outer class reach a
 * private constructor as Java's do.
 */
class GlossLookups {

    /** Anything already out that can be stopped: the socket call, once it exists. */
    interface Cancellable {
        fun cancel()
    }

    /**
     * What a lookup with an answer in hand is allowed to do with it.
     *
     * <p>Two effects, and the whole point of one interface for them is that they are not the same
     * decision: the tokens the API reported were spent and are counted either way, while the answer
     * itself is kept - cached and handed over - only while the question is still being asked.
     */
    interface Settlement {
        /**
         * The API reported this much usage: it was spent, so the shared counter and the per-day
         * ledger both take it. The split and not only the total, because the prompt-cache share is
         * what decides the price of what was spent.
         */
        fun count(usage: DeepSeekClient.Usage)

        /** The answer is still wanted: cache it, and hand it to whoever asked. */
        fun keep()
    }

    private val lock = Any()

    private var currentAttempt: Attempt? = null

    companion object {

        /**
         * Whether a tapped word may become a question at all.
         *
         * <p>Pure, and here for the same reason everything else in this class is: [GlossLookup]
         * reads a `Context` and posts its answer through a `Handler`, so a JVM test can
         * neither hand it an off interpreter nor hear what it answers. A `null` interpreter is off:
         * it is a caller with nothing to say, not a licence.
         */
        @JvmStatic
        fun mayBegin(interpreter: Interpreter?): Boolean =
                interpreter != null && interpreter.enabled()
    }

    /**
     * Start a question, superseding the one before it.
     *
     * <p>The earlier attempt stops being current - and its socket, if it has one already, is
     * cancelled - before this returns, so the tap that arrives last is never the one made to wait.
     */
    fun begin(): Attempt {
        val started = Attempt()
        val superseded =
                synchronized(lock) {
                    val previous = currentAttempt
                    currentAttempt = started
                    previous
                }
        if (superseded != null) {
            superseded.cancel()
        }
        return started
    }

    /** The question being asked right now, or `null` when none is out. */
    fun current(): Attempt? = synchronized(lock) { currentAttempt }

    /**
     * This lookup is over, however it ended: it stops being the current one. A later question that
     * has already taken its place is left alone, which is what keeps a slow answer from clearing the
     * tap that superseded it.
     */
    fun finish(attempt: Attempt) {
        synchronized(lock) {
            if (currentAttempt === attempt) {
                currentAttempt = null
            }
        }
    }

    /** One question's life, from [begin] until it is superseded or finished. */
    class Attempt internal constructor() {

        private val lock = Any()

        private var cancelled = false

        /** The socket call, once the worker has one. */
        private var out: Cancellable? = null

        /**
         * Whether a later tap has taken this question's place. Everything this attempt still had to
         * say - a cache entry, a word to the callback - is dropped once it is true.
         */
        fun isCancelled(): Boolean = synchronized(lock) { cancelled }

        /**
         * Hand in the work that is out, so cancelling this attempt can stop it.
         *
         * <p>Called by [GlossLookup] from the worker, before the call is executed. An attempt
         * that is already cancelled cancels it here and now - the case worth naming, because it is
         * the one the tap that superseded it created: the worker was still reading the cache when
         * the next word was tapped, and the request must not reach the network at all.
         */
        fun attach(work: Cancellable?) {
            if (work == null) {
                return
            }
            val immediately =
                    synchronized(lock) {
                        val already = cancelled
                        if (!already) {
                            out = work
                        }
                        already
                    }
            if (immediately) {
                work.cancel()
            }
        }

        /** Abandon this question: the socket stops, and nothing it produces is kept. Idempotent. */
        fun cancel() {
            val work =
                    synchronized(lock) {
                        if (cancelled) {
                            return
                        }
                        cancelled = true
                        out
                    }
            if (work != null) {
                work.cancel()
            }
        }

        /**
         * Settle an answer that came back.
         *
         * <p>The reported tokens are counted first and unconditionally: they were spent whatever
         * happens next, and the counter follows the invoice rather than the owner's attention. That
         * is also the only honest amount - a request cancelled before the API answered reported no
         * usage at all, so it counts nothing. The answer itself is kept only while this attempt is
         * still current: a superseded lookup writes no cache entry and reaches no popup.
         *
         * @param reported what the answer's `usage` said, [DeepSeekClient.Usage.none]
         *     when it said nothing
         * @return true when the answer was kept, false when it was dropped as superseded
         */
        fun settle(reported: DeepSeekClient.Usage?, settlement: Settlement): Boolean {
            if (reported != null && !reported.isEmpty()) {
                settlement.count(reported)
            }
            if (isCancelled()) {
                return false
            }
            settlement.keep()
            return true
        }
    }
}
