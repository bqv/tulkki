package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The change-password screen's screenshot cells - the screen [ChangePasswordActivity] composes, in
 * both themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the three cells pin.** The whole visible screen, which the deleted
 * `activity_change_password.xml` drew: the bar with this screen's own title and its up arrow, the two
 * filled `TextField`s under the `activity_*_margin` and `card_padding_regular` margins with the
 * trailing password toggle the layout's `endIconMode="password_toggle"` carried, and the button bar
 * at the bottom with its `TextButton` cancel on the start and the change button on the end. The third
 * cell is the state the screen exists for and the one a quiet regression would hide: the new field in
 * error, its `supportingText` where the layout's `TextInputLayout.setError` used to land.
 *
 * <p>**Why the chrome is in them.** These three screens draw no background of their own: the
 * `Scaffold` inside [TulkkiChrome] paints it, so composing the form alone would render a transparent
 * picture. The cell therefore composes the screen where production composes it, and the title is read
 * from the same resource the Activity reads.
 */
@PreviewTest
@Preview(
    name = "change-password-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChangePasswordDarkScreenshot() = Fixture(darkTheme = true, state = ChangePasswordScreenState())

@PreviewTest
@Preview(
    name = "change-password-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChangePasswordLightScreenshot() = Fixture(darkTheme = false, state = ChangePasswordScreenState())

@PreviewTest
@Preview(
    name = "change-password-error",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChangePasswordErrorScreenshot() =
    Fixture(
        darkTheme = true,
        state = ChangePasswordScreenState(newError = "Password can’t be empty"),
    )

@Composable
private fun Fixture(darkTheme: Boolean, state: ChangePasswordScreenState) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.change_password_on_server), onUp = {}) {
            ChangePasswordScreen(
                state = state,
                onCurrentPasswordChange = {},
                onNewPasswordChange = {},
                onCancel = {},
                onChangePassword = {},
            )
        }
    }
}
