package uk.xa0.tulkki.app

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.ReplySpan
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid

/**
 * The reply fallback's span, read out of a real message's payload tree.
 *
 * <p>This is the half of the old {@code ReplyFallbackTest} that could not stay in
 * {@code :translation}: it needs {@code uk.xa0.tulkki.xml.Element} and
 * {@code Message.getFallbacks}, and the engine may not name an island type. What is pinned is the
 * read itself - the namespace filter, the {@code body} child, the two attributes - because the
 * engine's whole reply arithmetic is only as good as the strings this hands it, and a helper that
 * reinvented the read (the uuid instead of the reply id, the wrong namespace) is how the boundary
 * would come to disagree with what upstream strips.
 */
class ReplySpanReadTest {

    /** The least a Message needs to exist; the conversation is never asked anything here. */
    private class FakeConversation : Conversational {
        override fun getAccount(): Account? = null

        /**
         * The interface's `getContact()` is non-null, while the Java fake answered `null`; the
         * message constructors never ask a conversation for a contact, so the faithful Kotlin
         * spelling of "null, never read" is to refuse the call rather than invent a contact.
         */
        override fun getContact(): Contact =
            throw UnsupportedOperationException("the fake conversation is never asked for a contact")

        override fun getJid(): Jid? = Jid.of("tulkki@example.org")

        override fun getMode(): Int = Conversational.MODE_SINGLE

        override fun getUuid(): String = "conversation"

        override fun getEphemeralTimer(): Int = 0

        override fun canInferPresence(): Boolean = false
    }

    private fun message(): Message =
        Message(FakeConversation(), "", Message.ENCRYPTION_NONE, Message.STATUS_SEND)

    /** A message carrying the reply fallback upstream writes, with the span given. */
    private fun withReplyFallback(start: String?, end: String?): Message {
        val message = message()
        val fallback =
            Element("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", "urn:xmpp:reply:0")
        val span = fallback.addChild("body", "urn:xmpp:fallback:0")
        if (start != null) {
            span.setAttribute("start", start)
        }
        if (end != null) {
            span.setAttribute("end", end)
        }
        message.addPayload(fallback)
        return message
    }

    @Test
    fun aRepliesDeclaredSpanComesBackVerbatim() {
        val span: ReplySpan = ReplySpans.of(withReplyFallback("0", "8"))
        Assert.assertTrue(span.declared())
        Assert.assertEquals("0", span.start())
        Assert.assertEquals("8", span.end())
    }

    @Test
    fun aMessageWithNoFallbackDeclaresNothing() {
        val span: ReplySpan = ReplySpans.of(message())
        Assert.assertFalse(span.declared())
        Assert.assertNull(span.start())
        Assert.assertNull(span.end())
    }

    @Test
    fun aFallbackForSomethingElseIsNotAReply() {
        // A reactions fallback is declared in the same namespace, for a different purpose. Asking for
        // the reply's fallback is what keeps it out, so the message declares no span.
        val message = message()
        val fallback =
            Element("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", "urn:xmpp:reactions:0")
        fallback.addChild("body", "urn:xmpp:fallback:0")
        message.addPayload(fallback)
        Assert.assertFalse(ReplySpans.of(message).declared())
    }

    @Test
    fun aDeclaredFallbackWithNoBodyChildIsStillDeclared() {
        // The difference the engine has to see: there is a fallback, and its span cannot be placed.
        val message = message()
        message.addPayload(
            Element("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", "urn:xmpp:reply:0"))
        val span: ReplySpan = ReplySpans.of(message)
        Assert.assertTrue(span.declared())
        Assert.assertNull(span.start())
        Assert.assertNull(span.end())
    }

    @Test
    fun aSpanWithMissingAttributesStaysDeclared() {
        for (attributes in arrayOf(arrayOf<String?>(null, null), arrayOf("", "5"), arrayOf("0", "abc"))) {
            val message = withReplyFallback(attributes[0], attributes[1])
            val span: ReplySpan = ReplySpans.of(message)
            Assert.assertTrue(
                "declared, start=" + attributes[0] + " end=" + attributes[1], span.declared())
        }
    }

    @Test
    fun aNullMessageDeclaresNothing() {
        Assert.assertFalse(ReplySpans.of(null).declared())
    }
}
