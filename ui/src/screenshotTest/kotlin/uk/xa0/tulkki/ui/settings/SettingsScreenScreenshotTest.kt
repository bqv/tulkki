package uk.xa0.tulkki.ui.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The settings screen's screenshot cells - `ui-4` part 2a's gate, on the harness `ui-11` landed
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>§7.2 wants "**both themes for every screen** ... because light is the map nobody looks at", and
 * this screen has a fourth state worth a cell of its own: the prompts sub-screen, which is a route and
 * not a dialog, so it can be rendered as a state like any other. The off state is §3.1's "the screen
 * reduces to a short page".
 *
 * <p>The fixture is a literal [TulkkiSettingsState], the same shape the JVM cells build, so a
 * screenshot is a picture of a state the page really produces rather than of a hand-written one that
 * could drift from it.
 */
@PreviewTest
@Preview(name = "settings-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 1600)
@Composable
fun SettingsDarkScreenshot() = Fixture(darkTheme = true, state = fullState(), route = SettingsRoute.PAGE)

@PreviewTest
@Preview(name = "settings-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 1600)
@Composable
fun SettingsLightScreenshot() = Fixture(darkTheme = false, state = fullState(), route = SettingsRoute.PAGE)

/** §3.1's off state: the two language rows, which are the switch, and nothing else. */
@PreviewTest
@Preview(name = "settings-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 480)
@Composable
fun SettingsOffScreenshot() =
    Fixture(darkTheme = true, state = fullState().copy(interpreter = false), route = SettingsRoute.PAGE)

/** The nested route: the container's three instructions, and the way back to the page. */
@PreviewTest
@Preview(name = "settings-prompts", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 700)
@Composable
fun SettingsPromptsScreenshot() = Fixture(darkTheme = true, state = fullState(), route = SettingsRoute.PROMPTS)

@Composable
private fun Fixture(darkTheme: Boolean, state: TulkkiSettingsState, route: SettingsRoute) {
    TulkkiTheme(darkTheme = darkTheme) {
        SettingsScreen(
            state = state,
            route = route,
            onRoute = {},
            onToggle = { _, _ -> },
            onEdit = {},
            onOpenScreen = {},
        )
    }
}

/** A state with every section populated: the interpreter on, a stored key, and all seven switches read. */
private fun fullState(): TulkkiSettingsState =
    TulkkiSettingsState(
        interpreter = true,
        appLanguage = "fi",
        studyLanguage = "en",
        keyPresent = true,
        keyStorageAvailable = true,
        baseUrl = "https://api.deepseek.com",
        dailyTokenCap = 100_000,
        revealSuggestionFirst = true,
        showSecondHalf = true,
        showConcealedOriginal = true,
        concealOwnSecondHalf = false,
        showBlurredEnglish = true,
        unblurEnglishOnTap = false,
        showEnglishRetranslation = false,
        promptEdited = mapOf(PromptBook.Kind.TRANSLATE to true, PromptBook.Kind.REVIEW to false, PromptBook.Kind.GLOSS to false),
        promptLength = mapOf(PromptBook.Kind.TRANSLATE to 184, PromptBook.Kind.REVIEW to 96, PromptBook.Kind.GLOSS to 41),
    )
