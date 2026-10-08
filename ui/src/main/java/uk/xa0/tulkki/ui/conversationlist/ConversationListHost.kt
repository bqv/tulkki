package uk.xa0.tulkki.ui.conversationlist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.projection.ConversationProjection
import uk.xa0.tulkki.ui.projection.MessageFacts
import uk.xa0.tulkki.ui.projection.PerProcess
import uk.xa0.tulkki.ui.projection.PreviewWords

/**
 * The Java door to [ConversationListScreen], for the host that is still Java:
 * `ConversationListFragment`.
 *
 * <p>It exists for the reason [uk.xa0.tulkki.ui.topup.TopUpHost] and
 * [uk.xa0.tulkki.ui.settings.SettingsHost] do - a Composable cannot be called from Java - and it holds
 * the same two halves those do: [assemble] is the pure state the screen draws, and [show] sets the
 * content once, with an observable [Session], so a re-read recomposes the rows instead of rebuilding the
 * composition.
 *
 * <p>**The read is not here.** §7.4's rule is that a Composable reads no SQLite, and the corollary is
 * that the *host* owns the read: the fragment takes `:data`'s `ConversationSnapshots` (one list query per
 * emission, with each row's pointer resolved) plus the one `MessageSnapshot` each pointer names, off the
 * main thread, and hands the result to [assemble]. That is why [assemble] takes snapshots and not a
 * `Context`: everything below it is plain Kotlin and reachable from a JVM cell.
 */
object ConversationListHost {

    /**
     * The live screen's state. The observable is what lets a re-read land without a re-composition: the
     * screen reads it, so a `Flow`'s next emission only recomposes what changed.
     */
    class Session {

        /** The state the screen reads. */
        internal var state by
            mutableStateOf(
                ConversationListState(
                    rows = null,
                    filter = ConversationFilter.ALL,
                    connection = UiConnection.CONNECTED,
                    archivedVisible = false,
                )
            )

        /** The host's next reading, already assembled. */
        fun update(next: ConversationListState) {
            state = next
        }

        /**
         * Whether a row may be swiped away. It is a state of the session rather than an argument of
         * [show] because it is a preference the owner can change while this screen is alive: `show`
         * is called once, and the host sets this on each read so the next recomposition honours it.
         */
        internal var swipeEnabled by mutableStateOf(true)

        /** The owner's `swipe_to_archive`, and the onboarding gate the tree applied beside it. */
        fun setSwipeEnabled(enabled: Boolean) {
            swipeEnabled = enabled
        }

        /**
         * Whether a long press opens the row's own menu. It is the **host's** kind rather than a
         * preference: `ConversationListFragment` offers the menu the tree's context menu offered, and
         * `ShareWithActivity` is a picker whose only gesture is the tap that picks a row, so its session
         * is set false once before [show] and every row is then drawn without one.
         */
        internal var menuEnabled by mutableStateOf(true)

        /** Whether this host's rows offer a menu at all. */
        fun setMenuEnabled(enabled: Boolean) {
            menuEnabled = enabled
        }

        /**
         * The row's avatar and its shape. It is a capability rather than state, set once before [show] by
         * a host that has one: the screen asks it per row and draws whatever it answers, so `:ui` names
         * neither the island's avatar service nor the image library `:app` loads with.
         */
        internal var avatar: ConversationAvatar? = null

        /** The host's avatar capability; `null` draws the rows without one. */
        fun setAvatar(port: ConversationAvatar?) {
            avatar = port
        }
    }

    /**
     * Sets the content once, over the live [Session].
     *
     * <p>[listState] is the host's when it has one: `ConversationListFragment` owns the saved scroll as
     * `ScrollState(position, offset)` and is a Java caller, so it cannot compose a list state of its own -
     * it makes one and hands it here. A caller with nothing to restore leaves it out and the screen
     * remembers its own, which is why the parameter is the last one.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        view: ComposeView,
        session: Session,
        darkTheme: Boolean,
        events: ConversationListEvents,
        listState: LazyListState? = null,
    ) {
        view.setTulkkiContent(darkTheme) {
            ConversationListScreen(
                state = session.state,
                events = events,
                listState = listState ?: rememberLazyListState(),
                swipeEnabled = session.swipeEnabled,
                menuEnabled = session.menuEnabled,
                avatar = session.avatar,
            )
        }
    }

    /**
     * The screen's state, from what the host read: the rows in the order the read gave them,
     * each projected with the message its pointer names.
     *
     * <p>A pointer that names nothing - an empty conversation, or a row the read did not return - is the
     * projection's own `Absent` preview rather than a blank line or a second query; [lastMessages] is
     * keyed by the pointer's id for exactly that reason.
     *
     * @param now the clock the mute is read against, passed on so the projection reads no clock of its
     *     own.
     * @param relativeTime whether a row's own clock may word itself relatively: the owner's
     *     `always_full_timestamps`, inverted, and the one display preference a row's time needs.
     * @param facts the seam the preview's reply-fallback strip is read from: the host that already holds
     *     the live rows passes its own `messageFacts()`, and a caller with none keeps
     *     `MessageFacts.NONE`'s composed body.
     */
    @JvmStatic
    fun assemble(
        snapshots: List<ConversationSnapshot>,
        lastMessages: Map<String, MessageSnapshot>,
        appLanguage: String,
        interpreter: Interpreter,
        words: PreviewWords,
        now: Long,
        perProcess: PerProcess,
        facts: MessageFacts = MessageFacts.NONE,
        filter: ConversationFilter,
        connection: UiConnection,
        archivedVisible: Boolean,
        relativeTime: Boolean = true,
    ): ConversationListState {
        val rows =
            snapshots.map { conversation ->
                ConversationProjection.of(
                    conversation = conversation,
                    lastMessage = conversation.lastMessageId?.let { lastMessages[it] },
                    appLanguage = appLanguage,
                    interpreter = interpreter,
                    words = words,
                    now = now,
                    perProcess = perProcess,
                    facts = facts,
                    relativeTime = relativeTime,
                )
            }
        return ConversationListState(
            rows = rows,
            filter = filter,
            connection = connection,
            archivedVisible = archivedVisible,
        )
    }
}
