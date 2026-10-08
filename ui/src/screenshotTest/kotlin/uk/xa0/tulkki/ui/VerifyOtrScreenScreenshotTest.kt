package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The OTR verification screen's screenshot cells - the screen [VerifyOTRActivity] composes, in both
 * themes (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the two cells pin.** The whole visible screen, which the deleted `activity_verify_otr.xml`
 * drew: the bar with this screen's own title (the layout had none, so the title is a cell's business
 * now), its up arrow and the two overflow items the deleted menu carried, the manual-verification
 * explanation and the two monospaced fingerprints with their `labelMedium` captions under the
 * layout's 16 dp / 20 dp margins, and the two `weight`ed buttons around their `1dp` rule at the
 * bottom. The cells are the mode `MODE_MANUAL_VERIFICATION` draws; the shared-secret half is the same
 * state machine and is not a second picture.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold` inside
 * [TulkkiChrome] paints it, so composing the two areas alone would render a transparent picture. The
 * cell therefore composes the screen where production composes it, and the title is read from the
 * same resource the Activity's `updateView` names.
 */
@PreviewTest
@Preview(
    name = "verify-otr-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun VerifyOtrDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "verify-otr-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun VerifyOtrLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val state = remember { manualVerificationState() }
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.manually_verify),
            onUp = {},
            menu =
                listOf(
                    ChromeMenuItem(stringResource(R.string.show_qr_code)) {},
                    ChromeMenuItem(stringResource(R.string.action_settings)) {},
                ),
        ) {
            VerifyOtrScreen(
                state = state,
                onHintChange = {},
                onSecretChange = {},
                onAction = {},
            )
        }
    }
}

/** The state `updateViewManualVerification` builds for a fingerprint that is not verified yet. */
private fun manualVerificationState(): VerifyOtrScreenState =
    VerifyOtrScreenState(
        explanation = R.string.manual_verification_explanation,
        showManual = true,
        showSmp = false,
        yourFingerprint = "1f2e3d4c 5b6a7988 0f1e2d3c 4b5a6978 8796a5b4 c3d2e1f0 0a1b2c3d 4e5f6071",
        remoteFingerprint = "2a3b4c5d 6e7f8091 a2b3c4d5 e6f70819 a2b3c4d5 e6f70819 a2b3c4d5 e6f70819",
        leftLabel = DataR.string.cancel,
        leftEnabled = true,
        leftAction = VerifyOtrAction.Finish,
        rightLabel = R.string.verify,
        rightEnabled = true,
        rightAction = VerifyOtrAction.Verify,
    )
