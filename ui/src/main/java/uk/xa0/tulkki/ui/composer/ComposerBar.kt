package uk.xa0.tulkki.ui.composer

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.theme.TulkkiElevation

/**
 * Tulkki: the composer's own bar, in Compose - the sentence and the buttons the send path puts above
 * the composer when something is held, failed, refused or waiting on a key.
 *
 * <p>It replaces the Java `tulkki_bar` view group (`tulkki_bar_text`, `tulkki_bar_action`,
 * `tulkki_bar_action_alt`, `tulkki_bar_close`) and the Java that moved it: the one real
 * `showTulkkiBar` body and `appendTulkkiBar`/`hideTulkkiBar`. The overloads stay in the fragment as
 * thin wrappers, because their callers name the states they draw, and deleting them would spread the
 * same eight arguments over every send-path method.
 *
 * <p>**Two flags stay on this side because they are decisions, not drawing.** `flow` is "the gate
 * owns the bar until its flow ends" and `pinned` is "this bar survives an interpreter-off refresh";
 * `refreshTulkkiLanguageBar` reads both to decide whether the notice may draw in the same slot. They
 * are read back through [ComposerBarController.isBusy] and [ComposerBarController.isPinned].
 *
 * <p>Redesign, not reproduction: the old text view carried `autoLink="web"` and the bar its own
 * `?attr/colorSurface` background and elevation; the surface and elevation stay (a bar floating over
 * the list), the autolink does not - no sentence the send path writes is a URL on its own.
 */
object ComposerBarHost {

    /** Sets the bar's content once and answers the controller the fragment keeps. */
    @JvmStatic
    fun install(view: ComposeView, darkTheme: Boolean): ComposerBarController {
        val controller = ComposerBarController()
        view.setTulkkiContent(darkTheme) {
            ComposerBarContent(controller)
        }
        return controller
    }
}

/**
 * The bar's content, for a host that composes it directly rather than setting it on a `ComposeView` of
 * its own: [ComposerBarHost.install]'s body without the view. See
 * [uk.xa0.tulkki.ui.pinnedmessage.PinnedBarContent] for why the doors exist and why they must draw the
 * same bar - the conversation's page is one composition now, and the held and failed sentences are
 * inside it.
 */
@Composable
fun ComposerBarContent(controller: ComposerBarController) {
    ComposerBar(controller)
}

/**
 * What the bar currently says, as one value so a change is one state write. `null` is "no bar", which
 * is a zero-height `ComposeView` and nothing drawn.
 */
internal class BarState(
    val text: String,
    val actionLabel: String?,
    val action: View.OnClickListener?,
    val altLabel: String?,
    val altAction: View.OnClickListener?,
    val dismissible: Boolean,
)

/**
 * The bar's live inputs.
 *
 * <p>[show] and [hide] move the drawing; [isBusy] and [isPinned] answer the fragment's own refresh
 * logic, which is why the two flags are kept here rather than in the state the composable reads: a
 * flag change must not redraw the bar, and the old Java only ever read them.
 */
class ComposerBarController internal constructor() {

    internal val state: MutableState<BarState?> = mutableStateOf(null)

    private var base: String = ""
    private var busy: Boolean = false
    private var pinnedFlag: Boolean = false

    fun show(
        text: String,
        actionLabel: String?,
        action: View.OnClickListener?,
        altLabel: String?,
        altAction: View.OnClickListener?,
        dismissible: Boolean,
        flow: Boolean,
        pinned: Boolean,
    ) {
        base = text
        busy = flow
        pinnedFlag = pinned
        state.value = BarState(text, actionLabel, action, altLabel, altAction, dismissible)
    }

    /** The gate's own "…" while its request is in flight: the sentence grows, the bar does not move. */
    fun append(extra: String?) {
        val current = state.value ?: return
        state.value = BarState(
            base + extra.orEmpty(),
            current.actionLabel,
            current.action,
            current.altLabel,
            current.altAction,
            current.dismissible,
        )
    }

    fun hide() {
        state.value = null
        busy = false
        pinnedFlag = false
    }

    /**
     * Drops both action buttons but keeps the sentence: the gate hides its "show the suggestion"
     * button the moment the request is in flight, so the owner cannot ask twice for the same answer.
     */
    fun hideActions() {
        val current = state.value ?: return
        state.value = BarState(current.text, null, null, null, null, current.dismissible)
    }

    fun isBusy(): Boolean = busy

    fun isPinned(): Boolean = pinnedFlag
}

@Composable
private fun ComposerBar(controller: ComposerBarController) {
    val state = controller.state.value ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = TulkkiElevation.toolbar,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val actionLabel = state.actionLabel
                val action = state.action
                if (actionLabel != null && action != null) {
                    TextButton(onClick = { action.onClick(null) }) {
                        Text(actionLabel)
                    }
                }
                val altLabel = state.altLabel
                val altAction = state.altAction
                if (altLabel != null && altAction != null) {
                    TextButton(onClick = { altAction.onClick(null) }) {
                        Text(altLabel)
                    }
                }
                if (state.dismissible) {
                    TextButton(onClick = { controller.hide() }) {
                        Text(stringResource(R.string.tulkki_bar_dismiss))
                    }
                }
            }
        }
    }
}
