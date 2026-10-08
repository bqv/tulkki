package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * What a share or a copy may carry.
 *
 * <p>The clipboard and the share sheet are where the interface stops being able to cover anything, so
 * the cases are the two that used to leak: a covered body, which must be refused rather than written
 * out of the app, and a body that needed no translation but carries a reply fallback - somebody
 * else's message in their language - which must not ride along inside the text that leaves.
 */
class ShareTextTest {

    private val ON = Interpreter.of("fi", "de")
    private val OFF = Interpreter.of("fi", "fi")

    private fun translated(translatedBody: String): DisplayedBody {
        return DisplayedBody.of("Hallo", translatedBody, Message.TRANSLATION_DONE, true, ON)
    }

    private fun covered(): DisplayedBody {
        return DisplayedBody.of("Hallo, wie geht es dir?", null, Message.TRANSLATION_NONE, true, ON)
    }

    /** The same row with the interpreter off: what a plain client would share. */
    private fun off(body: String): DisplayedBody {
        return DisplayedBody.of(body, "Hei", Message.TRANSLATION_DONE, true, OFF)
    }

    @Test
    fun aTranslationIsWhatLeaves() {
        Assert.assertEquals("Hei", ShareText.of(translated("Hei"), "Hallo"))
        Assert.assertFalse(ShareText.refused("Hei"))
    }

    @Test
    fun aBodyThatNeededNothingLeavesAsItselfWithoutItsQuote() {
        // The caller passes the row's own text with the fallback already removed
        // (Message.getBody(true)); the quoted original must not be in it.
        val own = "Joo, nähdään huomenna"
        val displayed =
                DisplayedBody.of("Joo, nähdään huomenna", null, Message.TRANSLATION_SAME_LANGUAGE, false, ON)
        val text = ShareText.of(displayed, own)
        Assert.assertEquals(own, text)
        Assert.assertFalse("the quoted original must not be in what leaves", text.contains("> "))
    }

    @Test
    fun aCoveredBodyIsRefused() {
        val text = ShareText.of(covered(), "Hallo, wie geht es dir?")
        Assert.assertEquals(ShareText.REFUSED, text)
        Assert.assertTrue(ShareText.refused(text))
    }

    @Test
    fun withTheInterpreterOffTheOriginalIsWhatLeaves() {
        // Off there is no cover to fall back to: the share sheet and the clipboard carry the
        // message as it arrived, never the untranslated placeholder.
        val text = ShareText.of(off("Hallo, wie geht es dir?"), "Hallo, wie geht es dir?")
        Assert.assertEquals("Hallo, wie geht es dir?", text)
        Assert.assertFalse(ShareText.refused(text))
    }

    @Test
    fun withTheInterpreterOffAStoredTranslationDoesNotLeave() {
        // The whole point: the paid-for rendering is not what the owner shares once the interpreter
        // is off, even though the row still holds it.
        Assert.assertEquals("Hallo", ShareText.of(off("Hallo"), "Hallo"))
    }

    @Test
    fun anAbsentDecisionIsRefused() {
        Assert.assertTrue(ShareText.refused(ShareText.of(null, "etwas")))
    }

    @Test
    fun aMissingOriginalIsRefusedRatherThanGuessedAt() {
        val displayed =
                DisplayedBody.of("https://example.com", null, Message.TRANSLATION_NONE, false, ON)
        Assert.assertTrue(ShareText.refused(ShareText.of(displayed, null)))
    }

    @Test
    fun emptinessIsTheRefusal() {
        Assert.assertTrue(ShareText.refused(null))
        Assert.assertTrue(ShareText.refused(""))
        Assert.assertFalse(ShareText.refused(" "))
    }
}
