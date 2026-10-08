package uk.xa0.tulkki.ui.topup

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The top-up screen's screenshot cells - `ui-6`'s gate, on the harness `ui-11` landed
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**The page itself is not in any of them, and cannot be.** §7.3 says it plainly: the WebView's
 * rendering, its cookie jar and its dark mode are only observable on a phone, and a real payment cannot
 * be driven - so the host hands the one view in (the screen's `page` slot) and these cells render the
 * chrome around it, which is where every state §3.3 names is decided. A fixture passes no page, so a
 * reference here is a picture of the bar, the failure panel or the notice and not of a page.
 *
 * <p>§7.2 wants "**both themes for every screen**", so the loading state is here twice; the rest are
 * §3.3's own states: the two failures (the WebView's own description, and the status the platform
 * answered instead of the page), the one address nothing on the phone could open, and the off state.
 */
@PreviewTest
@Preview(name = "topup-loading", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun TopUpLoadingScreenshot() = Fixture(darkTheme = true, state = TopUpState.loading(0).progressed(42))

@PreviewTest
@Preview(name = "topup-loading-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun TopUpLoadingLightScreenshot() = Fixture(darkTheme = false, state = TopUpState.loading(0).progressed(42))

/** The page did not arrive: the WebView's own words and the address that failed, with the retry. */
@PreviewTest
@Preview(name = "topup-failed", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun TopUpFailedScreenshot() =
    Fixture(
        darkTheme = true,
        state =
            TopUpState.loading(0)
                .failed(
                    TopUpState.Reason.Unreachable(
                        "https://platform.deepseek.com/top_up",
                        "net::ERR_CONNECTION_RESET",
                    )
                ),
    )

/** The shape to expect: the platform answers a client it does not like with a status, not the page. */
@PreviewTest
@Preview(name = "topup-http", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun TopUpHttpScreenshot() =
    Fixture(
        darkTheme = true,
        state =
            TopUpState.loading(0)
                .failed(TopUpState.Reason.Http(403, "https://platform.deepseek.com/top_up")),
    )

/** The page is up and one address it asked for went nowhere: a line under it, not a panel over it. */
@PreviewTest
@Preview(name = "topup-unhandled", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun TopUpUnhandledScreenshot() =
    Fixture(darkTheme = true, state = TopUpState.loading(0).finished().unhandled("wechatpay://pay?id=1"))

/** §3.3's off state: reached anyway, the ledger's own card and no payment page. */
@PreviewTest
@Preview(name = "topup-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 320)
@Composable
fun TopUpOffScreenshot() = Fixture(darkTheme = true, state = TopUpState.loading(0), enabled = false)

@Composable
private fun Fixture(darkTheme: Boolean, state: TopUpState, enabled: Boolean = true) {
    TulkkiTheme(darkTheme = darkTheme) {
        TopUpScreen(
            state = state,
            enabled = enabled,
            page = {},
            onRetry = {},
            onOpenSettings = {},
        )
    }
}
