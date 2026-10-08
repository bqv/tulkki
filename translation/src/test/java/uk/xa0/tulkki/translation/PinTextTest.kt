package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

import uk.xa0.tulkki.data.model.Message

/**
 * What a pin may store.
 *
 * <p>The pin is the one display surface that outlives its bubble - it sits at the head of the
 * conversation and in its own table - so the interesting cases are the ones where the bubble has
 * nothing readable to give it: a body that needed a translation and did not get one, and a
 * translation that failed. Both must store nothing, and the caller refuses the pin rather than
 * keeping the original somewhere the cover does not reach.
 */
class PinTextTest {

    private val ON = Interpreter.of("fi", "de")
    private val OFF = Interpreter.of("fi", "fi")

    private fun covered(body: String): DisplayedBody {
        return DisplayedBody.of(
                body,
                null,
                Message.TRANSLATION_NONE,
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, body, null, ON),
                ON)
    }

    @Test
    fun aCoveredRowPinsNothing() {
        // The body genuinely needs a translation (the received-prose question, not a flag passed in)
        // and has none, so there is nothing a pin may store but the cover's own wording.
        val displayed = covered("Hallo, wie geht es dir?")
        Assert.assertTrue(displayed.isBlurred())
        Assert.assertEquals(PinText.NOTHING, PinText.of(displayed))
    }

    @Test
    fun aFailedTranslationPinsNothing() {
        Assert.assertEquals(
                PinText.NOTHING,
                PinText.of(
                        DisplayedBody.of("Hallo", null, Message.TRANSLATION_FAILED, true, ON)))
    }

    @Test
    fun aTranslatedRowPinsTheTranslation() {
        Assert.assertEquals(
                "Hei, mitä kuuluu?",
                PinText.of(
                        DisplayedBody.of(
                                "Hallo, wie geht's?",
                                "Hei, mitä kuuluu?",
                                Message.TRANSLATION_DONE,
                                true,
                                ON)))
    }

    @Test
    fun aRowThatNeededNothingPinsItsOwnText() {
        // A link has no language, so nothing was owed and the link is what the bubble shows.
        val link = "https://example.com/kutsu"
        Assert.assertEquals(
                link,
                PinText.of(
                        DisplayedBody.of(
                                link,
                                null,
                                Message.TRANSLATION_NONE,
                                DisplayedBody.needsTranslation(
                                        Message.STATUS_RECEIVED, link, null, ON),
                                ON)))
    }

    @Test
    fun aRowAlreadyInTheAppLanguagePinsItsOwnText() {
        Assert.assertEquals(
                "Moi, nähdään huomenna",
                PinText.of(
                        DisplayedBody.of(
                                "Moi, nähdään huomenna",
                                null,
                                Message.TRANSLATION_SAME_LANGUAGE,
                                false,
                                ON)))
    }

    @Test
    fun offPinsTheOriginalEvenWhenTheRowHoldsATranslation() {
        // The pin is a display surface that outlives its bubble, so off it stores the message as it
        // arrived rather than the paid-for rendering the row still holds.
        Assert.assertEquals(
                "Hallo, wie geht's?",
                PinText.of(
                        DisplayedBody.of(
                                "Hallo, wie geht's?",
                                "Hei, mitä kuuluu?",
                                Message.TRANSLATION_DONE,
                                true,
                                OFF)))
    }

    @Test
    fun offPinsACoveredOriginalRatherThanNothing() {
        // On, this row pins nothing; off it is the plain client's text and is pinnable.
        val displayed =
                DisplayedBody.of("Hallo, wie geht es dir?", null, Message.TRANSLATION_NONE, true, OFF)
        Assert.assertEquals("Hallo, wie geht es dir?", PinText.of(displayed))
        Assert.assertFalse(PinText.nothingToPin(PinText.of(displayed), false))
    }

    @Test
    fun anAbsentDecisionPinsNothing() {
        Assert.assertEquals(PinText.NOTHING, PinText.of(null))
    }

    @Test
    fun aCoveredBodyIsNotPinnable() {
        val pinned = PinText.of(covered("Hallo, wie geht es dir?"))
        Assert.assertTrue(PinText.nothingToPin(pinned, false))
    }

    @Test
    fun textIsPinnable() {
        Assert.assertFalse(PinText.nothingToPin("Hei", false))
    }

    @Test
    fun mediaIsPinnableWithoutText() {
        Assert.assertFalse(PinText.nothingToPin(PinText.NOTHING, true))
    }

    @Test
    fun blankTextIsNotPinnable() {
        Assert.assertTrue(PinText.nothingToPin("   ", false))
        Assert.assertTrue(PinText.nothingToPin(null, false))
    }
}
