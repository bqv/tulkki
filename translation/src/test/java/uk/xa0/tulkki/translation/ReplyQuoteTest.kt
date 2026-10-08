package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The quote block at the head of a reply.
 *
 * <p>The contract, in one place: the quote is the <em>referenced</em> message displayed, so its top
 * half is that message's app-language side and its second half is the conversation's language's side.
 * The second half may be read only when the quoted text is the owner's own <em>and</em> the reply is
 * outgoing; somebody else's text is concealed in either direction, and so is the owner's own text
 * inside a received reply. A referenced row that needed a translation and has none is covered and
 * shows nothing, never the fallback the reply carries. A reference that cannot be resolved moves with
 * the switch, and it is the only case that does: on, it is covered and shows nothing; off, the
 * concealment premise is gone - the original is visible everywhere - so it is the reply's own fallback
 * copy of the quoted text, drawn as the single half a plain client drew.
 *
 * <p>The pair {@code receivedReplyQuotingTheOwnersOwnMessageHidesTheWireText} and
 * {@code sentReplyQuotingTheOwnersOwnMessageIsReadable} is the point of asking about both facts: the
 * same stored pair produces a concealed strip inside a received reply and readable text inside a sent
 * one. The other pair, {@code sentReplyQuotingSomebodyElsesMessageConcealsTheirOriginal} and
 * {@code theComposerPreviewOfAReplyToTheContactConcealsTheOriginal}, is why the quoted row's author is
 * asked as well: the composer is always outgoing, so on direction alone it revealed the peer's
 * original under the Finnish on every single reply.
 */
class ReplyQuoteTest {

    private val ON = Interpreter.of("fi", "de")
    private val OFF = Interpreter.of("fi", "fi")

    /** The decision, with both directions spelled out. */
    private fun quote(
            replyStatus: Int,
            referencedStatus: Int,
            referencedBody: String?,
            referencedTranslation: String?,
            referencedState: Int
    ): ReplyQuote {
        return ReplyQuote.of(
                replyStatus,
                referencedBody,
                referencedTranslation,
                referencedState,
                referencedStatus, null, ON)
    }

    /** A received reply whose quote is somebody else's message. */
    private fun incomingQuotingTheirMessage(
            original: String?, translation: String?, state: Int
    ): ReplyQuote {
        return quote(Message.STATUS_RECEIVED, Message.STATUS_RECEIVED, original, translation, state)
    }

    /** A received reply whose quote is the owner's own earlier message. */
    private fun incomingQuotingTheOwnersMessage(
            wire: String?, draft: String?, state: Int
    ): ReplyQuote {
        return quote(Message.STATUS_RECEIVED, Message.STATUS_SEND, wire, draft, state)
    }

    /** A reply the owner is sending (or writing in the composer). */
    private fun outgoing(
            referencedStatus: Int,
            referencedBody: String?,
            referencedTranslation: String?,
            referencedState: Int
    ): ReplyQuote {
        return quote(
                Message.STATUS_SEND,
                referencedStatus,
                referencedBody,
                referencedTranslation,
                referencedState)
    }

    @Test
    fun aReceivedReplyQuotingATranslatedMessageShowsTheTranslationAndHidesTheOriginal() {
        val quote =
                incomingQuotingTheirMessage(
                        "Guten Morgen! Wie geht es dir heute?",
                        "Hyvaa huomenta! Mita sinulle kuuluu?",
                        Message.TRANSLATION_DONE)
        Assert.assertFalse(quote.isCovered())
        Assert.assertEquals("Hyvaa huomenta! Mita sinulle kuuluu?", quote.top())
        Assert.assertTrue(quote.isDivided())
        Assert.assertTrue(quote.isBottomConcealed())
        Assert.assertFalse(quote.isBottomReadable())
        Assert.assertEquals(
                "a received quote's original must not reach the view at all, not even as hidden text",
                "",
                quote.bottomText())
    }

    @Test
    fun aReceivedReplyQuotingAnUntranslatedMessageIsCoveredAndNeverRaw() {
        val quote =
                incomingQuotingTheirMessage(
                        "Guten Morgen! Wie geht es dir heute?", null, Message.TRANSLATION_NONE)
        Assert.assertTrue(
                "translation was needed and did not happen, so the quote is covered",
                quote.isCovered())
        Assert.assertFalse(quote.isDivided())
        Assert.assertEquals(
                "a covered quote has no text to show: the fallback is the peer's original",
                "",
                quote.top())
        Assert.assertEquals("", quote.bottomText())
    }

    @Test
    fun aReplyWhoseReferenceIsNotInTheConversationIsCoveredWhenTheInterpreterIsOn() {
        // getInReplyTo() null and findMessageWithUuid() empty: the row is gone. The reply's body still
        // holds the quoted fallback, so with the interpreter on there is something to leak and nothing
        // the referenced row can show. The fallback is handed in and deliberately goes nowhere.
        val quote = ReplyQuote.unresolved(ON, "> Guten Morgen!")
        Assert.assertTrue(quote.isCovered())
        Assert.assertEquals("", quote.top())
        Assert.assertEquals("", quote.bottomText())
        Assert.assertFalse(quote.isDivided())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, quote.bottom())
    }

    @Test
    fun offAMissingReferenceIsTheRepliesOwnFallbackAsOneHalf() {
        // The same branch with the interpreter off: the app shows originals everywhere, so the
        // fallback copy is not a leak - it is what a plain client drew, and the quote is that text as
        // a single half, undivided, with no cover at all.
        val quote = ReplyQuote.unresolved(OFF, "> Guten Morgen!")
        Assert.assertFalse(
                "off there is no concealment premise left, so the quote is not covered",
                quote.isCovered())
        Assert.assertEquals("> Guten Morgen!", quote.top())
        Assert.assertFalse(quote.isDivided())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, quote.bottom())
        Assert.assertEquals("", quote.bottomText())
        Assert.assertFalse(quote.isBottomReadable())
        Assert.assertFalse(quote.isBottomConcealed())
    }

    @Test
    fun offAMissingReferenceWithNoPlacedFallbackDrawsNothing() {
        // A declared fallback whose span cannot be placed leaves nothing to draw. Off, that is an
        // empty quote - never the cover string, and never an invented text.
        val quote = ReplyQuote.unresolved(OFF, null)
        Assert.assertFalse(quote.isCovered())
        Assert.assertEquals("", quote.top())
        Assert.assertFalse(quote.isDivided())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, quote.bottom())
    }

    @Test
    fun receivedReplyQuotingTheOwnersOwnMessageHidesTheWireText() {
        // The reported leak, exactly: somebody replies to what the owner sent, the quote is the
        // owner's own row, and its wire text is the source language the owner must not read here.
        val quote =
                incomingQuotingTheOwnersMessage(
                        "Ich habe dir gestern ein Buch gegeben",
                        "Annoin sinulle eilen kirjan",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Annoin sinulle eilen kirjan", quote.top())
        Assert.assertTrue(quote.isDivided())
        Assert.assertTrue(quote.isBottomConcealed())
        Assert.assertEquals(
                "keying readability on the referenced row would show the owner's German here",
                "",
                quote.bottomText())
    }

    @Test
    fun sentReplyQuotingTheOwnersOwnMessageIsReadable() {
        // The same stored pair as the test above; only the reply's direction differs.
        val quote =
                outgoing(
                        Message.STATUS_SEND,
                        "Ich habe dir gestern ein Buch gegeben",
                        "Annoin sinulle eilen kirjan",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Annoin sinulle eilen kirjan", quote.top())
        Assert.assertTrue(quote.isDivided())
        Assert.assertTrue(quote.isBottomReadable())
        Assert.assertFalse(quote.isBottomConcealed())
        Assert.assertEquals("Ich habe dir gestern ein Buch gegeben", quote.bottomText())
    }

    @Test
    fun aSentReplyQuotingSomebodyElsesMessageConcealsTheirOriginal() {
        // Readability needs both facts: the reply is on its way out, but the text on the bottom is
        // somebody else's original, and the "an outgoing bubble's bottom half is readable" rule says
        // the owner may see what they sent - it is not a licence to reveal the contact's original.
        val quote =
                outgoing(
                        Message.STATUS_RECEIVED,
                        "Guten Tag! Ich gehe heute einkaufen.",
                        "Hyvaa paivaa! Kayn tanaan kaupassa.",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Hyvaa paivaa! Kayn tanaan kaupassa.", quote.top())
        Assert.assertTrue(quote.isDivided())
        Assert.assertTrue(quote.isBottomConcealed())
        Assert.assertFalse(quote.isBottomReadable())
        Assert.assertEquals(
                "the contact's original must not reach the view, even in the owner's own bubble",
                "",
                quote.bottomText())
    }

    @Test
    fun theComposerPreviewOfAReplyToTheContactConcealsTheOriginal() {
        // The case that settled the rule. The composer's preview is always outgoing, because the
        // owner is the one writing, so on the reply's direction alone it showed the peer's original
        // under the Finnish every single time they replied. The quoted row is what decides.
        val quote =
                outgoing(
                        Message.STATUS_RECEIVED,
                        "Guten Tag! Ich gehe heute einkaufen.",
                        "Hyvaa paivaa! Kayn tanaan kaupassa.",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Hyvaa paivaa! Kayn tanaan kaupassa.", quote.top())
        Assert.assertTrue(quote.isBottomConcealed())
        Assert.assertEquals("", quote.bottomText())
        // Replying to the owner's own message is the one readable case, and it stays readable.
        val toTheOwnersMessage =
                outgoing(
                        Message.STATUS_SEND,
                        "Ich habe dir gestern ein Buch gegeben",
                        "Annoin sinulle eilen kirjan",
                        Message.TRANSLATION_DONE)
        Assert.assertTrue(toTheOwnersMessage.isBottomReadable())
        Assert.assertEquals("Ich habe dir gestern ein Buch gegeben", toTheOwnersMessage.bottomText())
    }

    @Test
    fun aReferencedMessageAlreadyInTheAppLanguageIsASingleQuote() {
        // The receive path costs nothing for it and stores no translation, and there is no second
        // side to divide. Same answer both ways: nothing here was ever an original.
        val asIncoming =
                incomingQuotingTheirMessage("Tama on jo suomea", null, Message.TRANSLATION_SAME_LANGUAGE)
        Assert.assertEquals("Tama on jo suomea", asIncoming.top())
        Assert.assertFalse(asIncoming.isDivided())
        Assert.assertEquals("", asIncoming.bottomText())
        val asOutgoing =
                outgoing(Message.STATUS_RECEIVED, "Tama on jo suomea", null, Message.TRANSLATION_SAME_LANGUAGE)
        Assert.assertEquals("Tama on jo suomea", asOutgoing.top())
        Assert.assertFalse(asOutgoing.isDivided())
    }

    @Test
    fun aReceivedReplyQuotingHistoryTheAutomaticPassNeverTranslatesIsCovered() {
        // History is inserted with no translation and is never queued, so the state stays NONE and
        // the quote is covered rather than shown raw.
        val quote =
                incomingQuotingTheirMessage(
                        "Wir haben uns letzte Woche in Helsinki getroffen.", null, Message.TRANSLATION_NONE)
        Assert.assertTrue(quote.isCovered())
        Assert.assertEquals("", quote.top())
    }

    @Test
    fun aReceivedReplyQuotingCiphertextIsCovered() {
        // PGP is still a blob when the row is written, the automatic pass refuses it, and the state
        // stays NONE. Covering is the answer even though the "language" in it is armour.
        val quote =
                incomingQuotingTheirMessage(
                        "-----BEGIN PGP MESSAGE-----\nhQEMA1exampleexampleexample",
                        null,
                        Message.TRANSLATION_NONE)
        Assert.assertTrue(quote.isCovered())
        Assert.assertEquals("", quote.top())
    }

    @Test
    fun aFailedReferenceTranslationIsCovered() {
        val quote =
                incomingQuotingTheirMessage(
                        "Guten Morgen! Wie geht es dir heute?", null, Message.TRANSLATION_FAILED)
        Assert.assertTrue(quote.isCovered())
    }

    @Test
    fun aDoneStateThatStoredNothingIsCoveredNotShown() {
        val quote =
                incomingQuotingTheirMessage(
                        "Guten Morgen! Wie geht es dir heute?", "", Message.TRANSLATION_DONE)
        Assert.assertTrue(quote.isCovered())
    }

    @Test
    fun aBodyWithNoLanguageIsShownAsTheOneHalf() {
        val quote =
                incomingQuotingTheirMessage(
                        "https://example.org/ein-sehr-langer-link", null, Message.TRANSLATION_NONE)
        Assert.assertFalse(quote.isCovered())
        Assert.assertEquals("https://example.org/ein-sehr-langer-link", quote.top())
        Assert.assertFalse(quote.isDivided())
    }

    @Test
    fun aReplyQuotingAPingShowsItAsOneReadableHalfAndNeverCoversIt() {
        // A ping is a nudge, not prose: TranslationDecision.hasLanguage answers no for it, so
        // DisplayedBody.needsTranslation does too and the quote is the one readable half it was sent
        // as. Inherited from Ping, not decided here - this test is what says the inheritance holds.
        val asIncoming =
                incomingQuotingTheirMessage("Matti: ", null, Message.TRANSLATION_NONE)
        Assert.assertFalse(
                "a ping is a name, not an original: covering it would hide a nudge behind a cover",
                asIncoming.isCovered())
        Assert.assertEquals("Matti: ", asIncoming.top())
        Assert.assertFalse(asIncoming.isDivided())
        Assert.assertEquals("", asIncoming.bottomText())
        val asOutgoing =
                outgoing(Message.STATUS_RECEIVED, "Matti: ", null, Message.TRANSLATION_NONE)
        Assert.assertFalse(asOutgoing.isCovered())
        Assert.assertEquals("Matti: ", asOutgoing.top())
        Assert.assertFalse(asOutgoing.isDivided())
    }

    @Test
    fun anIncomingReplyQuotingTheOwnersUntranslatedMessageShowsItAsOneHalf() {
        // An outgoing row that needed nothing and has no translation: the owner's own words, in the
        // app language. It is shown as a single half even inside a received reply - it is not an
        // original somebody else wrote, so there is nothing here to hide from its author.
        val quote =
                incomingQuotingTheOwnersMessage("Mina opin suomea", null, Message.TRANSLATION_NONE)
        Assert.assertFalse(quote.isCovered())
        Assert.assertEquals("Mina opin suomea", quote.top())
        Assert.assertFalse(quote.isDivided())
    }

    @Test
    fun aNullOrEmptyReferencedBodyNeverBreaksTheDecision() {
        val quote = incomingQuotingTheirMessage(null, null, Message.TRANSLATION_NONE)
        Assert.assertNotNull(quote.top())
        Assert.assertEquals("", quote.top())
        Assert.assertFalse(quote.isDivided())
    }

    @Test
    fun offShowsTheReferencedRowAsItArrivedAndNeverCoversIt() {
        // With the interpreter off the quoted row needed no translation, so the quote is the row's
        // own body, one half - including the row that is covered when the interpreter is on.
        val translated =
                ReplyQuote.of(
                        Message.STATUS_RECEIVED,
                        "Guten Morgen!",
                        "Hyvaa huomenta!",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_RECEIVED,
                        null,
                        OFF)
        Assert.assertFalse(translated.isCovered())
        Assert.assertEquals("Guten Morgen!", translated.top())
        Assert.assertFalse(translated.isDivided())

        val coveredWhenOn =
                ReplyQuote.of(
                        Message.STATUS_RECEIVED,
                        "Guten Morgen!",
                        null,
                        Message.TRANSLATION_NONE,
                        Message.STATUS_RECEIVED,
                        null,
                        OFF)
        Assert.assertFalse(
                "off there is nothing owed, so the quote is not covered",
                coveredWhenOn.isCovered())
        Assert.assertEquals("Guten Morgen!", coveredWhenOn.top())
        Assert.assertFalse(coveredWhenOn.isDivided())
    }
}
