package uk.xa0.tulkki.ui.conversationlist

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.ConversationKind
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.UiConversation
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTranslationMode

/**
 * `ui-8`'s cells over the conversation list's state, docs/MIGRATION.md "Design: the Compose UI" §3.5:
 * the three filters as "cheap predicates over `rows`", the archived gate, and the three readings the
 * screen draws its four states from.
 *
 * <p>What they hold, in particular: `null` rows are the first emission and an empty list is not - the
 * distinction that keeps the explainer card off the screen while the read is in flight; the connection
 * is a fact of its own, so a reconnect is a status line and never "no conversations"; and the unread
 * filter is the badge, not the mute, because a muted conversation nobody has read is still unread.
 */
class ConversationListStateTest {

    @Test
    fun theThreeFiltersArePredicatesOverTheRows() {
        val rows = listOf(row("a", unread = 1), row("b", unread = 0), row("c", unread = 2, kind = ConversationKind.GROUP), row("d", unread = 0, kind = ConversationKind.GROUP))
        Assert.assertEquals(
            "all, in the read's own order",
            listOf("a", "b", "c", "d"),
            ids(state(rows, ConversationFilter.ALL)),
        )
        Assert.assertEquals("unread is the badge", listOf("a", "c"), ids(state(rows, ConversationFilter.UNREAD)))
        Assert.assertEquals("groups is the row's own flag", listOf("c", "d"), ids(state(rows, ConversationFilter.GROUPS)))
    }

    @Test
    fun archivedRowsAreHiddenUntilTheyAreAskedFor() {
        val rows = listOf(row("a", unread = 1), row("z", unread = 3, archived = true))
        Assert.assertEquals(
            "off, an archived row is in no filter",
            listOf("a"),
            ids(state(rows, ConversationFilter.ALL, archivedVisible = false)),
        )
        Assert.assertEquals(
            "off, even the unread filter does not reach it",
            listOf("a"),
            ids(state(rows, ConversationFilter.UNREAD, archivedVisible = false)),
        )
        Assert.assertEquals(
            "on, it is the third of the list",
            listOf("a", "z"),
            ids(state(rows, ConversationFilter.ALL, archivedVisible = true)),
        )
        Assert.assertEquals(
            "and its badge counts like any other",
            listOf("a", "z"),
            ids(state(rows, ConversationFilter.UNREAD, archivedVisible = true)),
        )
    }

    @Test
    fun theFirstEmissionIsLoadingAndAnEmptyListIsNot() {
        val loading = state(rows = null, ConversationFilter.ALL)
        Assert.assertTrue("the read is in flight", loading.loading)
        Assert.assertTrue("and nothing is drawn as an empty state", loading.visible.isEmpty())
        Assert.assertFalse("least of all the explainer card", loading.empty)

        val landed = state(rows = emptyList(), ConversationFilter.ALL)
        Assert.assertFalse("the read landed", landed.loading)
        Assert.assertTrue("and there is nothing to show: the explainer card's own state", landed.empty)

        val oneRow = state(rows = listOf(row("a")), ConversationFilter.ALL)
        Assert.assertFalse(oneRow.loading)
        Assert.assertFalse(oneRow.empty)
    }

    @Test
    fun aDisconnectedListIsNotAnEmptyList() {
        val reconnecting = state(rows = emptyList(), ConversationFilter.ALL, connection = UiConnection.DISCONNECTED)
        Assert.assertEquals(
            "the connection is carried whatever the rows say, so the screen can say it",
            UiConnection.DISCONNECTED,
            reconnecting.connection,
        )
        Assert.assertFalse(
            "and it is still not the first emission: a status line, not a skeleton",
            reconnecting.loading,
        )
    }

    @Test
    fun aMutedUnreadConversationIsStillUnread() {
        val rows = listOf(row("a", unread = 3, muted = true), row("b", unread = 0, muted = true))
        Assert.assertEquals(
            "the badge is the fact, not the mute",
            listOf("a"),
            ids(state(rows, ConversationFilter.UNREAD)),
        )
    }

    private fun state(
        rows: List<UiConversation>?,
        filter: ConversationFilter,
        connection: UiConnection = UiConnection.CONNECTED,
        archivedVisible: Boolean = false,
    ): ConversationListState = ConversationListState(rows, filter, connection, archivedVisible)

    private fun ids(state: ConversationListState): List<String> = state.visible.map { it.id.uuid }

    /** One row, with only the fields a cell reads spelled by the cell. */
    private fun row(
        id: String,
        unread: Int = 0,
        muted: Boolean = false,
        archived: Boolean = false,
        kind: ConversationKind = ConversationKind.ONE_TO_ONE,
        pinned: Boolean = false,
        withSelf: Boolean = false,
        ongoingCall: Boolean = false,
    ): UiConversation =
        UiConversation(
            id = ConversationId(id),
            name = id.uppercase(),
            jid = "$id@example.org",
            lastMessageId = MessageId("m-$id"),
            lastMessageAt = 0L,
            preview = UiPreview.Absent,
            unread = unread,
            muted = muted,
            archived = archived,
            kind = kind,
            pinned = pinned,
            withSelf = withSelf,
            ongoingCall = ongoingCall,
            language = UiLanguagePair(conversationLanguage = "de", appLanguage = "fi", overridden = false),
            translation = UiTranslationMode.ON,
        )
}
