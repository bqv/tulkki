package uk.xa0.tulkki.ui.projection

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import uk.xa0.tulkki.ui.R

/**
 * The row's own clock - `ConversationAdapter`'s last line of arithmetic, and the one part of it that never
 * needed the entity it was reading.
 *
 * <p>`UIHelper.readableTimeDifference` was the tree's ladder and is this function: nothing at all is "just
 * now", a minute is "a minute ago", the next quarter of an hour is "N minutes ago", today is the time of
 * day, and anything older is the date. The two halves that made it un-cellable are arguments here - the
 * clock (`now`) and the locale and zone the date is written in - and the three relative strings come
 * through the same [PreviewWords] seam the preview line uses, so the whole decision is one pure call with
 * a cell.
 *
 * <p>**Nothing here reads a clock of its own**, for the reason the projection takes `now`: a row's time is
 * a function of its instant and the moment it is drawn, and a function that reads `System.currentTimeMillis`
 * cannot be asked what it says about a fixture.
 */
object ConversationRowTime {

    /**
     * The line for one instant.
     *
     * @param at the row's instant, or 0 for a row that names none - which the tree drew as "just now".
     * @param now the moment the row is being drawn.
     * @param relative whether the owner allows the relative wording at all: the tree's
     *     `always_full_timestamps`, inverted, and a preference of the running app rather than a fact of
     *     the row.
     */
    @JvmStatic
    fun of(
        at: Long,
        now: Long,
        relative: Boolean,
        words: PreviewWords,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (at <= 0L) {
            return words.get(R.string.just_now)
        }
        val difference = (now - at) / 1000
        if (relative && difference < 60) {
            return words.get(R.string.just_now)
        }
        if (relative && difference < 120) {
            return words.get(R.string.minute_ago)
        }
        if (relative && difference < 900) {
            return words.get(R.string.minutes_ago, Math.round(difference / 60.0))
        }
        val moment = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (moment.toLocalDate() == today) {
            moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        } else {
            moment.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        }
    }
}
