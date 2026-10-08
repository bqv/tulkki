package uk.xa0.tulkki.ui.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.PreviewWords

/**
 * The words a day's header carries, §4.2's "drawn divider + label between days".
 *
 * <p>`MessageAdapter.render(DateSeperatorMessageItemViewHolder)` was the tree's rule and this is it,
 * with the two halves that made it un-cellable as arguments: a row's own day is "Today" or
 * "Yesterday" while it is fresh, and the date it happened on once it is not. Everything else - the
 * clock, the locale and the zone - arrives as a parameter for `ConversationRowTime`'s reason: a rule
 * that reads a clock of its own cannot be asked what it says about a fixture, and the screenshot
 * references would drift with the calendar.
 *
 * <p>The comparison is between **local dates**, not instants: two messages an hour apart are two days
 * apart across a midnight, and a message at 23:30 UTC is already tomorrow in Helsinki - which is the
 * reader's own day and therefore the one the header must name.
 */
object DayLabel {

    /**
     * The header for the day `at` falls on.
     *
     * @param at any instant of that day, in `zone`
     * @param now the moment the header is drawn, whose own local date is "today"
     * @param words the three strings' own seam, as `ConversationRowTime` takes it
     */
    @JvmStatic
    fun of(
        at: Long,
        now: Long,
        words: PreviewWords,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> words.get(R.string.today)
            today.minusDays(1) -> words.get(R.string.yesterday)
            else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
        }
    }
}
