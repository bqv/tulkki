package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import java.util.ArrayList
import org.junit.Assert
import org.junit.Test

/**
 * "Send as written": the owner's one exception to "nothing is sent untranslated", and the small
 * number of things that keep it from being something else.
 *
 * <p>The rule is three sentences - only a send failure offers it, it sends exactly one message
 * untranslated, and it happens once - and all three are decided without a screen, so they are pinned
 * here rather than asserted about a fragment. What a {@code Context} adds (writing the row and handing
 * it to the service's own send path) is the platform half and is a device check; this is the half that
 * decides what the owner asked for.
 */
class SendAsWrittenTest {

    private val DRAFT = "ok"

    private val sent = ArrayList<Message>()
    private val sender =
            object : OutgoingTranslation.Sender {
                override fun send(message: Message) {
                    sent.add(message)
                }
            }

    @Test
    fun oneMessageGoesAsWrittenExactlyOnce() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        val message = failed(settings, HeldSend.HoldReason.UNREACHABLE)

        Assert.assertTrue(
                "a send failure is what this action is for",
                OutgoingTranslation.sendAsWritten(message, settings, "en", sender))
        Assert.assertEquals("exactly one message", 1, sent.size)
        Assert.assertSame("and it is this one", message, sent.get(0))
        Assert.assertEquals(
                "the wire body is the owner's own text, unswapped",
                DRAFT,
                message.getRawBody())
        Assert.assertEquals(
                "no translation was bought: the row is released as already decided",
                Message.TRANSLATION_SAME_LANGUAGE,
                message.getTranslationState())
        Assert.assertEquals("en", message.getTranslationLang())

        // "records that it was sent that way and why", and survives the process.
        val reopened = TranslationSettings(store, store)
        Assert.assertEquals(
                HeldSend.HoldReason.UNREACHABLE, reopened.sentAsWritten(message.getUuid()))
        Assert.assertNull(
                "it is no longer a send failure", reopened.sendFailure(message.getUuid()))
    }

    @Test
    fun aSecondTapSendsNothingMore() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        val message = failed(settings, HeldSend.HoldReason.FAILED)

        Assert.assertTrue(OutgoingTranslation.sendAsWritten(message, settings, "en", sender))
        Assert.assertFalse(
                "once sent as written, the row no longer offers it",
                OutgoingTranslation.sendAsWritten(message, settings, "en", sender))
        Assert.assertEquals("still one message", 1, sent.size)
    }

    @Test
    fun onlyASendFailureIsOfferedThis() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        refused(Message.STATUS_WAITING, Message.TRANSLATION_FAILED, settings)
        refused(Message.STATUS_SEND_FAILED, Message.TRANSLATION_NONE, settings)
        refused(Message.STATUS_SEND_FAILED, Message.TRANSLATION_DONE, settings)
        refused(Message.STATUS_SEND, Message.TRANSLATION_FAILED, settings)
        Assert.assertEquals("nothing left the device", 0, sent.size)
    }

    @Test
    fun noMessageOrNoSettingsSendsNothing() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        Assert.assertFalse(OutgoingTranslation.sendAsWritten(null, settings, "en", sender))
        Assert.assertFalse(OutgoingTranslation.sendAsWritten(failed(settings, null), null, "en", sender))
        Assert.assertEquals(0, sent.size)
    }

    private fun refused(status: Int, translationState: Int, settings: TranslationSettings) {
        val message = Message(HeldConversation(), DRAFT, Message.ENCRYPTION_NONE, status)
        message.setTranslationState(translationState)
        Assert.assertFalse(
                "only " + Message.STATUS_SEND_FAILED + " with a failed translation may be sent as written",
                OutgoingTranslation.sendAsWritten(message, settings, "en", sender))
    }

    /** A row in the send-failure state, with its reason recorded as the send path records it. */
    private fun failed(settings: TranslationSettings, reason: HeldSend.HoldReason?): Message {
        val message =
                Message(
                        HeldConversation(),
                        DRAFT,
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_SEND_FAILED)
        message.setTranslationState(Message.TRANSLATION_FAILED)
        settings.setSendFailure(message.getUuid(), reason)
        return message
    }

    /**
     * A real conversation with its real message list, so the row is a real outgoing message rather
     * than a stub. It answers {@code getEphemeralTimer} itself, because the real one reads a JSON
     * attribute and the unit-test runtime has the stub implementation of {@code org.json}.
     */
    private class HeldConversation :
            Conversation(
                    java.util.UUID.randomUUID().toString(),
                    "tulkki",
                    null,
                    "account",
                    null,
                    0L,
                    Conversation.STATUS_AVAILABLE,
                    Conversation.MODE_SINGLE,
                    "") {

        override fun getEphemeralTimer(): Int {
            return 0
        }
    }
}
