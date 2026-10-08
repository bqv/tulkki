package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/**
 * The two sides of a bubble, and which of them may be read.
 *
 * <p>The contract, in one place: the top is the app language's side and the bottom is the
 * conversation's language's side, whatever the direction; a received bottom half is never readable
 * and a sent one always is; and a message that was never translated has no second half at all.
 *
 * <p>The pair of tests named {@code sameRow*} is the point of the class: the same stored strings -
 * one language below the other - produce a concealed strip for a received message and readable text
 * for a sent one. That is the asymmetry, and it is about whose message it is, not about which
 * language ended up on the bottom.
 */
class BubbleHalvesTest {

    private val ON = Interpreter.of("fi", "de")
    private val OFF = Interpreter.of("fi", "fi")

    /** English original, Finnish translation: the shape an incoming message is stored in. */
    private fun received(original: String?, translation: String?, state: Int): BubbleHalves {
        return BubbleHalves.of(original, translation, state, Message.STATUS_RECEIVED, null, ON)
    }

    /** Finnish draft, English wire text: the shape an outgoing message is stored in after the swap. */
    private fun sent(wire: String?, draft: String?, state: Int): BubbleHalves {
        return BubbleHalves.of(wire, draft, state, Message.STATUS_SEND, null, ON)
    }

    @Test
    fun aReceivedTranslationHasTheTranslationOnTopAndTheOriginalBelowConcealed() {
        val halves =
                received(
                        "Guten Morgen! Wie geht es dir heute?",
                        "Hyvaa huomenta! Mita sinulle kuuluu?",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Hyvaa huomenta! Mita sinulle kuuluu?", halves.top())
        Assert.assertTrue(halves.isDivided())
        Assert.assertTrue(halves.isBottomConcealed())
        Assert.assertFalse(halves.isBottomReadable())
        Assert.assertEquals(
                "a received original must not reach the view at all, not even as hidden text",
                "",
                halves.bottomText())
    }

    @Test
    fun aSentTranslationHasTheOwnersWordsOnTopAndTheWireTextBelowReadable() {
        val halves =
                sent(
                        "[de] Mina opin suomea ja haluan puhua sinulle",
                        "Mina opin suomea ja haluan puhua sinulle",
                        Message.TRANSLATION_DONE)
        Assert.assertEquals("Mina opin suomea ja haluan puhua sinulle", halves.top())
        Assert.assertTrue(halves.isBottomReadable())
        Assert.assertFalse(halves.isBottomConcealed())
        Assert.assertEquals(
                "[de] Mina opin suomea ja haluan puhua sinulle", halves.bottomText())
    }

    @Test
    fun sameRowReceivedIsConcealedWhileSentIsReadable() {
        // One stored pair, two directions: which language sits below does not decide readability.
        val below = "Guten Tag! Ich gehe heute einkaufen."
        val above = "Hyvaa paivaa! Kayn tanaan kaupassa."
        val asReceived = received(below, above, Message.TRANSLATION_DONE)
        val asSent = sent(below, above, Message.TRANSLATION_DONE)
        Assert.assertEquals(above, asReceived.top())
        Assert.assertEquals(above, asSent.top())
        Assert.assertTrue(asReceived.isBottomConcealed())
        Assert.assertTrue(asSent.isBottomReadable())
        Assert.assertEquals(below, asSent.bottomText())
    }

    @Test
    fun nothingTranslatedIsASingleBubble() {
        // Already in the app language: the receive path costs nothing and stores no translation.
        val halves =
                received("Tama on jo suomea", null, Message.TRANSLATION_SAME_LANGUAGE)
        Assert.assertEquals("Tama on jo suomea", halves.top())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, halves.bottom())
        Assert.assertFalse(halves.isDivided())
        Assert.assertEquals("", halves.bottomText())
    }

    @Test
    fun aBodyWithNoLanguageIsASingleBubble() {
        val halves = received("https://example.org/x", null, Message.TRANSLATION_NONE)
        Assert.assertEquals("https://example.org/x", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun anOutgoingMessageThatNeededNothingIsASingleBubble() {
        val halves =
                sent("Mina opin suomea", null, Message.TRANSLATION_SAME_LANGUAGE)
        Assert.assertEquals("Mina opin suomea", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun anUntranslatedIncomingMessageKeepsItsCoverAndGetsNoSecondHalf() {
        // Needed and did not happen: the cover is the whole bubble, and there is nothing to divide
        // because there is nothing to show on top.
        val halves = received("Guten Morgen!", null, Message.TRANSLATION_NONE)
        Assert.assertEquals("Guten Morgen!", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun aFailedTranslationKeepsItsCover() {
        val halves = received("Guten Morgen!", null, Message.TRANSLATION_FAILED)
        Assert.assertEquals("Guten Morgen!", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun aTranslationWithNothingOnTheOtherSideIsASingleBubble() {
        val halves = sent("", "Mina opin suomea", Message.TRANSLATION_DONE)
        Assert.assertEquals("Mina opin suomea", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun aDoneStateThatStoredNothingIsCoveredNotDivided() {
        val halves = received("Guten Morgen!", "", Message.TRANSLATION_DONE)
        Assert.assertEquals("Guten Morgen!", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun aNullBodyNeverBreaksTheDecision() {
        val halves = BubbleHalves.of(null, null, Message.TRANSLATION_NONE, Message.STATUS_RECEIVED, null, ON)
        Assert.assertEquals("", halves.top())
        Assert.assertFalse(halves.isDivided())
    }

    // -- the interpreter off: a plain XMPP client --------------------------------------------------

    @Test
    fun offWithATranslationAndAStoredTranslationIsStillOneHalf() {
        // The explicit outer gate, and the case that would otherwise depend on DisplayedBody's own
        // ordering: off, there is no second side to divide whatever the row holds.
        val halves =
                BubbleHalves.of(
                        "Guten Morgen! Wie geht es dir heute?",
                        "Hyvaa huomenta! Mita sinulle kuuluu?",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_RECEIVED,
                        null,
                        OFF)
        Assert.assertEquals("Guten Morgen! Wie geht es dir heute?", halves.top())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, halves.bottom())
        Assert.assertFalse(halves.isDivided())
        Assert.assertFalse(halves.isBottomReadable())
        Assert.assertFalse(halves.isBottomConcealed())
        Assert.assertEquals("", halves.bottomText())
    }

    @Test
    fun offCollapsesASentTranslationTheSameWay() {
        // The owner's own wire text is the one bottom half that is readable when on; off it is not a
        // second half at all, because the top is already the wire text.
        val halves =
                BubbleHalves.of(
                        "[de] Mina opin suomea ja haluan puhua sinulle",
                        "Mina opin suomea ja haluan puhua sinulle",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_SEND,
                        null,
                        OFF)
        Assert.assertEquals("[de] Mina opin suomea ja haluan puhua sinulle", halves.top())
        Assert.assertEquals(BubbleHalves.Bottom.NONE, halves.bottom())
        Assert.assertFalse(halves.isDivided())
    }

    @Test
    fun offIsOneHalfInEveryState() {
        val states = intArrayOf(
            Message.TRANSLATION_NONE,
            Message.TRANSLATION_DONE,
            Message.TRANSLATION_SAME_LANGUAGE,
            Message.TRANSLATION_FAILED,
        )
        for (state in states) {
            for (status in intArrayOf(Message.STATUS_RECEIVED, Message.STATUS_SEND)) {
                val halves =
                        BubbleHalves.of("Guten Morgen!", "Hyvaa huomenta!", state, status, null, OFF)
                Assert.assertEquals("state " + state, "Guten Morgen!", halves.top())
                Assert.assertEquals("state " + state, BubbleHalves.Bottom.NONE, halves.bottom())
                Assert.assertFalse("state " + state, halves.isDivided())
            }
        }
    }

    @Test
    fun offStillTurnsANullBodyIntoTheEmptyString() {
        val halves =
                BubbleHalves.of(null, "Hei", Message.TRANSLATION_DONE, Message.STATUS_RECEIVED, null, OFF)
        Assert.assertEquals("", halves.top())
        Assert.assertFalse(halves.isDivided())
    }
}
