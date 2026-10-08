package uk.xa0.tulkki.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import uk.xa0.tulkki.xml.Element

/**
 * One row of a conversation's command page: the words the deleted `CommandAdapter` drew and what the
 * row does.
 *
 * <p>It is `CommandAdapter.Command` typed - `getName()`'s label and `start(activity, conversation)`'s
 * action - with the two implementations that existed ([uk.xa0.tulkki.ui.adapter.CommandAdapter.MucConfig]
 * and its XEP-0050 command) built as values by the fragment. The action takes the conversation rather
 * than capturing one, because the list outlives a thread switch and the deleted click listener was
 * handed `currentConversation` at the moment of the tap.
 *
 * <p>[element] is the row's own XEP-0050 `<item/>`, or `null` for the room-configuration row. It is
 * the one thing a caller still reads off the row: `ConversationFragment.commandFor` looks a deep
 * link's node up among them, which is what the deleted `Command0050.el` was for.
 */
data class UiCommand(
    val label: String,
    val element: Element?,
    val start: (ConversationListActivity, Conversation) -> Unit,
)

/**
 * The command page's state: the rows, the onboarding note and the fetch's own progress.
 *
 * <p>It replaces three views of the deleted `fragment_conversation.xml` page - the `commands_view`
 * `ListView` (and the `CommandAdapter` that filled it), the `commands_note` `TextView` and the
 * `commands_view_progressbar` `ProgressBar` - with the facts they carried: [commands] is the adapter's
 * list, [loading] is the progress bar's `visibility`, and [noteVisible] is the note's.
 */
class CommandsSession {

    /** The rows to draw, in the adapter's own order. */
    internal var commands: List<UiCommand> by mutableStateOf(emptyList())

    /** Whether the onboarding note is drawn. */
    internal var noteVisible: Boolean by mutableStateOf(false)

    /** Whether a command list is being fetched. */
    internal var loading: Boolean by mutableStateOf(false)

    internal fun applyCommands(next: List<UiCommand>) {
        commands = next
    }

    internal fun showNote(visible: Boolean) {
        noteVisible = visible
    }

    internal fun applyLoading(loading: Boolean) {
        this.loading = loading
    }
}

/**
 * A conversation's command page: the ad-hoc commands the peer or the room offers, as tappable rows,
 * with the fetch's progress while it is out and the onboarding note under them.
 *
 * <p>Nothing here decides what a command is: `ConversationFragment.refreshCommands` resolves which
 * commands exist - the room's configuration rows, the XEP-0050 items the service answered with - and
 * this draws them, reports the one that was tapped, and shows the two states the deleted page showed.
 *
 * <p>Redesign rather than reproduction: the deleted page was a `ListView` with a `command_row.xml`
 * per row (a `TextView` at the list's own preferred height) and a 130 dp `ProgressBar` beside it; the
 * rows are Compose rows at the same height, and the fetch's progress is the theme's own indicator.
 *
 * @param session the rows, the note and the progress
 * @param onStart what a row's tap means, owned by the host - the fragment, which holds the
 *     conversation the command runs against
 */
@Composable
fun ConversationCommands(session: CommandsSession, onStart: (UiCommand) -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(session.commands) { command ->
                Text(
                    text = command.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { onStart(command) }
                            .padding(
                                horizontal = TulkkiSpacing.lg,
                                vertical = TulkkiSpacing.md,
                            ),
                )
            }
        }
        if (session.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(COMMANDS_PROGRESS_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        if (session.noteVisible) {
            Text(
                text = stringResource(R.string.hub_commands_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.sm),
            )
        }
    }
}

/** The deleted `commands_view_progressbar`'s own height, in dp. */
private val COMMANDS_PROGRESS_HEIGHT = 130.dp
