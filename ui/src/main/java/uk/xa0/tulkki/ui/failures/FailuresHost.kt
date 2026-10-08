package uk.xa0.tulkki.ui.failures

import androidx.compose.ui.platform.ComposeView
import java.util.function.Function
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The Java door to [FailuresScreen], for the host that is still Java:
 * `TranslationFailuresFragment`.
 *
 * <p>It exists for the reason [uk.xa0.tulkki.ui.ledger.LedgerHost] and
 * [uk.xa0.tulkki.ui.settings.SettingsHost] do: a Composable cannot be called from Java, so the fragment
 * hands over the state, the interpreter's switch, the owner's retry wording, one reading, the action
 * seam and one callback, and this sets the content. The theme is a value passed in - "a screenshot test
 * can render any theme without an Activity" (§7.4) - read by the host off the Activity's own resources,
 * which follow the stored preference.
 *
 * <p>[conversationLabel] is the one reading rather than a value: which conversation a failure is in is
 * the loaded conversation's own name when the service has it, the database's stored address when it does
 * not, and `tulkki_failures_unknown_conversation` after that. Only the service can answer the first, so
 * the host answers and the screen stays a drawing of [FailuresState] - the same split the ledger makes
 * for its three notices. [actions] is the same split for what a row may do: a Composable emits and the
 * host performs, because resolving a row's message and calling the translation routes is work no
 * Composable may do, and every route called that way is one the conversation already has.
 *
 * <p>**Each call replaces the composition**, exactly as the other two hosts' do and for the same reason:
 * the Java host re-renders by re-setting the content after the read lands, and the screen takes one
 * immutable state, so nothing here can drift from the screenshots.
 */
object FailuresHost {

    @JvmStatic
    fun show(
        view: ComposeView,
        state: FailuresState,
        enabled: Boolean,
        retryWording: String,
        darkTheme: Boolean,
        conversationLabel: Function<TranslationFailures.Failure, String>,
        actions: FailuresActions,
        onOpenSettings: Runnable,
    ) {
        view.setContent {
            TulkkiTheme(darkTheme = darkTheme) {
                FailuresScreen(
                    state = state,
                    enabled = enabled,
                    retryWording = retryWording,
                    conversationLabel = { conversationLabel.apply(it) },
                    actions = actions,
                    onOpenSettings = { onOpenSettings.run() },
                )
            }
        }
    }
}
