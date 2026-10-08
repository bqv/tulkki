package uk.xa0.tulkki.translation

/**
 * How long to wait before trying a failed call again.
 *
 * <p>The client already says whether a failure is worth retrying at all (429/5xx/network yes, a
 * rejected key or an unparseable answer no). This is the schedule for the ones that are: growing, so
 * a DeepSeek outage does not turn into a request every few seconds, and finite, so a message that
 * keeps failing becomes a visible failure instead of a queue item that is retried forever.
 *
 * <p>Nothing here sleeps. The caller turns a delay into a scheduled job and lets the platform wake
 * it; a sleeping loop would hold a thread and a radio open for no reason.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object TranslationBackoff {

    /** Attempt 1 waits this long, and so on down the list. */
    private val DELAYS =
            longArrayOf(
                    30_000L, // 30 s
                    120_000L, // 2 min
                    600_000L, // 10 min
                    3_600_000L, // 1 h
                    21_600_000L) // 6 h

    /** After this many failed attempts a message is failed, not retried. */
    @JvmField val MAX_ATTEMPTS = DELAYS.size

    /**
     * The delay before attempt number `attempts` (1-based: the wait after the first failure). Values
     * beyond the table keep the last delay, so this never returns something absurd.
     */
    @JvmStatic
    fun delayMillis(attempts: Int): Long {
        if (attempts < 1) {
            return DELAYS[0]
        }
        return DELAYS[minOf(attempts, DELAYS.size) - 1]
    }

    /** Whether an item that has already failed `attempts` times may be tried once more. */
    @JvmStatic
    fun retryable(attempts: Int): Boolean = attempts < MAX_ATTEMPTS
}
