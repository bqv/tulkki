package uk.xa0.tulkki.ui.conversation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshots

/**
 * The read the conversation host runs: one conversation's rows, live, from `:data`'s
 * [MessageSnapshots]. It is the `:ui` caller the message list was blocked on -
 * `MessageSnapshots.watch(conversation)` is the missing piece the layout inventory names, and this
 * is the door it was missing.
 *
 * <p>**The read is not in the host.** [ConversationHost.MessagesSession] owns the *decision* to
 * subscribe and the drawing, exactly as [uk.xa0.tulkki.ui.conversationlist.ConversationListHost]
 * owns the list's; this object owns the statements, exactly as
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListRead] does. That split is also what keeps the
 * subscription reachable from a JVM cell: [subscribe] takes a [Flow] and a [CoroutineScope] and
 * nothing Android, so a cell can hand it a fake stream and read back what it does with no device
 * and no database.
 *
 * <p>**Nothing here decides concealment, reads a setting or spends a call.** [stream] names one
 * conversation and `:data`'s own `watch` re-emits its rows; whether an original is ever drawn is
 * [uk.xa0.tulkki.ui.projection.MessageProjection]'s, made from the row an emission carries and
 * before any setting is consulted. The `Flow` opens no request: the only traffic behind it is the
 * file's own, and no translation call is on that path.
 *
 * <p>**The read has two statements, not one, because the host has two update paths.** [stream] is
 * the file's own `Flow`, and [read] is the one-shot read a host's notification path pushes into the
 * same session - the app's "a message was sent or arrived" call. The list recomposes from either;
 * neither is a scroll nudge, and [read] differs from [stream] only in arity (it is a statement, not
 * a subscription).
 */
object ConversationRead {

    /**
     * One conversation's rows, oldest first, re-emitted whenever one of them changes: `:data`'s own
     * `MessageSnapshots.watch`, scoped to [conversation] here so no caller has to remember to.
     *
     * <p>It opens SQLite, so its cover is the gate's build and the run rather than a JVM cell; what
     * a cell can hold is on [subscribe]'s side.
     */
    @JvmStatic
    fun stream(context: Context, conversation: String): Flow<List<MessageSnapshot>> =
        MessageSnapshots.get(context).watch(conversation)

    /**
     * The same rows, read once: the statement a host's own update path pushes into
     * [ConversationHost.MessagesSession.update], so the drawn list follows the app's notification and
     * not only the file's `Flow`.
     *
     * <p>**Why a second source exists at all.** The live conversation's update path is
     * `ConversationFragment.refresh()`, which the service drives through `OnConversationUpdate` on
     * every send and arrival; it re-reads the conversation and, before this statement was reachable,
     * notified only the `GONE` Java adapter. The drawn list is the Compose one, whose rows arrive from
     * [stream] alone, so a row appended after that one subscription was the file's to announce: when
     * the watch did not re-emit, the rows stayed as the first emission left them - the new bubble
     * absent, the list not following, and the day/run arithmetic computed over the old rows. Reading
     * the same statement on the notification path makes the host's own update a writer of the one
     * state the list draws from.
     *
     * <p>It is the identical SQL as [stream]'s (one `byConversation`), so the two writers cannot
     * disagree about what the rows are; a later [stream] emission carries the same rows plus whatever
     * changed since. It opens SQLite for the same reason [stream] does, and it decides no content
     * fact: the projection conceals after this returns.
     */
    @JvmStatic
    fun read(context: Context, conversation: String): List<MessageSnapshot> =
        MessageSnapshots.get(context).read(conversation)

    /**
     * Subscribe [source] until the returned [Job] is cancelled: every emission is handed to
     * [onRows] in order, and the first emission is the list as it stands rather than an empty one.
     *
     * <p>The caller owns the [scope] and the release - cancelling the returned `Job` is the whole
     * of "release the watcher". That is why this hands the `Job` back rather than keeping it: a
     * subscription no caller can stop is a live watcher left behind, which is the leak this exists
     * to prevent.
     */
    @JvmStatic
    fun subscribe(
        source: Flow<List<MessageSnapshot>>,
        scope: CoroutineScope,
        onRows: (List<MessageSnapshot>) -> Unit,
    ): Job = source.onEach(onRows).launchIn(scope)

    /**
     * The scope one host view's subscription runs in, built here because the live host is Java and a
     * Java caller cannot compose a Kotlin scope: [subscribe] takes a scope and owns only the
     * subscription, so the two halves - the scope and the watcher - are built by the one object that
     * already owns both statements. The dispatcher is the main one because an emission moves Compose
     * state, and the job is a supervisor so one failed emission does not take the list's channel down.
     *
     * <p>[releaseScope] is the other half and is [release]'s counterpart: a host cancels the scope it
     * was handed when its view goes, so no subscription outlives the view that asked for it.
     */
    @JvmStatic
    fun viewScope(): CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Release [scope], the whole of "release on destroy" for a scope this object built. */
    @JvmStatic
    fun releaseScope(scope: CoroutineScope) {
        scope.cancel()
    }
}
