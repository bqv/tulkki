package uk.xa0.tulkki.translation

/**
 * How long a single send waits before asking DeepSeek the same question again.
 *
 * <p>This is deliberately not [TranslationBackoff]. That one is the *queue's* schedule: it is long
 * (thirty seconds at the first failure, then minutes and hours) because a received message can wait,
 * and its whole job is to stop a broken account being hammered while the app is not looking. This one
 * belongs to one send the owner has just made and is standing in front of: the message is held, the
 * composer is waiting, and the only question is whether a blip lasts longer than a breath. So the
 * delays are seconds, the last of them fifteen, and after them the failure is real and the owner is
 * told - the ordinary hold, with its reason, and the owner's own tap as the only further retry.
 *
 * <p>Nothing here sleeps, and nothing here decides whether a failure is retryable in the first place:
 * the client does that ([DeepSeekClient.TranslationException.retryable]), and a non-retryable
 * failure - a rejected key, an empty balance, an answer that is not the contract - is never waited on.
 * [OutgoingTranslation.requestForSendWithRetry] is the one caller and it owns the waiting.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object SendRetry {

    /**
     * The waits, in order, after the failures that are worth waiting out: two seconds, five, then
     * fifteen - about twenty-two seconds in all, which covers a radio coming back and a rate limit
     * clearing without leaving the composer's message held while the owner gives up on it. After that
     * the failure is real and the owner is told.
     */
    private val DELAYS = longArrayOf(2_000L, 5_000L, 15_000L)

    /** How many requests one send's decision may make in total, retries included. */
    @JvmField val ATTEMPTS = DELAYS.size + 1

    /**
     * The wait before attempt number `attempts + 1`, where `attempts` is the number of attempts that
     * have already failed (so the first failure waits [delayMillis] of 1). Values beyond the table
     * keep the last delay, which is unreachable through [retryable] but not absurd if it were.
     */
    @JvmStatic
    fun delayMillis(attempts: Int): Long {
        if (attempts < 1) {
            return DELAYS[0]
        }
        return DELAYS[minOf(attempts, DELAYS.size) - 1]
    }

    /** Whether a send that has already failed `attempts` times may make one more request. */
    @JvmStatic
    fun retryable(attempts: Int): Boolean = attempts < ATTEMPTS
}
