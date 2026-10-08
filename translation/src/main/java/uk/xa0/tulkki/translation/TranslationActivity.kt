package uk.xa0.tulkki.translation

/**
 * What Tulkki has done today, and the last thing that stopped it.
 *
 * <p>"Nothing works" is not diagnosable from the outside, so the app has to be able to say what it
 * is doing: how many messages it translated today, how many are still waiting, and the last failure
 * with its reason. Two of those three are already known elsewhere - the queue counts what is waiting
 * ([TranslationQueue.pendingCount]) and the day's tokens are already counted for the cap
 * ([DailyTokenCounter]) - so this keeps only the two that nothing else keeps: today's
 * translated-message count and the last failure.
 *
 * <p>The reason is [HeldSend.HoldReason], not a second vocabulary invented here: the send path
 * already classifies every failure as retryable or not and knows which of "no key, no credit, cap
 * reached, unreachable, rejected key" it was, and the usage screen and a tapped message must say the
 * same words for the same state.
 *
 * <p>`detail` is DeepSeek's or the network's own message, kept verbatim because a paraphrase
 * would lose the one thing that makes a failure diagnosable.
 *
 * <p>Pure Kotlin against a small store, so the arithmetic and the day rollover are JVM-tested.
 *
 * <p><strong>The class lives in `:translation` since 3.7 pair 12, and `:ui` was measured and
 * rejected.</strong> It is pure Java - no `View`, no `Context`, no `R` - and it names nothing of
 * `:ui`, so `:translation` costs it nothing. The other home would have cost a lot:
 * `TranslationSettings`, in this module, constructs the record in its own constructor and hands it
 * back from `activity()`, so a `:ui` home would be a `:translation` -&gt; `:ui` site (the direction
 * pair 10 closed) and would need a factory seam for the construction.
 *
 * <p>Pair 8b moved the class down to `uk.xa0.tulkki.data` to kill the `:data` -&gt; `:ui` direction
 * (D9); that move left the port's import as a `:data` -&gt; `:translation` site, which is C1/D1's
 * direction, and pair 12 is what closes it. The two consumers that kept the record in `:data` were
 * `TranslationSettings` and `TranslationStore`, and both moved up with it.
 *
 * <p><strong>The interop surface is wider here than in the other three, and every piece of it is
 * deliberate.</strong> `Store` stays nested and public, because four Java types implement it
 * (`TranslationSettings`, `TranslationStore`, and this module's and `:app`'s test doubles).
 * [Failure] keeps four `@JvmField`s because Java reads them as fields - `TranslationSettings`'s
 * writer, the test's assertions - while the four `RecordedFailure` accessors stay methods, so the
 * same object is a value to Java and a port to the engine. `reason` is declared nullable to match
 * what Java already allowed: nothing here ever wrote a `null` reason, but a Java caller could, and
 * [TranslationFailures] reads through `reason()` on objects it did not build.
 */
class TranslationActivity(private val store: Store) : TranslationActivityPort {

    /**
     * The outgoing attempts kept in memory, newest last. Tulkki: a held send stores one word - its
     * failure cause - so this is the diagnosis behind that word: which language was asked for, how
     * long the answer was, what each reader said and with what confidence, what the check concluded,
     * and why the message was held. A re-ask is a second entry linked to the attempt it re-asks.
     *
     * <p>It is a bounded ring ({@link OutgoingAttempts.CAPACITY} entries) and never a body of text,
     * so it is safe to log and cheap to keep; nothing here is persisted, so a restart starts empty.
     * Reached through {@link TranslationActivityPort#recentOutgoingAttempts}, which the app already
     * holds.
     */
    private val attempts = OutgoingAttempts()

    /** Where the count and the last failure live between runs. */
    interface Store {
        /** The day the stored count belongs to, or `null`. */
        fun day(): String?

        fun translated(): Int

        fun saveTranslated(day: String?, translated: Int)

        /** The last failure, or `null` when none was ever recorded. */
        fun failure(): Failure?

        fun saveFailure(failure: Failure)

        /**
         * Tulkki: forgets the stored failure. It is the interpreter's own record - the reason the
         * last call did not produce a translation - and with the interpreter off there is no such
         * call, so the record is cleared with the rest of its carried state rather than shown
         * against a client that no longer translates.
         */
        fun clearFailure()
    }

    /** One failed attempt, as a value. */
    class Failure(
            @JvmField val reason: HeldSend.HoldReason?,

            /** What DeepSeek or the network said, or `null` when there was nothing to quote. */
            @JvmField val detail: String?,

            /** The message it happened to, or `null` when no single message was involved. */
            @JvmField val messageUuid: String?,

            @JvmField val at: Long
    ) : TranslationActivityPort.RecordedFailure {

        // The port's side: fields, read one at a time, so the engine reads a value it declares.

        override fun reason(): HeldSend.HoldReason? = reason

        override fun detail(): String? = detail

        override fun messageUuid(): String? = messageUuid

        override fun at(): Long = at
    }

    /**
     * Messages translated on `day`. A stale day reads as zero, exactly like the token counter: a
     * count that has to be reset by something else is a count that can fail to be reset.
     */
    @Synchronized
    fun translatedToday(day: String?): Int {
        if (day == null || day != store.day()) {
            return 0
        }
        return store.translated()
    }

    /** Records `messages` more translated messages on `day`. */
    @Synchronized
    override fun addTranslated(day: String?, messages: Int) {
        if (day == null || messages <= 0) {
            return
        }
        store.saveTranslated(day, translatedToday(day) + messages)
    }

    /**
     * Keeps one outgoing attempt for the diagnostic record. Called by the send path as each attempt
     * finishes; there is no second path and nothing else writes here.
     */
    override fun recordOutgoingAttempt(attempt: OutgoingAttempts.Attempt) {
        attempts.record(attempt)
    }

    /** The attempts still kept, oldest first - the value a diagnostic surface renders. */
    override fun recentOutgoingAttempts(): List<OutgoingAttempts.Attempt> = attempts.recent()

    /**
     * Remembers the last failure. Every attempt that did not produce a translation comes through
     * here - retryable or not - because "DeepSeek is unreachable and we will try again" is as much
     * of an answer as a final failure is.
     */
    @Synchronized
    override fun recordFailure(
            reason: HeldSend.HoldReason?,
            detail: String?,
            messageUuid: String?,
            at: Long
    ) {
        if (reason == null) {
            return
        }
        store.saveFailure(Failure(reason, detail, messageUuid, at))
    }

    /** The last failure, or `null` when nothing has failed yet. */
    @Synchronized
    fun lastFailure(): Failure? = store.failure()

    /**
     * Forgets the last failure. Tulkki: this record is the interpreter's, so observing it off takes
     * the record with it - there is no call left whose failure it could describe, and a cover that
     * said "DeepSeek is unreachable" over a plain client's message would name a service the app is
     * not using. The day's translated-message count is deliberately not touched: that is an account
     * of what was done, not work the interpreter was still carrying.
     */
    @Synchronized
    fun clearFailure() {
        store.clearFailure()
    }

    /**
     * The reason to put on one message's cover, or `null` when the last failure was some other
     * message's. A failure belongs to the message it happened to.
     */
    @Synchronized
    fun reasonFor(messageUuid: String?): HeldSend.HoldReason? {
        val failure = store.failure()
        if (failure == null || messageUuid == null || messageUuid != failure.messageUuid) {
            return null
        }
        return failure.reason
    }
}
