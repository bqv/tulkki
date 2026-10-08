package uk.xa0.tulkki.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The chrome's screenshot cells: the bar the "0 layouts" programme hangs every converted screen
 * from, in both themes, so its look is compared against a reference a human reviews rather than
 * against a description (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2 - the same harness the
 * theme, the welcome mark and the conversation screen use).
 *
 * <p>**What the two cells pin.** A title, the leading back affordance and one overflow item are on
 * screen at once, which is the whole surface [TulkkiChrome] has: the title in
 * `MaterialTheme.typography.titleLarge`, the arrow as `R.drawable.ic_arrow_back_24dp` (the theme's
 * own `homeAsUpIndicator`), the overflow as `R.drawable.ic_more_horiz_24dp`, and all three colours
 * read from the scheme - so a theme attribute that moved, or a size or an icon that changed, moves a
 * pixel here.
 *
 * <p>**The open menu is not in a cell.** `DropdownMenu` draws in its own popup window, which the
 * layoutlib renderer does not compose into the bitmap; what is pinned is the overflow button that
 * opens it. The item itself is [ChromeMenuItem] and its click is plain Kotlin, so a picture of it
 * would pin the popup's own theme, not this file's.
 */
@PreviewTest
@Preview(name = "chrome-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 240)
@Composable
fun ChromeDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(name = "chrome-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 240)
@Composable
fun ChromeLightScreenshot() = Fixture(darkTheme = false)

/**
 * The subject draws no colour of its own: every pixel comes through `TulkkiTheme`, which is the
 * assertion. The one line under the bar is there so the `Scaffold`'s own content area is part of the
 * picture rather than an empty strip.
 */
@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = "Top up",
            onUp = {},
            menu = listOf(ChromeMenuItem("Start chat") {}),
        ) {
            Text(text = "Screen content", modifier = Modifier.padding(16.dp))
        }
    }
}
