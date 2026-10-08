package uk.xa0.tulkki.ui.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.preferences.PreferenceListView
import uk.xa0.tulkki.ui.preferences.SettingsChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The main settings screen's screenshot cells - the dark and the light picture of the list
 * `SettingsActivity` opens on, on the harness `ui-11` landed (docs/MIGRATION.md, "Design: the Compose
 * UI" §7.1/§7.2).
 *
 * <p>§7.2 wants "**both themes for every screen** ... because light is the map nobody looks at". The
 * fixture is [MainSettingsPage.items] itself, not a list written here, so the picture is of the rows the
 * screen really draws; the three readings it takes are the values a normal build has (the interpreter
 * on, channel discovery visible, a push server present). The bar above the list is
 * [SettingsChrome]'s, which is the same bar the Activity draws.
 */
@PreviewTest
@Preview(name = "settings-main-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 720)
@Composable
fun MainSettingsDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(name = "settings-main-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 720)
@Composable
fun MainSettingsLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val context = LocalContext.current
    TulkkiTheme(darkTheme = darkTheme) {
        SettingsChrome(title = stringResource(R.string.title_activity_settings), onUp = {}) {
            PreferenceListView(
                items =
                    MainSettingsPage.items(
                        context = context,
                        interpreterEnabled = true,
                        channelDiscoveryHidden = false,
                        upVisible = true,
                    ),
                dialog = null,
                onToggle = { _, _ -> },
                onClick = {},
                onCopy = {},
                onValue = { _, _ -> },
                onColour = { _, _ -> },
                onDismiss = {},
            )
        }
    }
}
