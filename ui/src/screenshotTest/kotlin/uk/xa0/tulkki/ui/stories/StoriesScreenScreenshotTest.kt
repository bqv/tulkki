package uk.xa0.tulkki.ui.stories

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.conversationlist.AvatarShape
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The stories list's screenshot cells - the screen `StoriesActivity` composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the welcome
 * screens use).
 *
 * <p>**What the two cells pin.** The whole visible screen, because that is what `StoriesActivity`
 * hands the chrome: the bar with `R.string.stories` and the settings overflow item, two rows at their
 * literal titles and times with the avatar and preview plates (the host resolves both, so a cell can
 * only pin the plate), the add button, and the four-tab bar with the chat count's badge and the
 * stories/calls dots. A cell that loses the 12 dp row padding, the 56 dp card, the tab labels or the
 * bar's own height moves a pixel.
 *
 * <p>**Why the chrome is in them.** This screen draws no background of its own: the `Scaffold`
 * inside [TulkkiChrome] paints it, so composing the body alone would render a transparent picture.
 * The titles are read from the same resources the Activity reads.
 *
 * <p>**The add-story dialog is in neither cell.** It is a Compose `Dialog`, which is a second window
 * the harness does not compose - no cell in this module pins one - and its own two view slots are the
 * image library's and the platform's besides.
 */
@PreviewTest
@Preview(
    name = "stories-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StoriesDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "stories-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StoriesLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val state =
        StoriesScreenState(
            rows =
                listOf(
                    StoryRow(
                        jid = "mika@example.org",
                        accountUuid = "account-1",
                        avatar = null,
                        title = "Mika",
                        time = "2 hours ago",
                        preview = null,
                    ),
                    StoryRow(
                        jid = "aada@example.org",
                        accountUuid = "account-1",
                        avatar = null,
                        title = "Aada",
                        time = "yesterday",
                        preview = null,
                    ),
                ),
            hasOnlineAccounts = true,
            badges = NavBadges(chats = 3, stories = true, feeds = false, calls = true),
            showNavBar = true,
        )
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.stories),
            onUp = null,
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.action_settings)) {},
                ),
        ) {
            StoriesScreen(
                state = state,
                avatarShape = AvatarShape.OVAL,
                icons = iconsFor(darkTheme),
                onTab = {},
                onStory = {},
                onAddStory = {},
            )
        }
    }
}

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
