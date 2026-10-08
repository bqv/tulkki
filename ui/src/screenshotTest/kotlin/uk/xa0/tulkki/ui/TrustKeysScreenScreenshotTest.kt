package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The trust screen's screenshot cells - the screen [TrustKeysActivity] composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the two cells pin.** The whole visible screen, which the deleted
 * `activity_trust_keys.xml` and `keys_card.xml` drew: the bar with this screen's own title and its up
 * arrow, the own-keys card with its `titleLarge` address and two rows (one switch off, one on), one
 * contact card with a row, and one contact card whose keys are empty so the `no_keys_just_confirm`
 * line is drawn - the state that line exists for. The second card's title is an [AnnotatedString]
 * with one character in the theme's error colour, which is what `IrregularUnicodeDetector` carries
 * into the card. The button bar is at the bottom with the cancel `TextButton` and the enabled Done
 * button.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold` inside
 * [TulkkiChrome] paints it, so composing the cards alone would render a transparent picture. The cell
 * therefore composes the screen where production composes it, and the title is read from the same
 * resource the Activity reads.
 */
@PreviewTest
@Preview(
    name = "trust-keys-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun TrustKeysDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "trust-keys-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun TrustKeysLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        val noKeysText = stringResource(R.string.no_keys_just_confirm, "Bob")
        val errorColor = MaterialTheme.colorScheme.error
        val state = remember(noKeysText, errorColor) { trustKeysState(noKeysText, errorColor) }
        TulkkiChrome(
            title = stringResource(R.string.trust_omemo_fingerprints),
            onUp = {},
        ) {
            TrustKeysScreen(
                state = state,
                onOwnKeyChanged = { _, _ -> },
                onForeignKeyChanged = { _, _, _ -> },
                onForeignTitleClick = {},
                onFingerprintClick = {},
                onDisableEncryption = {},
                onCancel = {},
                onSave = {},
            )
        }
    }
}

/** A screen with an undecided own key, a decided one, a contact with keys and one without. */
private fun trustKeysState(noKeysText: String, errorColor: Color): TrustKeysScreenState =
    TrustKeysScreenState(
        ownKeysTitle = "owner@example.org",
        ownKeys =
            listOf(
                FingerprintRowState(
                    fingerprint = OWN_KEY,
                    status = FingerprintStatus.createActive(false),
                    trusted = false,
                ),
                FingerprintRowState(
                    fingerprint = OWN_KEY_TRUSTED,
                    status = FingerprintStatus.createActive(true),
                    trusted = true,
                ),
            ),
        showOwnKeys = true,
        foreignKeys =
            listOf(
                ForeignKeysCardState(
                    jid = Jid.of("alice@example.org"),
                    title = AnnotatedString("alice@example.org"),
                    rows =
                        listOf(
                            FingerprintRowState(
                                fingerprint = FOREIGN_KEY,
                                status = FingerprintStatus.createActive(false),
                                trusted = false,
                            )
                        ),
                    noKeysText = null,
                ),
                ForeignKeysCardState(
                    jid = Jid.of("bob@example.org"),
                    title = spoofedJid(errorColor),
                    rows = emptyList(),
                    noKeysText = noKeysText,
                ),
            ),
        showForeignKeys = true,
        saveLabel = R.string.done,
        saveEnabled = true,
    )

/**
 * A JID with one letter from another script, drawn the way `IrregularUnicodeDetector.style` draws it:
 * the `ForegroundColorSpan` on the ambiguous character is the theme's error colour.
 */
private fun spoofedJid(errorColor: Color): AnnotatedString =
    buildAnnotatedString {
        append("b")
        withStyle(SpanStyle(color = errorColor)) { append("\u043e") }
        append("b@example.org")
    }

private const val OWN_KEY =
    "1f2e3d4c5b6a79880f1e2d3c4b5a69788796a5b4c3d2e1f00a1b2c3d4e5f6071"
private const val OWN_KEY_TRUSTED =
    "9182736455463728190a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60"
private const val FOREIGN_KEY =
    "2a3b4c5d6e7f8091a2b3c4d5e6f70819a2b3c4d5e6f70819a2b3c4d5e6f70819"
