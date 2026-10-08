package uk.xa0.tulkki.ui.calls

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.stories.NavBadges
import uk.xa0.tulkki.ui.stories.NavIcons
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The calls log's screenshot cells - the screen `CallsActivity` composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the other
 * converted screens use).
 *
 * <p>**What the two cells pin.** The whole visible screen, because that is what `CallsActivity`
 * hands the chrome: the bar with `R.string.calls` and the settings overflow item, three rows - an
 * answered incoming call with its account line, a missed received call (the `red_700` line), and a
 * made call - each at the deleted `item_call.xml`'s 12 dp padding with its avatar plate, bold name,
 * status icon, preview, dot and date, and the four-tab bar with `calls` selected and the chat
 * count's badge plus the stories/calls/feeds dots. A cell that loses the 12 dp row padding, the
 * 18 dp status icon, the 2 dp dot or the bar's own height moves a pixel.
 *
 * <p>**Why the chrome is in them.** This screen draws no background of its own: the `Scaffold`
 * inside [TulkkiChrome] paints it, so composing the body alone would render a transparent picture.
 * The titles are read from the same resources the Activity reads.
 *
 * <p>**Why every row's `contact` and `message` are `null`.** A cell cannot build a live contact or a
 * persisted message, and neither is needed to draw the row: the host resolves every fact into the
 * row, and the deleted `AvatarView`'s replacement draws the XML's own `ic_person_24dp` plate when
 * there is no contact - which also keeps an `AndroidView` out of the cell. The "call again" dropdown
 * is not open in either cell: it is a second popup window the harness does not compose.
 */
@PreviewTest
@Preview(
    name = "calls-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CallsDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "calls-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CallsLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.calls),
            onUp = null,
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.action_settings)) {},
                ),
        ) {
            CallsScreen(
                state = callsState(),
                icons = iconsFor(darkTheme),
                onTab = {},
                onContact = {},
                onCallAgain = { _, _ -> },
            )
        }
    }
}

/** The rows the deleted `CallsAdapter.CallViewHolder.bind` would have filled, with literal answers. */
private fun callsState(): CallsScreenState =
    CallsScreenState(
        rows =
            listOf(
                CallRow(
                    message = null,
                    contact = null,
                    name = "Mika",
                    info = "Incoming call (2 min)",
                    date = "2 hours ago",
                    missed = false,
                    icon = R.drawable.ic_call_received_24dp,
                    account = "owner@example.org",
                    contactClickable = false,
                ),
                CallRow(
                    message = null,
                    contact = null,
                    name = "Aada",
                    info = "Missed call",
                    date = "yesterday",
                    missed = true,
                    icon = R.drawable.ic_call_missed_24db,
                    account = null,
                    contactClickable = false,
                ),
                CallRow(
                    message = null,
                    contact = null,
                    name = "Otso",
                    info = "Outgoing call (5 min)",
                    date = "3 days ago",
                    missed = false,
                    icon = R.drawable.ic_call_made_24dp,
                    account = null,
                    contactClickable = false,
                ),
            ),
        badges = NavBadges(chats = 3, stories = false, feeds = true, calls = true),
        showNavBar = true,
    )

/**
 * The eight drawables `values/themes.xml` and `values-night/themes.xml` map the tab attributes to.
 *
 * <p>The cell passes what the host's `theme.resolveAttribute` would answer, because a screenshot cell
 * has no `Theme.Tulkki` under it - the production screen is handed the resolved ids for exactly that
 * reason ([NavIcons]).
 */
private fun iconsFor(darkTheme: Boolean): NavIcons =
    if (darkTheme) {
        NavIcons(
            chatsUnselected = R.drawable.outline_chat_white_24,
            chatsSelected = R.drawable.chat_selected_white_24,
            callsUnselected = R.drawable.calls_unselected_white_24dp,
            callsSelected = R.drawable.calls_selected_white_24dp,
            storiesUnselected = R.drawable.stories_unselected_white_24,
            storiesSelected = R.drawable.stories_selected_white_24,
            feedsUnselected = R.drawable.feed_unselected_white_24dp,
            feedsSelected = R.drawable.feed_selected_white_24dp,
        )
    } else {
        NavIcons(
            chatsUnselected = R.drawable.outline_chat_black_24,
            chatsSelected = R.drawable.chat_selected_black_24,
            callsUnselected = R.drawable.calls_unselected_black_24dp,
            callsSelected = R.drawable.calls_selected_black_24dp,
            storiesUnselected = R.drawable.stories_unselected_black_24,
            storiesSelected = R.drawable.stories_selected_black_24,
            feedsUnselected = R.drawable.feed_unselected_black_24dp,
            feedsSelected = R.drawable.feed_selected_black_24dp,
        )
    }
