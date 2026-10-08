package uk.xa0.tulkki.translation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day's DeepSeek tokens, against the day's cap.
 *
 * <p>The cap is denominated in tokens, so something has to count them. The count is deliberately
 * blunt: it covers every request the app makes, inbound and outbound, because the owner asked for one
 * guard rail and not one per feature.
 *
 * <p>The day is a calendar day in the phone's own zone, not a rolling 24 hours: "the cap resets
 * tomorrow" has to mean what a person thinks it means.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 *
 * <p><strong>The interop surface is the reason this is a class and not an object.</strong> The two
 * counters are `synchronized` in the Java this replaces and stay so through `@Synchronized` - both
 * lock the receiver, so the two instances share one monitor exactly as before - while the four
 * statics the Java callers (`:ui`'s usage screen, `Spend`, the tests) read move to the companion
 * object as `@JvmStatic`, and `UNLIMITED` becomes a `const val` so Java still reads a static field.
 * `Store` is the Java callers' own interface: it stays nested and its `save` still takes a nullable
 * day, because the Java `add` calls it with the day it was handed, `null` included.
 */
class DailyTokenCounter(private val store: Store) {

    /** Where the count lives between runs. */
    interface Store {
        /** The day the stored count belongs to, or `null`. */
        fun day(): String?

        fun tokens(): Int

        fun save(day: String?, tokens: Int)
    }

    /**
     * Tokens already spent on `day`. A stale day reads as zero: a counter that has to be reset by
     * something else is a counter that can fail to be reset.
     */
    @Synchronized
    fun used(day: String?): Int {
        if (day == null || day != store.day()) {
            return 0
        }
        return store.tokens()
    }

    /** Adds a response's tokens and returns the new total for `day`. */
    @Synchronized
    fun add(day: String?, tokens: Int): Int {
        val used = used(day) + maxOf(0, tokens)
        store.save(day, used)
        return used
    }

    /**
     * Which kind of spend a cap question is about.
     *
     * <p>The purpose is a parameter of the question and never a global: the cap's last tenth is a
     * reserve for received translation (docs/MIGRATION.md item 17, three), so the very same `used` and
     * `cap` answer differently for an outgoing message and for an incoming one.
     *
     * <p><strong>Nested in the class, deliberately not in the companion below.</strong> A type
     * declared inside a Kotlin `companion object` is `DailyTokenCounter.Companion.Purpose` on the JVM,
     * so a Java caller writing `DailyTokenCounter.Purpose.SEND` gets `cannot find symbol` - which is
     * exactly how this slice failed to compile the first time. A nested class of the class itself is
     * `DailyTokenCounter.Purpose`, the name every call site writes.
     */
    enum class Purpose {
        /**
         * An outgoing message. The send side stops at the reserve line, because it has its own hold
         * and its own deliberate tap, and nothing is sent untranslated in any case.
         */
        SEND,
        /**
         * A received message. It may spend the reserve, because a spent cap that leaves the owner
         * unable to read what was said to them inverts the feature's purpose.
         */
        RECEIVED
    }

    companion object {

        /** A cap of zero or less means no cap. */
        const val UNLIMITED = 0

        /** The divisor of the cap's reserve for received translation: the last tenth. */
        private const val RESERVE_DIVISOR = 10

        /**
         * The line a *send* stops at: the cap less its reserve for received translation. The reserve
         * is a tenth of the number in force, computed from the cap and never stored, so raising the
         * cap moves the line with it.
         */
        @JvmStatic
        fun sendLine(cap: Int): Int =
                if (cap <= UNLIMITED) UNLIMITED else cap - cap / RESERVE_DIVISOR

        /** True when no further request of `purpose` may be made today. */
        @JvmStatic
        fun exhausted(used: Int, cap: Int, purpose: Purpose): Boolean =
                cap > UNLIMITED &&
                        (if (purpose == Purpose.SEND) used >= sendLine(cap) else used >= cap)

        /** Tokens left today for `purpose`, or [Int.MAX_VALUE] when there is no cap. */
        @JvmStatic
        fun remaining(used: Int, cap: Int, purpose: Purpose): Int =
                if (cap <= UNLIMITED) Int.MAX_VALUE
                else maxOf(0, (if (purpose == Purpose.SEND) sendLine(cap) else cap) - used)

        /**
         * The hard cap's own question, for a progress display rather than a spend decision: "has the
         * day's number itself been reached". It is [Purpose.RECEIVED]'s line by construction, because
         * a received call may spend the reserve; a send's question is
         * `exhausted(used, cap, Purpose.SEND)`, whose line is [sendLine]. The two-argument form stays
         * because the Ledger screen asks it, and that screen states the reserve from [sendLine].
         */
        @JvmStatic
        fun exhausted(used: Int, cap: Int): Boolean = exhausted(used, cap, Purpose.RECEIVED)

        /** The same, as a count: the hard cap's remaining, which is what a progress bar draws. */
        @JvmStatic
        fun remaining(used: Int, cap: Int): Int = remaining(used, cap, Purpose.RECEIVED)

        /** The calendar day in `zone`, as an ISO date string. */
        @JvmStatic
        fun dayOf(nowMillis: Long, zone: ZoneId): String =
                LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toString()

        /**
         * When the current cap gives way to the next one: the first moment of the next local day.
         * Used to schedule the retry of a queue that stopped at the cap, so nothing waits for a
         * polling loop.
         */
        @JvmStatic
        fun startOfNextDay(nowMillis: Long, zone: ZoneId): Long =
                LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
    }
}
