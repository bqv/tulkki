package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/**
 * {@link HeldSend#alreadyHeld} - "is this message already in the conversation's list?" - which is the
 * question the send path asks before it adds a message to that list.
 *
 * <p>It is asked because a held message is written into the conversation before its translation is
 * bought, and handing it back to the send path must not insert it a second time: upstream's ordinary
 * send adds the message it is given, and two entries for one message are two bubbles. That was a real
 * bug, found on the emulator: one message, one database row, two bubbles.
 *
 * <p>Both answers are dangerous, so both are pinned here. A false "no" for a held message is the
 * duplicate bubble. A false "yes" for a message that is not in the list would skip the insert and
 * leave a message that never appears; a false "yes" for an edit would route it past the update that
 * makes the edit land. And the one thing that must never be misread as "already held" is a message
 * that is not in the list at all, because that is every message's first send.
 */
class HeldSendAlreadyHeldTest {

    /**
     * A real {@link Conversation} with its real message list, so {@code findMessageWithUuid} is the
     * method under test rather than a double of it. Two things are arranged around it: the insert is
     * done directly, because {@code add} runs the client's spam heuristics, which are not what this is
     * about; and {@code getEphemeralTimer} is answered here, because the real one reads a JSON
     * attribute and the unit-test runtime has the stub implementation of {@code org.json}.
     */
    private class HeldConversation : Conversation(
            java.util.UUID.randomUUID().toString(),
            "tulkki",
            null,
            "account",
            // No address: this is about the conversation's message list, and the engine
            // test may not name an island type to build a Jid.
            null,
            0L,
            Conversation.STATUS_AVAILABLE,
            Conversation.MODE_SINGLE,
            "") {

        override fun getEphemeralTimer(): Int {
            return 0
        }

        fun put(message: Message) {
            messages.add(message)
        }
    }

    private fun outgoing(conversation: Conversation): Message {
        return Message(
                conversation,
                "Mina opin suomea ja haluan puhua sinulle",
                Message.ENCRYPTION_NONE,
                Message.STATUS_WAITING)
    }

    @Test
    fun aRowTheConversationHoldsIsAlreadyHeld() {
        val conversation = HeldConversation()
        val message = outgoing(conversation)
        conversation.put(message)
        Assert.assertTrue(
                "the held message is in the conversation, so the send path must not add it again",
                HeldSend.alreadyHeld(conversation, message))
    }

    @Test
    fun aMessageTheConversationDoesNotHoldIsNotAlreadyHeld() {
        val conversation = HeldConversation()
        val message = outgoing(conversation)
        Assert.assertFalse(
                "a message the conversation has never seen is a first send, not a held row",
                HeldSend.alreadyHeld(conversation, message))
    }

    @Test
    fun aDifferentMessageWithTheSameBodyIsNotAlreadyHeld() {
        val conversation = HeldConversation()
        conversation.put(outgoing(conversation))
        val other = outgoing(conversation)
        Assert.assertFalse(
                "the identity that matters is the message's own, not its text",
                HeldSend.alreadyHeld(conversation, other))
    }

    @Test
    fun anEditIsNeverAlreadyHeld() {
        val conversation = HeldConversation()
        val message = outgoing(conversation)
        conversation.put(message)
        message.putEdited(message.getUuid(), null)
        Assert.assertTrue("the edit case is the one being tested", message.edited())
        Assert.assertFalse(
                "an edit's uuid is the edited message's own; answering yes would skip its update",
                HeldSend.alreadyHeld(conversation, message))
    }

    @Test
    fun noConversationAndNoMessageAreNotAlreadyHeld() {
        Assert.assertFalse(HeldSend.alreadyHeld(null, null))
        Assert.assertFalse(HeldSend.alreadyHeld(HeldConversation(), null))
        Assert.assertFalse(HeldSend.alreadyHeld(null, outgoing(HeldConversation())))
    }
}
