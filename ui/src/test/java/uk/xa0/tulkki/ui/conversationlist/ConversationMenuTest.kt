package uk.xa0.tulkki.ui.conversationlist

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.ConversationId
import uk.xa0.tulkki.ui.projection.ConversationKind
import uk.xa0.tulkki.ui.projection.MessageId
import uk.xa0.tulkki.ui.projection.UiConversation
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTranslationMode

/**
 * `ui-8`'s cells over the long-press menu, docs/MIGRATION.md "Design: the Compose UI" §3.5's list and the
 * tree's own `menu/conversation_context.xml`.
 *
 * <p>What they hold: **which** entries a row offers and **what each says**, both taken from
 * `ConversationListFragment.onCreateContextMenu` - the order, the pin/mute pairs, the ongoing call that
 * only a one-to-one with a call in progress offers, the contact's details hidden on the note-to-self row,
 * the room's details and its leaving words by kind, and the archive entry whose wording a swipe shares.
 * Nothing is dropped and nothing new is added: a lost entry would be a regression the screen could not
 * show, and an invented one would be an action the host has no handler for.
 */
class ConversationMenuTest {

    @Test
    fun aOneToOneOffersItsOwnEntriesInTheTreesOrder() {
        val row = row(kind = ConversationKind.ONE_TO_ONE)
        Assert.assertEquals(
            listOf(
                ConversationAction.PIN,
                ConversationAction.MUTE,
                ConversationAction.CONTACT_DETAILS,
                ConversationAction.BLOCK_AVATAR,
                ConversationAction.ARCHIVE_CHAT,
            ),
            ConversationMenu.entries(row, ongoingCall = false),
        )
        Assert.assertEquals(
            "a call in progress adds its entry between the mute pair and the details",
            listOf(
                ConversationAction.PIN,
                ConversationAction.MUTE,
                ConversationAction.ONGOING_CALL,
                ConversationAction.CONTACT_DETAILS,
                ConversationAction.BLOCK_AVATAR,
                ConversationAction.ARCHIVE_CHAT,
            ),
            ConversationMenu.entries(row, ongoingCall = true),
        )
        Assert.assertFalse(
            "the note-to-self row hides the contact's details, as the tree hides them",
            ConversationMenu.entries(row(withSelf = true), ongoingCall = false).contains(ConversationAction.CONTACT_DETAILS),
        )
    }

    @Test
    fun aRoomOffersTheRoomsDetailsAndLeavesByItsKind() {
        Assert.assertEquals(
            listOf(
                ConversationAction.PIN,
                ConversationAction.MUTE,
                ConversationAction.MUC_DETAILS,
                ConversationAction.BLOCK_AVATAR,
                ConversationAction.LEAVE_GROUP,
            ),
            ConversationMenu.entries(row(kind = ConversationKind.GROUP), ongoingCall = false),
        )
        Assert.assertEquals(
            "a channel words both its details and its leaving differently",
            listOf(
                ConversationAction.PIN,
                ConversationAction.MUTE,
                ConversationAction.CHANNEL_DETAILS,
                ConversationAction.BLOCK_AVATAR,
                ConversationAction.END_CHANNEL,
            ),
            ConversationMenu.entries(row(kind = ConversationKind.CHANNEL), ongoingCall = false),
        )
        Assert.assertFalse(
            "and a room never offers the contact's details or a call",
            ConversationMenu.entries(row(kind = ConversationKind.GROUP), ongoingCall = true)
                .contains(ConversationAction.ONGOING_CALL),
        )
    }

    @Test
    fun aPinnedAndMutedRowOffersTheOppositeEntries() {
        val entries = ConversationMenu.entries(row(pinned = true, muted = true), ongoingCall = false)
        Assert.assertEquals(ConversationAction.UNPIN, entries.first())
        Assert.assertEquals(ConversationAction.UNMUTE, entries[1])
        Assert.assertFalse(entries.contains(ConversationAction.PIN))
        Assert.assertFalse(entries.contains(ConversationAction.MUTE))
    }

    /** A swipe means the same thing the menu's own leaving entry does, and only its words change. */
    @Test
    fun theSwipeIsTheSameLeavingEntryTheMenuOffers() {
        for (kind in ConversationKind.entries) {
            val leaving = ConversationMenu.archive(kind)
            Assert.assertEquals(
                "a $kind row's last entry is what a swipe means",
                leaving,
                ConversationMenu.entries(row(kind = kind), ongoingCall = false).last(),
            )
        }
        Assert.assertEquals(ConversationAction.ARCHIVE_CHAT, ConversationMenu.archive(ConversationKind.ONE_TO_ONE))
        Assert.assertEquals(ConversationAction.LEAVE_GROUP, ConversationMenu.archive(ConversationKind.GROUP))
        Assert.assertEquals(ConversationAction.END_CHANNEL, ConversationMenu.archive(ConversationKind.CHANNEL))
    }

    @Test
    fun everyEntryHasItsOwnWords() {
        val labels = ConversationAction.entries.associateWith { ConversationMenu.label(it) }
        Assert.assertEquals(
            "no two entries share a line",
            ConversationAction.entries.size,
            labels.values.toSet().size,
        )
        Assert.assertEquals("add_to_favorites", R.string.add_to_favorites, labels.getValue(ConversationAction.PIN))
        Assert.assertEquals("leave_group", R.string.leave_group, labels.getValue(ConversationAction.LEAVE_GROUP))
        Assert.assertEquals(
            "a channel's own words",
            R.string.action_end_conversation_channel,
            labels.getValue(ConversationAction.END_CHANNEL),
        )
    }

    /** One row, with only the fields the menu reads spelled by the cell. */
    private fun row(
        kind: ConversationKind = ConversationKind.ONE_TO_ONE,
        pinned: Boolean = false,
        muted: Boolean = false,
        withSelf: Boolean = false,
        ongoingCall: Boolean = false,
    ): UiConversation =
        UiConversation(
            id = ConversationId("c1"),
            name = "Mikko",
            jid = "mikko@example.org",
            lastMessageId = MessageId("m1"),
            lastMessageAt = 0L,
            preview = UiPreview.Absent,
            unread = 0,
            muted = muted,
            archived = false,
            pinned = pinned,
            kind = kind,
            withSelf = withSelf,
            ongoingCall = ongoingCall,
            language = UiLanguagePair(conversationLanguage = "de", appLanguage = "fi", overridden = false),
            translation = UiTranslationMode.ON,
        )
}
