package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * Whether a bubble's second half is shown, and whether it may be read.
 *
 * <p>The test that carries the invariant is {@code aContactsOriginalIsConcealedWhateverTheSettingsSay}:
 * it walks every combination of the three settings against a received half and requires the answer to
 * be the concealed strip or nothing - never readable. A settings screen must never be able to make
 * somebody else's original readable, so the case is pinned for all eight combinations rather than for
 * the default.
 *
 * <p>The interpreter's own claim is the pair {@code theOffInterpreterDrawsNoHalfWhateverTheSettingsSay}
 * and {@code theOffInterpreterCannotBeTalkedIntoDrawingAReceivedHalf}: with it off the answer is
 * {@code Kind.NONE} for every combination of the bottom and the three settings, and that includes the
 * one shape the gate names - a received {@code CONCEALED} bottom with all three settings on. This is
 * deliberately not "the original is readable": off removes a half, and the only two answers for a
 * received bottom are concealed and not drawn. Turning every setting on must not talk the off state
 * into drawing anything.
 *
 * <p>The received switch's own claim is the pair {@code theReceivedSwitchOffLeavesTheTranslationAlone}
 * and {@code theReceivedSwitchOnDrawsTheStrip}: it can take the strip away and put it back, and
 * nothing else. {@code theOwnersOwnHalfIgnoresTheReceivedSwitch} is the other half of that - the
 * switch is the received side's, so it must not move the owner's own words - and
 * {@code hidingTheSecondHalfLeavesASingleBubble} pins that it can never put back a strip the older
 * global switch has removed.
 *
 * <p>Every case below that is not named "off" asks with the interpreter on. Every call names the
 * interpreter it asks about, and the on-value is {@link #ON} - two different languages - so the
 * existing cases keep asserting exactly what they asserted before the predicate existed. The shipped
 * state is {@link #OFF}: a fresh install's two settings both read the device's locale.
 */
class SecondHalfTest {

    /**
     * The interpreter on, for every case that is about the three settings rather than about the
     * off-switch: two different languages, so nothing else in the rule can make it off.
     */
    private val ON = Interpreter.of("fi", "de")

    /**
     * The interpreter off: the same language on both sides. That is a fresh install's state, and not
     * a contrived one - neither setting has been touched, so both read the device's locale.
     */
    private val OFF = Interpreter.of("fi", "fi")

    /** Off: the received message is the translation alone, with nothing drawn under it. */
    @Test
    fun theReceivedSwitchOffLeavesTheTranslationAlone() {
        val second =
                SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, false, false, ON)
        Assert.assertEquals(
                "off is the translation alone, with nothing under it",
                SecondHalf.Kind.NONE,
                second.kind())
        Assert.assertFalse(second.isShown())
    }

    /** On: the strip is drawn, exactly as it always was - and it is still never readable. */
    @Test
    fun theReceivedSwitchOnDrawsTheStrip() {
        val second =
                SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, true, false, ON)
        Assert.assertEquals(SecondHalf.Kind.CONCEALED, second.kind())
        Assert.assertTrue(second.isShown())
        Assert.assertTrue(second.isConcealed())
        Assert.assertFalse(second.isReadable())
    }

    /** The structural guarantee: no combination of the settings yields a readable received half. */
    @Test
    fun aContactsOriginalIsConcealedWhateverTheSettingsSay() {
        for (show in booleanArrayOf(true, false)) {
            for (showConcealed in booleanArrayOf(true, false)) {
                for (concealOwn in booleanArrayOf(true, false)) {
                    val what =
                            "show=" + show + " showConcealed=" + showConcealed + " concealOwn=" +
                                    concealOwn
                    val second =
                            SecondHalf.of(
                                    BubbleHalves.Bottom.CONCEALED,
                                    show,
                                    showConcealed,
                                    concealOwn,
                                    ON)
                    Assert.assertNotEquals(what, SecondHalf.Kind.READABLE, second.kind())
                    Assert.assertFalse(what, second.isReadable())
                    Assert.assertEquals(
                            "the settings may remove the strip and nothing else",
                            if (show && showConcealed) SecondHalf.Kind.CONCEALED
                            else SecondHalf.Kind.NONE,
                            second.kind())
                }
            }
        }
    }

    /**
     * The off-switch's whole claim: with the interpreter off nothing is drawn, for every bottom and
     * every combination of the three settings - including the {@code null} bottom and the two that do
     * not divide. It is the first clause of the decision, above the settings, so the settings cannot
     * put a half back; and because "nothing" is not on the readable side of a received bottom, no
     * combination can turn somebody else's original into text either.
     */
    @Test
    fun theOffInterpreterDrawsNoHalfWhateverTheSettingsSay() {
        for (bottom in arrayOf<BubbleHalves.Bottom?>(
                null,
                BubbleHalves.Bottom.NONE,
                BubbleHalves.Bottom.CONCEALED,
                BubbleHalves.Bottom.READABLE)) {
            for (show in booleanArrayOf(true, false)) {
                for (showConcealed in booleanArrayOf(true, false)) {
                    for (concealOwn in booleanArrayOf(true, false)) {
                        val what =
                                "bottom=" + bottom + " show=" + show + " showConcealed=" +
                                        showConcealed + " concealOwn=" + concealOwn
                        val second =
                                SecondHalf.of(bottom, show, showConcealed, concealOwn, OFF)
                        Assert.assertEquals(what, SecondHalf.Kind.NONE, second.kind())
                        Assert.assertFalse(what, second.isShown())
                        Assert.assertFalse(what, second.isReadable())
                        Assert.assertFalse(what, second.isConcealed())
                    }
                }
            }
        }
    }

    /**
     * The one shape the gate names: a received {@code CONCEALED} bottom with all three display
     * settings on, and the interpreter off. Every setting is at the value that draws the most, and
     * the answer is still nothing - so there is no combination of the settings, and no ordering of
     * them, that talks the off state into drawing a half, and least of all into making the received
     * original readable.
     */
    @Test
    fun theOffInterpreterCannotBeTalkedIntoDrawingAReceivedHalf() {
        val second =
                SecondHalf.of(BubbleHalves.Bottom.CONCEALED, true, true, true, OFF)
        Assert.assertEquals(
                "all three settings on cannot make the off state draw a received half",
                SecondHalf.Kind.NONE,
                second.kind())
        Assert.assertFalse(second.isShown())
        Assert.assertFalse(second.isConcealed())
        Assert.assertFalse(second.isReadable())
    }

    /** The shipped settings do not help either: off, {@link #defaults} is nothing for every bottom. */
    @Test
    fun theOffInterpreterLeavesEvenTheShippedDefaultsEmpty() {
        for (bottom in arrayOf(
                BubbleHalves.Bottom.NONE,
                BubbleHalves.Bottom.CONCEALED,
                BubbleHalves.Bottom.READABLE)) {
            val second = SecondHalf.defaults(bottom, OFF)
            Assert.assertEquals(
                    "off draws nothing, whatever the display settings say",
                    SecondHalf.Kind.NONE,
                    second.kind())
            Assert.assertFalse(second.isShown())
            Assert.assertFalse(second.isReadable())
            Assert.assertFalse(second.isConcealed())
        }
    }

    /** The received switch is the received side's: it must not touch the owner's own half. */
    @Test
    fun theOwnersOwnHalfIgnoresTheReceivedSwitch() {
        for (showConcealed in booleanArrayOf(true, false)) {
            Assert.assertEquals(
                    "the owner's own half is readable, whatever the received switch says",
                    SecondHalf.Kind.READABLE,
                    SecondHalf.of(BubbleHalves.Bottom.READABLE, true, showConcealed, false, ON)
                            .kind())
            Assert.assertEquals(
                    SecondHalf.Kind.CONCEALED,
                    SecondHalf.of(BubbleHalves.Bottom.READABLE, true, showConcealed, true, ON)
                            .kind())
        }
    }

    @Test
    fun theOwnersOwnHalfIsReadableByDefault() {
        val second = SecondHalf.defaults(BubbleHalves.Bottom.READABLE, ON)
        Assert.assertTrue(second.isShown())
        Assert.assertTrue(second.isReadable())
        Assert.assertFalse(second.isConcealed())
    }

    @Test
    fun theConcealOwnSettingConcealsTheOwnersOwnHalf() {
        val second =
                SecondHalf.of(BubbleHalves.Bottom.READABLE, true, true, true, ON)
        Assert.assertEquals(SecondHalf.Kind.CONCEALED, second.kind())
        Assert.assertFalse(second.isReadable())
    }

    /**
     * The older global switch stays the outer gate: off, every bubble is a single half, and the
     * received switch cannot put the strip back. That is what its own summary promises - "every
     * message is a single half" - and the received row narrows it rather than overriding it.
     */
    @Test
    fun hidingTheSecondHalfLeavesASingleBubble() {
        for (bottom in arrayOf(
                BubbleHalves.Bottom.CONCEALED, BubbleHalves.Bottom.READABLE)) {
            for (showConcealed in booleanArrayOf(true, false)) {
                for (concealOwn in booleanArrayOf(true, false)) {
                    val second =
                            SecondHalf.of(bottom, false, showConcealed, concealOwn, ON)
                    Assert.assertEquals(
                            "the owner's own half is hidden too when the setting is off",
                            SecondHalf.Kind.NONE,
                            second.kind())
                    Assert.assertFalse(second.isShown())
                }
            }
        }
    }

    @Test
    fun nothingToDivideIsNothingToShow() {
        Assert.assertEquals(
                SecondHalf.Kind.NONE,
                SecondHalf.of(BubbleHalves.Bottom.NONE, true, true, false, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.NONE, SecondHalf.of(null, true, true, false, ON).kind())
    }

    @Test
    fun defaultsConcealOnlyContacts() {
        Assert.assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.defaults(BubbleHalves.Bottom.CONCEALED, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.READABLE,
                SecondHalf.defaults(BubbleHalves.Bottom.READABLE, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.NONE, SecondHalf.defaults(BubbleHalves.Bottom.NONE, ON).kind())
    }

    @Test
    fun theShippedDefaultsShowTheHalfAndLeaveTheOwnersOwnReadable() {
        // The settings are what *add* concealment, so an install that never opens their screen must
        // see exactly what it saw before they existed. Flipping any of these constants would change
        // every owner's conversation silently, which is why they are pinned here and not left to a
        // comment; the settings store reads the same constants as its defaults. The received switch's
        // shipped answer is deliberately the same as the older switch's: unset means "follow it".
        Assert.assertTrue(SecondHalf.SHOW_SECOND_HALF_BY_DEFAULT)
        Assert.assertTrue(SecondHalf.SHOW_CONCEALED_ORIGINAL_BY_DEFAULT)
        Assert.assertFalse(SecondHalf.CONCEAL_OWN_SECOND_HALF_BY_DEFAULT)
        Assert.assertEquals(
                SecondHalf.Kind.READABLE,
                SecondHalf.defaults(BubbleHalves.Bottom.READABLE, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.defaults(BubbleHalves.Bottom.CONCEALED, ON).kind())
    }

    /**
     * A quote follows whose original it is, and the split is the one {@link ReplyQuote} has already
     * made: a quote of somebody else's message is concealed, so the received switch governs it; a
     * quote of the owner's own message on its way out is readable, so the existing sent-side settings
     * govern that one. No second rule is invented here - the same {@link BubbleHalves.Bottom} value
     * that decides readability decides which switch applies.
     */
    @Test
    fun aContactsQuoteAndTheOwnersOwnQuoteFollowTheirSides() {
        val quotingTheContact =
                ReplyQuote.of(
                        Message.STATUS_RECEIVED,
                        "Guten Tag! Ich gehe heute einkaufen.",
                        "Hyvaa paivaa! Kayn tanaan kaupassa.",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_RECEIVED,
                        null,
                        ON)
        Assert.assertTrue(quotingTheContact.isBottomConcealed())
        Assert.assertEquals(
                "the received switch removes the contact's quoted strip",
                SecondHalf.Kind.NONE,
                SecondHalf.of(quotingTheContact.bottom(), true, false, false, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.of(quotingTheContact.bottom(), true, true, false, ON).kind())

        val quotingTheOwner =
                ReplyQuote.of(
                        Message.STATUS_SEND,
                        "Ich habe dir gestern ein Buch gegeben",
                        "Annoin sinulle eilen kirjan",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_SEND,
                        null,
                        ON)
        Assert.assertTrue(quotingTheOwner.isBottomReadable())
        for (showConcealed in booleanArrayOf(true, false)) {
            Assert.assertEquals(
                    "the owner's own quote is the sent side's, whatever the received switch says",
                    SecondHalf.Kind.READABLE,
                    SecondHalf.of(quotingTheOwner.bottom(), true, showConcealed, false, ON).kind())
        }
        Assert.assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.of(quotingTheOwner.bottom(), true, true, true, ON).kind())
    }

    /**
     * The one case that needed a decision rather than a reading: a received reply that quotes the
     * owner's own earlier message. The strip is drawn inside somebody else's bubble, and
     * {@link ReplyQuote} already conceals even the owner's own words there - the readability rule
     * asks for both rows to be outgoing - so this quote goes with the received switch, which is the
     * same side readability put it on rather than a rule of its own.
     */
    @Test
    fun theOwnersOwnQuoteInsideAReceivedReplyFollowsTheReceivedSwitch() {
        val quote =
                ReplyQuote.of(
                        Message.STATUS_RECEIVED,
                        "Ich habe dir gestern ein Buch gegeben",
                        "Annoin sinulle eilen kirjan",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_SEND,
                        null,
                        ON)
        Assert.assertTrue(quote.isBottomConcealed())
        Assert.assertEquals(
                SecondHalf.Kind.NONE,
                SecondHalf.of(quote.bottom(), true, false, false, ON).kind())
        Assert.assertEquals(
                SecondHalf.Kind.CONCEALED,
                SecondHalf.of(quote.bottom(), true, true, false, ON).kind())
    }
}
