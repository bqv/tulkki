package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The participants screen's screenshot cells - the whole screen [MucUsersActivity] composes, in both
 * themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the two cells pin.** The chrome with the manifest's title and its up arrow, the search
 * field open on its hint, and three rows that between them carry every shape the old
 * `item_contact.xml` drew: the display name with a differing nick under it, the affiliation line of
 * a user with no contact, and the pills of a user whose pseudo hats hide that line - plus the
 * presence dot at the avatar's foot and the monospaced PGP key of advanced mode. The avatars are the
 * plate a row without an `AvatarView` covers, because the avatar service needs a live account; the
 * screen's own drawing is what a substitution would break.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold`
 * inside [TulkkiChrome] paints it - so composing the body alone would render a transparent picture.
 * The titles are the same resources the Activity reads.
 */
@PreviewTest
@Preview(
    name = "muc-users-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MucUsersDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "muc-users-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun MucUsersLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.group_chat_members), onUp = {}) {
            MucUsersScreen(
                rows = fixtureRows,
                searchOpen = true,
                query = "",
                onQueryChange = {},
                onSearchClose = {},
                onOpen = {},
                onMenu = {},
                userMenu = null,
                onMenuDismiss = {},
                onMenuSelected = { _ -> },
                onKey = {},
            )
        }
    }
}

private val fixtureRows =
    listOf(
        MucUserRow(
            key = "juliet@capulet.example",
            displayName = "Juliet Capulet",
            secondary = "Juliet",
            hats = emptyList(),
            pgpKey = null,
            presenceColor = 0xFF259B24.toInt(),
            avatarable = null,
        ),
        MucUserRow(
            key = "romeo@montague.example",
            displayName = "Romeo Montague",
            secondary = "participant",
            hats = emptyList(),
            pgpKey = null,
            presenceColor = 0xFFFF9800.toInt(),
            avatarable = null,
        ),
        MucUserRow(
            key = "friar@verona.example",
            displayName = "Friar Laurence",
            secondary = "",
            hats =
                listOf(
                    MucUserHat(label = "moderator", color = 0xFF259B24.toInt()),
                    MucUserHat(label = "admin", color = 0xFF1976D2.toInt()),
                ),
            pgpKey = "8F2A1B3C4D5E6F70",
            presenceColor = null,
            avatarable = null,
        ),
    )
