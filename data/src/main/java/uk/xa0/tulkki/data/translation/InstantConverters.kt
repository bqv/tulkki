package uk.xa0.tulkki.data.translation

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room's bridge for the one `java.time` type a read model carries (schema 78).
 *
 * <p>[QueueSummary.nextAttemptAt] is an `Instant?` because that is how the design names it and
 * because an instant is what a screen draws; the column is an `INTEGER` of epoch milliseconds like
 * every other time in this file. One converter pair, registered on `HistoryDatabase`, is the whole
 * of the difference - rather than a `Long?` field whose meaning every reader has to remember, or a
 * second field beside it.
 *
 * <p>`null` is preserved in both directions and is not a missing value: `MIN(next_attempt_at)` over
 * no pending row is SQL `NULL`, and "nothing is due" is exactly what that says.
 */
internal object InstantConverters {

    @TypeConverter
    @JvmStatic
    fun toInstant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)

    @TypeConverter
    @JvmStatic
    fun toMillis(instant: Instant?): Long? = instant?.toEpochMilli()
}
