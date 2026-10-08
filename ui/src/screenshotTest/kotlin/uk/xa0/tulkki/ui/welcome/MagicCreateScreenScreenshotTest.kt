package uk.xa0.tulkki.ui.welcome

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The magic-create form's screenshot cells, in both themes.
 *
 * <p>It is the screen `activity_magic_create.xml` was: the mark, the invitation text, the username
 * field, the provider choice, the full-address preview and the `Next` button. The fixture fills the
 * username in so the preview - the one line that proves the domain and the local part were joined -
 * is part of the picture rather than an invisible `INVISIBLE` view.
 */
@PreviewTest
@Preview(name = "magic-create-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun MagicCreateDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(name = "magic-create-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun MagicCreateLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        MagicCreateScreen(
            state =
                MagicCreateState(
                    instructions = stringResource(R.string.magic_create_text),
                    username = "alice",
                    providers = listOf("example.org", "jabber.example", "chat.example"),
                    selectedProvider = "example.org",
                    fullJid = stringResource(R.string.your_full_jid_will_be, "alice@example.org"),
                    fullJidVisible = true,
                ),
            onUsernameChange = {},
            onOwnServerChange = {},
            onProviderSelected = {},
            onUseOwnChange = {},
            onCreate = {},
        )
    }
}
