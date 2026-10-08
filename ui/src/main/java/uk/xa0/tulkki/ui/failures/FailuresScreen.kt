package uk.xa0.tulkki.ui.failures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The translation failures this phone is still carrying, docs/MIGRATION.md "Design: the Compose UI"
 * §3.2 - the diagnostic list, drawn from [FailuresState] and nothing else.
 *
 * <p>**It shows the message's own text, and that is the owner's deliberate reversal.** An original is
 * concealed wherever a bubble would draw it, and this is the one owner-approved exception: a failure
 * the owner cannot recognise is one they cannot act on (§3.2, and `AGENTS.md`'s decision). The body
 * arrives on the row - `:data`'s two projections select their table's `body` column and
 * [TranslationFailures.Failure] carries it - so the row draws `failure.body` and takes no second read.
 * The exception is bounded and everything else stands: it is this screen and no other, a received
 * original is still never drawn in a bubble, and the cover is decided elsewhere and unchanged.
 *
 * <p>**It acts, and that reversal is the owner's too.** It used to be read-only by decision - the
 * composer and the covered bubble owned "do it now" - and a failure the owner cannot act on is not
 * diagnosable, only described, so a row now offers what its kind permits: a received row the cover's
 * own "translate this one, now", a held send the conversation bar's own retry and "send as written"
 * (docs/MIGRATION.md item 17). Nothing is automatic: the row's retry is the owner's tap, and the
 * automatic received retry stays where it always was, in `:translation`. The screen still decides
 * nothing about *how*: it emits a [FailureAction] and the host runs the very routes the covered
 * bubble and the bar run, so a second spending path is not created.
 *
 * <p>§7.4's "**Composables stay dumb**" holds: every value is a reading of [state] or a string from the
 * table, and the things a row cannot carry are parameters. [enabled] is the interpreter's switch,
 * §3.2's "reached anyway, the off card"; [conversationLabel] is the loaded conversation's own name,
 * which only the service can answer, and it falls back to the failure's stored address and then to
 * `tulkki_failures_unknown_conversation` - all decided by the host, because a Composable must not
 * name a service; [retryWording] is the owner's editable wording for the bar's retry button, so the
 * row's retry says what the bar's says; and [actions] is the host, because a Composable emits and
 * does not perform. The screen reads no setting, no database and no clock of its own.
 */
@Composable
fun FailuresScreen(
    state: FailuresState,
    enabled: Boolean,
    retryWording: String,
    conversationLabel: (TranslationFailures.Failure) -> String,
    actions: FailuresActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen paints the theme's own background, exactly as the ledger and the settings screen do:
    // without a Surface the content colour falls back to black on whatever the host drew behind it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        if (!enabled) {
            InterpreterOffCard(onOpenSettings)
            return@Surface
        }
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TulkkiSpacing.xl, vertical = TulkkiSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
        ) {
            Text(stringResource(R.string.tulkki_failures_intro), style = MaterialTheme.typography.bodyMedium)
            state.blocker?.let {
                Text(
                    stringResource(R.string.tulkki_failures_blocked, blockedPhrase(it)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.waiting > 0) {
                Text(
                    stringResource(R.string.tulkki_usage_activity_waiting, state.waiting),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Item 17's decision four: a screen opened for one conversation says so, so the owner does
            // not read a short list as "nothing else failed".
            if (state.conversation != null) {
                Text(stringResource(R.string.tulkki_failures_filtered), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = TulkkiSpacing.md))
            val rows = state.rows
            // `null` is the read still in flight: nothing is drawn, and in particular not the
            // "nothing has failed" line, which would be a claim the screen cannot yet make.
            if (rows != null) {
                val visible = state.visible
                if (visible.isEmpty()) {
                    Text(
                        text =
                            stringResource(
                                if (state.conversation == null) {
                                    R.string.tulkki_failures_none
                                } else {
                                    R.string.tulkki_failures_none_filtered
                                }
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (failure in visible) {
                    FailureRow(failure, retryWording, conversationLabel, actions)
                }
            }
        }
    }
}

/**
 * One row: when - and which of the two times it is - and which conversation, the message's own text,
 * why, what happens next, and the actions that kind permits. Nothing here decides what the actions
 * *mean*: the time's label, the reason and the disposition were settled in [TranslationFailures] and
 * named in words by [TranslationText], and which actions the row affords is [FailureAction]'s
 * question, so the row cannot disagree with the covered bubble or the bar about the same message.
 * The row emits, and [FailuresActions] performs.
 */
@Composable
private fun FailureRow(
    failure: TranslationFailures.Failure,
    retryWording: String,
    conversationLabel: (TranslationFailures.Failure) -> String,
    actions: FailuresActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
    ) {
        Text(
            stringResource(
                TranslationText.failuresWhen(failure.`when`),
                whenText(failure.at),
                conversationLabel(failure),
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        // The body is the reversal. `null` is a genuinely absent body rather than a hidden one, so
        // there is nothing to draw - the row still identifies the failure without it.
        val message = failure.body?.trim().orEmpty()
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        }
        Text(reasonSentence(failure.reason, failure.detail), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(TranslationText.failuresNext(failure.next)), style = MaterialTheme.typography.bodySmall)
        val rowActions = FailureAction.forFailure(failure.next)
        if (rowActions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.md)) {
                for (action in rowActions) {
                    TextButton(onClick = { onFailureAction(action, failure, actions) }) {
                        Text(actionLabel(action, retryWording))
                    }
                }
            }
        }
    }
}

/**
 * Which route a row's action takes. It is the one place the screen names a [FailuresActions] method,
 * so a new [FailureAction] that no host method answers is a `when` the compiler refuses rather than
 * a button that draws and does nothing.
 */
private fun onFailureAction(
    action: FailureAction,
    failure: TranslationFailures.Failure,
    actions: FailuresActions,
) {
    when (action) {
        FailureAction.TRANSLATE_NOW -> actions.translateNow(failure)
        FailureAction.RETRY -> actions.retry(failure)
        FailureAction.SEND_AS_WRITTEN -> actions.sendAsWritten(failure)
    }
}

/**
 * A button's own words. Two come from the screen's table; the retry's comes from the owner, through
 * the host, because it is the same editable wording the conversation's bar draws - a second, fixed
 * label here would be a second retry button that says something else.
 */
@Composable
private fun actionLabel(action: FailureAction, retryWording: String): String =
    when (action) {
        FailureAction.TRANSLATE_NOW -> stringResource(R.string.tulkki_failures_translate_now)
        FailureAction.RETRY -> retryWording
        FailureAction.SEND_AS_WRITTEN -> stringResource(R.string.tulkki_hold_send_as_written)
    }

/**
 * The reason, with DeepSeek's own words beside it when there are any - the same shape and the same
 * string the usage screen's last-failure line uses, because the diagnosable part is what the API said
 * and the screen must not paraphrase it.
 *
 * <p>A `null` reason is not the usage screen's `null`: this row is here because *this* message failed,
 * so an unreached reason says that rather than borrowing the last failure's words
 * ([TranslationText.failureOrNotKept]).
 */
@Composable
private fun reasonSentence(reason: HeldSend.HoldReason?, detail: String?): String {
    val phrase = stringResource(TranslationText.failureOrNotKept(reason))
    val words = detail?.trim().orEmpty()
    return if (words.isEmpty()) phrase else stringResource(R.string.tulkki_usage_last_failure_detail, phrase, words)
}

/** The blocker's own phrase, in the vocabulary the covered bubble and the usage screen already use. */
@Composable
private fun blockedPhrase(reason: HeldSend.HoldReason): String =
    stringResource(TranslationText.failureReason(reason))

/**
 * §3.2's off state: "nothing can fail; the row is gone from settings; reached anyway, the off card".
 * The words are the ledger's own two strings, so the two screens say the same thing about the same
 * mode, and the control is the way back to the screen the two language rows live on.
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

/** The row's own instant, in the phone's clock, as the XML screen drew it. */
private fun whenText(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
