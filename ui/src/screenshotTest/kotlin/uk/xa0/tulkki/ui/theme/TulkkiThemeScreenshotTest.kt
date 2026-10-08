package uk.xa0.tulkki.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.android.tools.screenshot.PreviewTest

/**
 * The screenshot harness's first cell, docs/MIGRATION.md "Design: the Compose UI" §7.1.
 *
 * <p>§7 decided "**JVM screenshot tests for the Compose UI from its first screen**", and §7.1 names the
 * harness: "Compose Preview Screenshot Testing, the AGP-native tool. It renders `@Preview` composables
 * with layoutlib on the JVM - no device, no emulator". §7.2 pins what a test asserts: "a state, not a
 * flow", in both themes, because "light is the map nobody looks at".
 *
 * <p>**The subject is the theme, and it is the only real composable in the tree.** `ui-3`'s ledger is
 * the screen this harness exists for; its seven state types are §3.0.1's and land with that row, so
 * the first subject is what `ui-1` did land: the colour scheme. The fixture draws the scheme's own
 * slots and nothing else (no literal colour, §1.8 rule 1), so a wrong `TulkkiColors` value moves a
 * pixel here as well as reddening `TokenParityTest`'s textual pin. The first *screen* screenshot lands
 * with `ui-3`.
 *
 * <p>Names, measured on this tree rather than taken from §7.1: the record and validate tasks are
 * `:ui:updateDebugScreenshotTest` and `:ui:validateDebugScreenshotTest` (§7.1's carry a `Tulkki` the
 * screenshot component does not have), the module needs
 * `experimentalProperties["android.experimental.enableScreenshotTest"] = true` as well as the
 * `gradle.properties` flag, and alpha16 has no `testOptions { screenshotTests { … } }` DSL at all - so
 * the tolerance is the plugin's own default.
 */
@PreviewTest
@Preview(name = "theme-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 320, heightDp = 240)
@Composable
fun TulkkiThemeDarkScreenshot() = ThemeFixture(darkTheme = true)

@PreviewTest
@Preview(name = "theme-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 320, heightDp = 240)
@Composable
fun TulkkiThemeLightScreenshot() = ThemeFixture(darkTheme = false)

/**
 * A theme proof, not a screen: the scheme's own slots, read through `MaterialTheme` and
 * `LocalTulkkiColors`, so no colour is written here. A change to any of them moves a pixel, which is
 * the whole assertion.
 */
@Composable
private fun ThemeFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Tulkki",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(MaterialTheme.colorScheme.primary)
                    Swatch(MaterialTheme.colorScheme.secondaryContainer)
                    Swatch(MaterialTheme.colorScheme.tertiaryContainer)
                    Swatch(MaterialTheme.colorScheme.error)
                    Swatch(MaterialTheme.colorScheme.surfaceVariant)
                }
                Box(
                    Modifier.fillMaxWidth()
                        .height(1.dp)
                        .background(LocalTulkkiColors.current.divider)
                )
                Text(
                    text = "ok",
                    color = LocalTulkkiColors.current.success,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Swatch(colour: Color) {
    Surface(color = colour, modifier = Modifier.width(48.dp).height(32.dp), content = {})
}
