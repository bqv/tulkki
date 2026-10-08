package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import java.time.YearMonth
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The conversation calendar's screenshot cells - the screen [ConversationCalendarActivity] composes,
 * in both themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**The month is in them now, and only now could be.** The calendar is the kizitonwose Compose
 * artifact, so layoutlib draws it: each cell fills the calendar slot with a real
 * [ConversationCalendar] - one pinned month (`2026-10`, so the reference picture does not follow the
 * clock), its header, and the day cells with a message count each - where the view artifact's cells
 * could only pass nothing at that slot. What the cells pin is everything around and inside it: the
 * bar with this screen's own title and its up arrow, the total line over the body, the day numbers,
 * and the `colorPrimaryContainer` fills whose alpha follows the count. The thumbnails are not in
 * them: those come from the file backend, which layoutlib has not.
 *
 * <p>**Why the chrome is in them.** This screen draws no background of its own: the `Scaffold` inside
 * [TulkkiChrome] paints it, so composing the body alone would render a transparent picture. The cell
 * therefore composes the screen where production composes it, and both strings are read from the
 * same resources the Activity reads.
 */
@PreviewTest
@Preview(
    name = "conversation-calendar-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ConversationCalendarDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "conversation-calendar-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ConversationCalendarLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    // Pinned: a `YearMonth.now()` here would move the reference picture with the clock.
    val month = YearMonth.of(2026, 10)
    // The shape `getMessagesCountGroupByDay` returns, summing to the total the line states.
    val counts = mapOf(3 to 5, 8 to 1, 12 to 24, 17 to 9, 24 to 3, 27 to 31)

    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.title_activity_calendar), onUp = {}) {
            ConversationCalendarScreen(
                total = stringResource(R.string.total_messages_per_month, "73"),
                calendar = {
                    ConversationCalendar(
                        startMonth = month,
                        endMonth = month,
                        firstVisibleMonth = month,
                        dataMonth = month,
                        dayCounts = counts,
                        dayFiles = emptyMap(),
                        previews = { null },
                        onVisibleMonthChanged = {},
                        onDayClick = {},
                        // The deleted host's `layout_marginTop="48dp"`, which production passes too.
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                },
            )
        }
    }
}
