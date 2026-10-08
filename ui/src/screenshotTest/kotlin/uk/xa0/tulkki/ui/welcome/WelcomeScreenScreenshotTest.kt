package uk.xa0.tulkki.ui.welcome

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The welcome screen's screenshot cells.
 *
 * <p>**These are the screen now, not a fragment of it.** Until the onboarding trio became Compose,
 * this file pinned only the mark: `activity_welcome.xml` was a data-binding layout the harness could
 * not render (see the audit, "The welcome golden is not the welcome screen"). With the layout gone,
 * the intro slide and the sign-in slide are ordinary composables and are drawn here in both themes -
 * the 128 dp mark, the gateway and third-party row, the 130 dp bottom bar with its dots and Next,
 * and the five ways in.
 *
 * <p>The two mark cells stay: they are the cells that pin `R.drawable.tulkki_logo` itself, which the
 * screen draws at two sizes, and they still fail if the regenerated drawable is replaced by
 * something else.
 */
@PreviewTest
@Preview(name = "welcome-intro-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun WelcomeIntroDarkScreenshot() = WelcomeFixture(darkTheme = true, initialPage = 0)

@PreviewTest
@Preview(name = "welcome-intro-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun WelcomeIntroLightScreenshot() = WelcomeFixture(darkTheme = false, initialPage = 0)

/** The sign-in slide: the last page, and the only one that carries the create/log-in actions. */
@PreviewTest
@Preview(name = "welcome-sign-in-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun WelcomeSignInDarkScreenshot() = WelcomeFixture(darkTheme = true, initialPage = 3)

@PreviewTest
@Preview(name = "welcome-sign-in-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun WelcomeSignInLightScreenshot() = WelcomeFixture(darkTheme = false, initialPage = 3)

@PreviewTest
@Preview(name = "welcome-mark-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 300)
@Composable
fun WelcomeMarkDarkScreenshot() = WelcomeHeader(darkTheme = true)

@PreviewTest
@Preview(name = "welcome-mark-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 300)
@Composable
fun WelcomeMarkLightScreenshot() = WelcomeHeader(darkTheme = false)

/** A welcome screen with untouched preferences; the callbacks are all no-ops in a reference. */
@Composable
private fun WelcomeFixture(darkTheme: Boolean, initialPage: Int) {
    TulkkiTheme(darkTheme = darkTheme) {
        WelcomeScreen(
            settings = WelcomeSettings(),
            working = false,
            databaseEncryptionConfigured = false,
            onSettingsChange = {},
            onInfo = {},
            onSetupDatabaseEncryption = {},
            onRegister = {},
            onLogIn = {},
            onBackup = {},
            onSnikket = {},
            onCertificate = {},
            onSettingsSettled = {},
            initialPage = initialPage,
        )
    }
}

/**
 * The mark on its own, at the welcome header's size and place.
 *
 * <p>The onboarding screens are composables now, so this is not the only note the screen gets - but
 * the artwork is the one thing the screen draws from `identity/icon`, and a substitute drawing
 * shows up here as red in both themes.
 */
@Composable
private fun WelcomeHeader(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.tulkki_logo),
                contentDescription = null,
                modifier = Modifier.padding(15.dp).size(128.dp),
            )
        }
    }
}
