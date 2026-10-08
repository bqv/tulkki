package uk.xa0.tulkki.ui.editaccount

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The account editor's screenshot cells - [EditAccountScreen] in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the four cells pin.** The whole visible screen the deleted
 * `activity_edit_account.xml` drew at its two ends: the dark and light cells carry the account card
 * (the JID, the password and the host/port pair), the vCard card with two dynamic rows and the drag
 * handles, the server-info card with its verification strip and the account's own rows, and the
 * cancel/save bar. The third is the state a quiet regression would hide, the JID in error with the
 * save button enabled; the fourth is the overflow's "show more", which is the only way the extra
 * server-info rows are ever on screen.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold` inside
 * [TulkkiChrome] paints it - so composing the form alone would render a transparent picture. The
 * cells compose it where production composes it, and the title is read from the same resource the
 * Activity reads.
 *
 * <p>**What is deliberately absent.** The two `AndroidView` bridges in the screen - the avatar and
 * the OMEMO other-device rows - take a live `Account` and the framework's own inflater; layoutlib
 * draws neither, so the fixtures leave [EditAccountScreenState.showAvatar] and
 * [EditAccountScreenState.otherDevicesVisible] off rather than pin an empty box.
 */
@PreviewTest
@Preview(
    name = "edit-account-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 1200,
)
@Composable
fun EditAccountDarkScreenshot() = Fixture(darkTheme = true, state = accountState())

@PreviewTest
@Preview(
    name = "edit-account-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 1200,
)
@Composable
fun EditAccountLightScreenshot() = Fixture(darkTheme = false, state = accountState())

@PreviewTest
@Preview(
    name = "edit-account-jid-error",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 1200,
)
@Composable
fun EditAccountJidErrorScreenshot() =
    Fixture(
        darkTheme = true,
        state = accountState().copy(jidError = "This is not a valid Jabber ID", saveEnabled = true),
    )

@PreviewTest
@Preview(
    name = "edit-account-server-info-more",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 1600,
)
@Composable
fun EditAccountServerInfoMoreScreenshot() =
    Fixture(darkTheme = true, state = accountState().copy(showMore = true))

@Composable
private fun Fixture(darkTheme: Boolean, state: EditAccountScreenState) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.account_details), onUp = {}) {
            EditAccountScreen(state = state)
        }
    }
}

/**
 * A signed-in account as the screen draws it: the connection fields filled, the vCard edited, the
 * server-info card visible, and the two rows of the vCard list the fixture owns.
 */
private fun accountState(): EditAccountScreenState =
    EditAccountScreenState(
        jid = "owner@example.org",
        password = "hunter2",
        showNamePort = true,
        hostname = "xmpp.example.org",
        port = "5222",
        portEnabled = true,
        jidEnabled = false,
        passwordToggleEnabled = true,
        showVCard = true,
        vcardRows =
            listOf(
                VCardRowState(0L, "fn", "The owner"),
                VCardRowState(1L, "org", "Tulkki"),
            ),
        showStats = true,
        sessionEstablished = "3 hours ago",
        verificationMessage = "DNSSEC and DANE verified",
        verificationIndicator = R.drawable.shield_verified,
        displayName = "The owner",
        accountColorVisible = true,
        accountColor = 0xFF3F51B5.toInt(),
        quietHoursVisible = true,
        quietHoursEnabled = true,
        quietHoursStart = "22:00",
        quietHoursEnd = "08:00",
        pgpVisible = true,
        pgpFingerprint = "A1B2 C3D4 E5F6",
        omemoVisible = true,
        omemoFingerprint = "0123 4567 89AB CDEF",
        osOptimizationVisible = false,
        saveLabel = R.string.save,
        saveEnabled = true,
    )
