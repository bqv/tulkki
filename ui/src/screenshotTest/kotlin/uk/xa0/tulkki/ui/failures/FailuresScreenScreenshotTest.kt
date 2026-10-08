package uk.xa0.tulkki.ui.failures

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.RetryWording
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.TranslationQueue
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The failures screen's screenshot cells - `ui-5`'s gate, on the harness `ui-11` landed
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>§7.2 wants "**both themes for every screen** ... because light is the map nobody looks at", and
 * §3.2's own four states are the other cells: the list, the empty list (which is not the loading one),
 * the loading state (`rows` is `null`, so the screen draws the live lines and no list at all), and the
 * off card. The body on each row is the whole point of this screen - the owner's one approved
 * exception to "originals are concealed" - so a reference here is what a change that drops the
 * message's own text out of the row has to fail.
 *
 * <p>The rows come from [TranslationFailures]' own factories, which are public, rather than from the
 * constructor, which is `internal` to `:translation`: the picture is then of the values the deciding
 * type really produces - a received failure with the queue's reason and the API's own words, and a
 * held send whose reason the record never kept - and the vocabulary drawn is the one
 * `TranslationText` names.
 */
@PreviewTest
@Preview(name = "failures-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun FailuresDarkScreenshot() = Fixture(darkTheme = true, state = fullState())

@PreviewTest
@Preview(name = "failures-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun FailuresLightScreenshot() = Fixture(darkTheme = false, state = fullState())

/** §3.2's empty state: the two live lines, and `tulkki_failures_none`. */
@PreviewTest
@Preview(name = "failures-empty", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 420)
@Composable
fun FailuresEmptyScreenshot() =
    Fixture(darkTheme = true, state = FailuresState(blocker = null, waiting = 0, rows = emptyList()))

/**
 * §3.2's loading state: the read has not landed, so the live lines are drawn and no list is - in
 * particular not the "nothing has failed" line, which would be a claim the screen cannot make yet.
 */
@PreviewTest
@Preview(name = "failures-loading", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 420)
@Composable
fun FailuresLoadingScreenshot() =
    Fixture(darkTheme = true, state = FailuresState(blocker = HeldSend.HoldReason.NO_KEY, waiting = 2, rows = null))

/**
 * Item 17's decision four: the screen opened **for one conversation**, so only that conversation's
 * row is drawn, the filter line says why the list is short, and the held send from the other
 * conversation is not there to be mistaken for this one's.
 */
@PreviewTest
@Preview(name = "failures-filtered", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 620)
@Composable
fun FailuresFilteredScreenshot() =
    Fixture(
        darkTheme = true,
        state = fullState().copy(conversation = "9d2b7e10-0000-4000-8000-0000000000c1"),
    )

/** §3.2's off state: "reached anyway, the off card". */
@PreviewTest
@Preview(name = "failures-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 320)
@Composable
fun FailuresOffScreenshot() = Fixture(darkTheme = true, state = fullState(), enabled = false)

@Composable
private fun Fixture(darkTheme: Boolean, state: FailuresState, enabled: Boolean = true) {
    TulkkiTheme(darkTheme = darkTheme) {
        FailuresScreen(
            state = state,
            enabled = enabled,
            // The shipped wording, so the retry button's own text is in the picture: a row's actions
            // are what this screen does now, and a reference that hid them would not pin them.
            retryWording = RetryWording.SHIPPED,
            conversationLabel = { it.conversationJid.orEmpty() },
            actions = NoActions,
            onOpenSettings = {},
        )
    }
}

/**
 * The host does the acting and a screenshot has none: the buttons' presence and words are the picture,
 * and the routes behind them are `TranslationFailuresFragment`'s, pinned where they live.
 */
private object NoActions : FailuresActions {
    override fun translateNow(failure: TranslationFailures.Failure) {}

    override fun retry(failure: TranslationFailures.Failure) {}

    override fun sendAsWritten(failure: TranslationFailures.Failure) {}
}

/** A state with everything §3.2 draws: a blocker, the queue's count, and one row of each half. */
private fun fullState(): FailuresState =
    FailuresState(
        blocker = HeldSend.HoldReason.CAP_REACHED,
        waiting = 3,
        rows = listOf(receivedFailure(), heldSend()),
    )

/** A received failure: the queue's own state, with the account's answer carried as its detail. */
private fun receivedFailure(): TranslationFailures.Failure =
    TranslationFailures.received(
        messageUuid = "1f0c9a24-0000-4000-8000-000000000001",
        conversationUuid = "9d2b7e10-0000-4000-8000-0000000000c1",
        conversationJid = "mikko@example.org",
        body = "Hei! Oletko tulossa huomenna?",
        state = TranslationQueue.Item.STATE_FAILED,
        attempts = 3,
        createdAt = 1_789_000_000_000L,
        failedAt = 1_789_000_400_000L,
        lastError = "insufficient balance",
        recorded = null,
    )

/** A held send the app's one failure record never reached, so its reason says so. */
private fun heldSend(): TranslationFailures.Failure =
    TranslationFailures.send(
        messageUuid = "2a5e6b31-0000-4000-8000-000000000002",
        conversationUuid = "b7c4d0f2-0000-4000-8000-0000000000c2",
        conversationJid = "sari@example.net",
        body = "Kiitos, nähdään huomenna!",
        timeSent = 1_789_001_000_000L,
        recorded = null,
    )
