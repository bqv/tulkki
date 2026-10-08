package uk.xa0.tulkki.ui.welcome

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The pick-server screen's screenshot cells, in both themes: the mark, the two lines of explanation
 * and the two buttons `activity_pick_server.xml` carried.
 */
@PreviewTest
@Preview(name = "pick-server-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun PickServerDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(name = "pick-server-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun PickServerLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        PickServerScreen(onUseDefault = {}, onUseOwnProvider = {})
    }
}
