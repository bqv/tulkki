package uk.xa0.tulkki.translation

/**
 * What the engine may write to the app's own account of itself: how many messages were translated
 * today, and the last thing that stopped one.
 *
 * <p>The record is [TranslationActivity], in this module since 3.7 pair 12. Before that it was
 * `uk.xa0.tulkki.ui.TranslationActivity`, and `TranslationSettings.activity()` handed it back -
 * a compile-time `:translation` -> `:ui` edge that no import line in this module would
 * have shown - so the two halves the engine actually touches are declared here:
 *
 * <ul>
 *   <li>the two <strong>actions</strong> are this interface, implemented by that class;
 *   <li>the one <strong>value</strong> the engine reads - a recorded failure - is
 *       [RecordedFailure], implemented by that class's nested `Failure`. Only the fields
 *       cross the boundary, which is why they are accessors here and fields there.
 * </ul>
 *
 * <p>`TranslationService` is handed one directly; `OutgoingTranslation` takes it from
 * [EngineHost.activity].
 *
 * <p><strong>The recorded redundancy is superseded.</strong> Pair 8b moved the record down into
 * `:data`, where `:translation -> :data` is allowed and this interface was therefore
 * optional; the note it left said the interface was kept only because the moved class already
 * implemented it, and that one later commit would delete it. 3.7 pair 12 moved the record back home
 * instead - `uk.xa0.tulkki.translation.TranslationActivity` - so the interface is not a boundary
 * device in either direction any more and there is no pending deletion: it stays because it is the
 * engine's own view of the record (`TranslationService` and `TranslationFailures` name
 * only the members they use, and the JVM doubles implement it), which is a design choice this
 * commit keeps rather than one the map forces.
 *
 * <p><strong>Every nullability here is read off the implementations, not off the Java's
 * declaration.</strong> [TranslationActivity] and `TranslationDoubles.MemoryActivityPort` - one
 * Kotlin, one Java - both accept `null` for the day, the failure reason, the detail and the message
 * uuid, and both return a nullable `reason()`, so the interface declares exactly that. A Java
 * implementor reads no annotations, so the widening costs the doubles nothing; a Kotlin one could
 * not override a narrower declaration at all.
 */
interface TranslationActivityPort {

    /** Records `messages` more translated messages on `day`. */
    fun addTranslated(day: String?, messages: Int)

    /**
     * Keeps one outgoing attempt's facts in memory for a diagnostic surface - the target, the
     * answer's length, each reader's own code and confidence, the check's outcome and the hold
     * reason (see [OutgoingAttempts]).
     *
     * <p>Defaulted, like [recentOutgoingAttempts], so the test doubles that implement this
     * interface for the send path do not have to grow a method nothing in them reads. The real
     * record, [TranslationActivity], keeps the ring; the engine records through this seam so
     * the ring is reachable from `:app`'s existing `activity()` handle without a second
     * wiring.
     */
    fun recordOutgoingAttempt(attempt: OutgoingAttempts.Attempt) {}

    /**
     * The outgoing attempts the record is still carrying, oldest first - the value a diagnostic
     * surface renders. Nothing here is persisted: a restart starts empty.
     */
    fun recentOutgoingAttempts(): List<OutgoingAttempts.Attempt> = emptyList()

    /**
     * Remembers the last failure. Every attempt that did not produce a translation comes through
     * here - retryable or not - because "DeepSeek is unreachable and we will try again" is as much
     * of an answer as a final failure is.
     */
    fun recordFailure(reason: HeldSend.HoldReason?, detail: String?, messageUuid: String?, at: Long)

    /** One recorded failure, as the engine reads it. */
    interface RecordedFailure {

        /** Why the attempt failed. */
        fun reason(): HeldSend.HoldReason?

        /** What DeepSeek or the network said, or `null` when there was nothing to quote. */
        fun detail(): String?

        /** The message it happened to, or `null` when no single message was involved. */
        fun messageUuid(): String?

        /** When it happened. */
        fun at(): Long
    }
}
