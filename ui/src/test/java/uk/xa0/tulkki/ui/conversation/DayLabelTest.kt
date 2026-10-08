package uk.xa0.tulkki.ui.conversation

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.PreviewWords

/**
 * §4.2's day words, the tree's `DateSeperatorMessage` rule: today, yesterday, and the date itself.
 *
 * <p>The cell that matters is the zone's: the comparison is between the reader's own local dates, so
 * the same instant is "Today" in Helsinki and "Yesterday" in UTC. A rule that compared the instants
 * would put a message sent at 22:30 on the 3rd under the 3rd's header, which is not the day the
 * reader lived it on.
 */
class DayLabelTest {

    private val words =
        PreviewWords { id, _ ->
            when (id) {
                R.string.today -> "Today"
                R.string.yesterday -> "Yesterday"
                else -> "?"
            }
        }

    private val helsinki = ZoneId.of("Europe/Helsinki")
    private val utc = ZoneId.of("UTC")

    /** 2026-10-04T12:00:00Z. */
    private val now = 1791115200000L

    @Test
    fun theSameLocalDayIsToday() {
        Assert.assertEquals(
            "Today",
            DayLabel.of(now - 7_200_000, now, words, Locale.UK, helsinki),
        )
    }

    @Test
    fun theDayBeforeIsYesterday() {
        Assert.assertEquals(
            "Yesterday",
            DayLabel.of(now - 26 * 3_600_000, now, words, Locale.UK, helsinki),
        )
    }

    @Test
    fun anOlderDayNamesItsOwnDate() {
        // The tree's `FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR`: the date, and enough of it to place itself.
        Assert.assertEquals(
            "20 September 2026",
            DayLabel.of(1789905600000L, now, words, Locale.UK, helsinki),
        )
    }

    @Test
    fun theDayIsTheReadersOwnAndNotTheInstants() {
        // 2026-10-03T22:30:00Z is 01:30 on the 4th in Helsinki: the reader's today, and UTC's yesterday.
        val at = 1791066600000L
        Assert.assertEquals("Today", DayLabel.of(at, now, words, Locale.UK, helsinki))
        Assert.assertEquals("Yesterday", DayLabel.of(at, now, words, Locale.UK, utc))
    }
}
