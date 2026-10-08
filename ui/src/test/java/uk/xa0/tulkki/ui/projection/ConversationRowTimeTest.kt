package uk.xa0.tulkki.ui.projection

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R

/**
 * `ui-8`'s cells over the row's own clock - the one piece of `ConversationAdapter` that was arithmetic
 * rather than drawing, and now a pure call.
 *
 * <p>Both halves are pinned: the relative ladder inside a quarter of an hour, which is what the owner's
 * `always_full_timestamps` turns off, and the day-versus-time rule past it. The clock, the locale and the
 * zone are arguments, so every one of these answers is a fixture rather than an environment.
 */
class ConversationRowTimeTest {

    /** The recording seam the projection itself takes, so a cell reads which resource was asked for. */
    private val words =
        PreviewWords { id, args -> if (args.isEmpty()) "s$id" else "s$id:" + args.joinToString("|") }

    private val zone = ZoneId.of("UTC")

    private val locale = Locale.UK

    /** 2023-11-14T22:13:20Z, a Tuesday evening in UTC. */
    private val now = 1_700_000_000_000L

    private fun of(at: Long, relative: Boolean = true): String =
        ConversationRowTime.of(at, now, relative, words, locale, zone)

    @Test
    fun theRelativeLadderIsTheTreesOwn() {
        Assert.assertEquals(
            "a row that names no instant is the tree's just-now, not an empty line",
            "s${R.string.just_now}",
            of(0L),
        )
        Assert.assertEquals("under a minute is just now", "s${R.string.just_now}", of(now - 59_000L))
        Assert.assertEquals("a minute is a minute ago", "s${R.string.minute_ago}", of(now - 60_000L))
        Assert.assertEquals(
            "five minutes is the rounded count",
            "s${R.string.minutes_ago}:5",
            of(now - 300_000L),
        )
        Assert.assertEquals(
            "and the ladder's last rung is just under a quarter of an hour - which the tree *rounds* to "
                + "fifteen, because that is what `Math.round` did with 899 seconds",
            "s${R.string.minutes_ago}:15",
            of(now - 899_000L),
        )
    }

    @Test
    fun todayIsTheTimeOfDayAndAnythingElseIsTheDate() {
        val earlierToday = Instant.ofEpochMilli(now).atZone(zone).withHour(9).withMinute(5).toInstant()
        Assert.assertEquals(
            "an instant earlier today is the time of day",
            "09:05",
            of(earlierToday.toEpochMilli()),
        )
        val yesterday = Instant.ofEpochMilli(now).atZone(zone).minusDays(1).toInstant()
        Assert.assertEquals(
            "and a different day is the date",
            "13/11/2023",
            of(yesterday.toEpochMilli()),
        )
    }

    @Test
    fun fullTimestampsTurnTheLadderOffAndNothingElse() {
        Assert.assertEquals(
            "the owner's always_full_timestamps draws the time of day, however recent the row is",
            "22:13",
            of(now - 5_000L, relative = false),
        )
        Assert.assertEquals(
            "and the date for an older one, exactly as the relative ladder's last rung does",
            "13/11/2023",
            of(Instant.ofEpochMilli(now).atZone(zone).minusDays(1).toInstant().toEpochMilli(), relative = false),
        )
    }
}
