package uk.xa0.tulkki.ui.accounts

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
 * The account list's screenshot cells - the screen the three hosts compose, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the stories
 * screens use).
 *
 * <p>**What the two cells pin.** The whole visible screen, because that is what the hosts hand the
 * chrome: the bar with `R.string.title_activity_manage_accounts` and the add-account overflow, an
 * online row with its shield and status message, a disabled row in the neutral tone, both switches
 * and both drag handles. A cell that loses the 8 dp row padding, the 48 dp avatar or the 16 dp
 * avatar gap moves a pixel.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own: the `Scaffold`
 * inside [TulkkiChrome] paints it, so composing the body alone would render a transparent picture.
 * The avatar is `null` in both cells because the host resolves it; a cell can only pin the plate.
 */
@PreviewTest
@Preview(
    name = "manage-accounts-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ManageAccountsDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "manage-accounts-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ManageAccountsLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val state =
        ManageAccountsState(
            rows =
                listOf(
                    AccountRow(
                        jid = "mika@example.org",
                        avatar = null,
                        statusText = "Connected",
                        tone = AccountStatusTone.PRIMARY,
                        statusMessage = "At work",
                        verificationIcon = R.drawable.shield_verified,
                        enabled = true,
                        color = null,
                    ),
                    AccountRow(
                        jid = "aada@example.org",
                        avatar = null,
                        statusText = "Temporarily disabled",
                        tone = AccountStatusTone.NEUTRAL,
                        statusMessage = null,
                        verificationIcon = null,
                        enabled = false,
                        color = null,
                    ),
                ),
            showPhoneAccounts = false,
            reorderable = true,
        )
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.title_activity_manage_accounts),
            onUp = {},
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.action_add_account)) {},
                ),
        ) {
            ManageAccountsScreen(
                state = state,
                avatarShape = AvatarShape.OVAL,
                onRow = {},
                onToggle = { _, _ -> },
            )
        }
    }
}
