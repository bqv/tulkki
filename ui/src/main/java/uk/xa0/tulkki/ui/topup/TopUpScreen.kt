package uk.xa0.tulkki.ui.topup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The top-up screen, docs/MIGRATION.md "Design: the Compose UI" §3.3 - the platform's own page with the
 * app's chrome around it, drawn from [TopUpState] and nothing else.
 *
 * <p>The page itself is a slot and not a Composable of this file's: a WebView is a platform view, and
 * §7.3 says its rendering, its cookie jar and its dark mode are only observable on a phone, so the host
 * hands the one view in and this screen decides where it sits. That is also what keeps the screen
 * renderable on the JVM: a fixture passes no page and the states around it - loading, the failure panel,
 * the notice - are pictures of what the owner sees.
 *
 * <p>What is drawn: the bar while the page is loading, the page, the failure panel over it when the page
 * did not arrive, and the one-line notice under everything when an address nothing on the phone can open
 * was the page's own request. The panel is opaque on purpose: what came back from a refusal is not the
 * page, and a status code with the address that failed is more use than the block page behind it.
 *
 * <p>**Must never show**, and the reason this screen is a browser and nothing more: anything pre-filled,
 * any address that is not http(s) put into the page (the WebView's own policy hands those out, and that
 * is the host's, not this file's), any script this app injects, and any cookie it reads or writes. The
 * page is DeepSeek's and the session is the owner's. The one control here is the off card's, and it only
 * leaves the screen.
 *
 * <p>[enabled] is the interpreter's switch and §3.3's off state: "removed with the ledger". A screen
 * reached anyway draws the ledger's own card rather than a payment page - with the interpreter off there
 * is no spend to top up - and composes no page at all.
 */
@Composable
fun TopUpScreen(
    state: TopUpState,
    enabled: Boolean,
    page: @Composable () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen paints the theme's own background, exactly as the ledger, the failures list and the
    // settings page do: without a Surface the content colour falls back to black on whatever the host
    // drew behind it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        if (!enabled) {
            InterpreterOffCard(onOpenSettings)
            return@Surface
        }
        Column(modifier = Modifier.fillMaxSize()) {
            val loading = state.page
            if (loading is TopUpState.Page.Loading) {
                LinearProgressIndicator(
                    progress = { loading.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                page()
                val failed = state.page
                if (failed is TopUpState.Page.Failed) {
                    FailurePanel(failed.reason, onRetry)
                }
            }
            state.notice?.let { UnhandledNotice(it) }
        }
    }
}

/**
 * The page did not arrive. The panel covers the page on purpose, and it is the one place the two
 * failures are told apart: the WebView's own description with the address it was loading, or the status
 * the platform answered instead of the page.
 */
@Composable
private fun FailurePanel(reason: TopUpState.Reason, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(TulkkiSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.tulkki_top_up_error_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                failureSentence(reason),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = TulkkiSpacing.sm),
            )
            Text(
                stringResource(R.string.tulkki_top_up_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = TulkkiSpacing.sm),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = TulkkiSpacing.lg)) {
                Text(stringResource(R.string.tulkki_top_up_retry))
            }
        }
    }
}

/**
 * The one line for an address the page asked for and no app on the phone answers: said, not swallowed,
 * because the flow stopped there and the page is still usable. A line rather than a panel, and never a
 * correction of the page - the address is the page's own and is passed on as it is.
 */
@Composable
private fun UnhandledNotice(address: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.tulkki_top_up_unhandled, address),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = TulkkiSpacing.xl, vertical = TulkkiSpacing.md),
        )
    }
}

/**
 * §3.3's off state. The top-up row is gone from the settings page with the ledger, so this is the
 * defensive half - reached anyway, the app does not open a payment page for an interpreter that is off -
 * and the words are the ledger's own three strings.
 */
@Composable
private fun InterpreterOffCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.lg)) {
        Column(
            modifier = Modifier.padding(TulkkiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
        ) {
            Text(stringResource(R.string.tulkki_interpreter_off_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tulkki_interpreter_off_line), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.tulkki_usage_open_settings)) }
        }
    }
}

/**
 * One failure's own sentence. The two frames are the string table's, chosen by the shape of the failure:
 * the WebView's description of a load that did not happen, or the status the platform answered with.
 */
@Composable
private fun failureSentence(reason: TopUpState.Reason): String =
    when (reason) {
        is TopUpState.Reason.Unreachable ->
            stringResource(R.string.tulkki_top_up_error_failed, reason.address, reason.description)
        is TopUpState.Reason.Http ->
            stringResource(R.string.tulkki_top_up_error_http, reason.status, reason.address)
    }
