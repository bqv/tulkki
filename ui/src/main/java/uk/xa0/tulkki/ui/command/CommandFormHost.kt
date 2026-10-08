package uk.xa0.tulkki.ui.command

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import uk.xa0.tulkki.data.model.CommandForm
import uk.xa0.tulkki.data.model.CommandFormListener
import uk.xa0.tulkki.data.model.CommandFormRenderer
import uk.xa0.tulkki.data.model.CommandFormRenderers
import uk.xa0.tulkki.data.model.CommandFormSession
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * The `:ui` half of the command-form slice: it installs the Compose renderer `:data` asks for
 * and builds the one `View` a pager page is.
 *
 * <p>It exists for the same reason [uk.xa0.tulkki.ui.conversation.ConversationHost] does - a
 * `Composable` cannot be called from Java, and `:data` cannot name Compose at all - but the
 * seam is `:data`'s own [CommandFormRenderer], not a Java door: the module dependency runs
 * one way, so the renderer is installed into [CommandFormRenderers] and the session builds its
 * page through whatever is there.
 *
 * <p>[CommandFormState] is the one observable the page draws from, exactly the
 * `ConversationHost.Session` shape: the session is `:data`'s, the mutable state is this
 * module's, and a change re-reads the session's own model rather than rebuilding the
 * composition. The listener is subscribed in a `DisposableEffect`, so a page destroyed by the
 * pager unsubscribes and cannot leak.
 *
 * <p>[install] is idempotent and is called once by `ConversationFragment`; leaving it unset
 * draws no command page at all, which is a JVM cell's and a headless run's honest state.
 */
object CommandFormHost : CommandFormRenderer {

    /** Install this renderer process-wide. Safe to call more than once. */
    @JvmStatic
    fun install() {
        CommandFormRenderers.renderer = this
    }

    override fun createView(context: Context, session: CommandFormSession, anchor: View?): View {
        val darkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val view = ComposeView(context)
        view.setTulkkiContent(darkTheme = darkTheme) {
            val state = remember(session) { CommandFormState(session) }
            DisposableEffect(session) {
                session.addFormListener(state)
                onDispose { session.removeFormListener(state) }
            }
            CommandFormPage(
                form = state.form,
                onAction = { session.executeAction(it) },
                onWebExecute = { session.executeWebAction(it) },
                onWebPreventDefault = { session.preventDefault(it) },
            )
        }
        return view
    }
}

/** The page's observable state: the session's model, re-read on every change it reports. */
internal class CommandFormState(private val session: CommandFormSession) : CommandFormListener {

    /** The rows and actions in force; a change replaces the whole value. */
    var form: CommandForm by mutableStateOf(session.form())
        private set

    override fun onFormChanged() {
        form = session.form()
    }
}
