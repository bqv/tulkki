package uk.xa0.tulkki.ui.channel

import android.content.res.Configuration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The channel discovery screen's four cells - the results with the search field closed and the same
 * results with it open, each in both themes (docs/MIGRATION.md "Design: the Compose UI"
 * §7.1/§7.2, the harness the chrome and the other converted screens use).
 *
 * <p>**What the two result cells pin.** The deleted `item_channel_discovery.xml`'s row: the 48 dp
 * avatar, the `name [nusers]` line, the two-character language beside it, the two-line description
 * and the room's bare JID - and the two things the XML made `GONE`, a missing description and a
 * language that is not two characters. The action view's replacement is visible as the bar's search
 * icon. The empty-result background is not in them: `background_no_results` is the surface plus a
 * 96 dp mark, and a cell that draws it would say nothing about a row.
 *
 * <p>**What the two search cells pin.** The field that replaces `actionview_search.xml`: the
 * `Search channels` hint, the leading search mark and the clear button, standing where the action
 * view's `EditText` stood.
 *
 * <p>**The avatars are the stand-in, and cannot be more.** A row's avatar is `AvatarWorkerTask`'s
 * `AvatarView`, which needs a live Activity; the cells pass no avatar source and the row draws the
 * group placeholder, the same argument the posts screen's cells make about a live account. The
 * chrome carries the same title and the same two overflow items the Activity hands it.
 */
@PreviewTest
@Preview(
    name = "channel-discovery-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChannelDiscoveryDarkScreenshot() = Fixture(darkTheme = true, searchOpen = false)

@PreviewTest
@Preview(
    name = "channel-discovery-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChannelDiscoveryLightScreenshot() = Fixture(darkTheme = false, searchOpen = false)

@PreviewTest
@Preview(
    name = "channel-search-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChannelSearchDarkScreenshot() = Fixture(darkTheme = true, searchOpen = true)

@PreviewTest
@Preview(
    name = "channel-search-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChannelSearchLightScreenshot() = Fixture(darkTheme = false, searchOpen = true)

/** The screen where production composes it: inside the chrome, which paints the background. */
@Composable
private fun Fixture(darkTheme: Boolean, searchOpen: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.discover_channels),
            onUp = {},
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.action_accounts)) {},
                    ChromeMenuItem(stringResource(R.string.action_settings)) {},
                ),
            actions = {
                if (!searchOpen) {
                    IconButton(onClick = {}) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                }
            },
        ) {
            ChannelDiscoveryScreen(
                rows = sampleRows(),
                loading = false,
                noResults = false,
                searchOpen = searchOpen,
                query = if (searchOpen) "prosody" else "",
                onQueryChange = {},
                onSearchClose = {},
                onSearchSubmit = {},
                onChannelClick = {},
                onShare = {},
                onOpenJoinDialog = {},
            )
        }
    }
}

/**
 * Rows with one line of every kind the deleted row drew, and each `GONE` case once: the first has a
 * description and a two-character language, the second has neither.
 */
private fun sampleRows(): List<ChannelRow> =
    listOf(
        ChannelRow(
            Room(
                "prosody@conference.prosody.im",
                "Prosody IM chatroom",
                "Prosody XMPP server support and related discussions (i.e. otters)",
                "en",
                42,
            ),
            avatarable = null,
        ),
        ChannelRow(
            Room(
                "tulkki@conference.example.org",
                "Tulkki",
                null,
                null,
                7,
            ),
            avatarable = null,
        ),
    )
