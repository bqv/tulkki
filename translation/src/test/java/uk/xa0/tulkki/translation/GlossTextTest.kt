package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/**
 * Which words may be tapped, and which must not be.
 *
 * <p>The rules that matter are the three exclusions: with the interpreter off there is nothing to tap
 * at all, a covered body yields nothing (its tap already means "translate this one"), and a concealed
 * half has no text to find. The rest is what a tap target should be - a run of letters, not
 * punctuation, not a number, not an emoji.
 *
 * <p>The off cases are the ones this class's gate names: no token, no span, no tap target. The token
 * list <em>is</em> the whole of it - {@code MessageAdapter.addGlossSpans} adds one span per token and
 * a span is the only way into {@code GlossPopup} - so an empty list removes the words, the spans and
 * the taps together. That the caller's loop really does nothing with an empty list is not testable
 * here: {@code :ui} hosts no Activity or Fragment and there is no Robolectric in this suite.
 */
class GlossTextTest {

    private val ON = Interpreter.of("fi", "de")

    /** App Finnish with study Finnish: the same language on both sides, so the interpreter is off. */
    private val OFF = Interpreter.of("fi", "fi")

    private fun at(text: String, token: GlossText.Token): String {
        return text.substring(token.start, token.end)
    }

    @Test
    fun aTranslationIsGlossableWordByWord() {
        val translated = "Hyvaa huomenta, ystavani!"
        val displayed =
                DisplayedBody.of("Guten Morgen, mein Freund!", translated, Message.TRANSLATION_DONE, true, ON)
        Assert.assertEquals(3, GlossText.words(displayed, "en", ON).size)
    }

    @Test
    fun anOriginalThatNeededNothingIsGlossableToo() {
        val displayed =
                DisplayedBody.of("Tama on jo suomea", null, Message.TRANSLATION_SAME_LANGUAGE, false, ON)
        Assert.assertEquals(4, GlossText.words(displayed, "en", ON).size)
    }

    @Test
    fun aCoveredBodyIsNeverGlossable() {
        // The tap on a cover means "translate this one, now". Glossing it would give that tap a second
        // meaning, so the rule lives here rather than with the caller.
        val covered =
                DisplayedBody.of(
                        "Guten Morgen, mein Freund!", null, Message.TRANSLATION_NONE, true, ON)
        Assert.assertTrue(covered.isBlurred())
        Assert.assertTrue(GlossText.words(covered, "en", ON).isEmpty())
    }

    @Test
    fun anOffInterpreterHasNoTokensToTap() {
        // App fi x study en - a plain XMPP client. The same text and the same study language as the
        // cells above; the only difference is the mode, so an empty answer cannot be the text's.
        val text = "Hyvaa huomenta, ystavani!"
        Assert.assertEquals(3, GlossText.words(text, "en", ON).size)
        Assert.assertTrue("no token", GlossText.words(text, "en", OFF).isEmpty())
        // And not by way of the study-language rule: an unknown study language skips that rule while
        // on, so off it must still be nothing.
        Assert.assertEquals(3, GlossText.words(text, null, ON).size)
        Assert.assertTrue(GlossText.words(text, null, OFF).isEmpty())
        Assert.assertTrue(GlossText.words(text, "  ", OFF).isEmpty())
    }

    @Test
    fun anOffInterpreterHasNoTokensInADrawnBodyEither() {
        // The bubble overload routes through the same guard, and this is the case an off app actually
        // draws: the original, because DisplayedBody.of answers ORIGINAL off - not a cover and not a
        // concealment, so what removes the words is the guard and nothing else.
        val original =
                DisplayedBody.of(
                        "Guten Morgen, mein Freund!", null, Message.TRANSLATION_NONE, true, OFF)
        Assert.assertFalse(original.isBlurred())
        Assert.assertFalse(
                "the text itself is glossable while the interpreter is on",
                GlossText.words(original, "en", ON).isEmpty())
        Assert.assertTrue(
                "no token, so no span, so no tap target",
                GlossText.words(original, "en", OFF).isEmpty())
    }

    @Test
    fun punctuationNumbersAndEmojiAreNotWords() {
        Assert.assertTrue(GlossText.words("... --- !!!", "en", ON).isEmpty())
        Assert.assertTrue(GlossText.words("42 3,14 100%", "en", ON).isEmpty())
        Assert.assertTrue(GlossText.words("\uD83D\uDE00 \uD83C\uDF89", "en", ON).isEmpty())
        Assert.assertTrue(GlossText.words("", "en", ON).isEmpty())
        Assert.assertTrue(GlossText.words(null as String?, "en", ON).isEmpty())
    }

    @Test
    fun aTokenIsTheRunOfLettersAndItsRangeFindsItAgain() {
        val text = "Hyvaa huomenta, ystavani!"
        val tokens = GlossText.words(text, "en", ON)
        Assert.assertEquals(3, tokens.size)
        for (token in tokens) {
            Assert.assertEquals(token.word, at(text, token))
        }
        Assert.assertEquals("Hyvaa", tokens[0].word)
        Assert.assertEquals(0, tokens[0].start)
        Assert.assertEquals(5, tokens[0].end)
    }

    @Test
    fun aWordAlreadyInTheStudyLanguageIsNotWorthAGloss() {
        // The gloss is in the study language, so a word that is already in it explains nothing.
        val tokens = GlossText.words("the talo", "en", ON)
        Assert.assertEquals("talo", tokens[0].word)
        Assert.assertEquals(1, tokens.size)
    }

    @Test
    fun anUnknownStudyLanguageSkipsTheDetectionRuleRatherThanTheWords() {
        // Nothing is known to compare against, so everything with letters is tappable. Refusing to
        // gloss because a setting is empty would be a dead end with no way out.
        Assert.assertEquals(3, GlossText.words("Hyvaa huomenta ystavani", null, ON).size)
        Assert.assertEquals(3, GlossText.words("Hyvaa huomenta ystavani", "  ", ON).size)
    }

    // ---- the sentence a gloss is bought with ----

    @Test
    fun theSentenceIsTheOneTheWordSitsIn() {
        val text = "Ostin eilen talon. Huomenna muutan sinne."

        Assert.assertEquals("Ostin eilen talon.", GlossText.sentence(text, 12, 17))
        Assert.assertEquals("Huomenna muutan sinne.", GlossText.sentence(text, 18, 26))
    }

    @Test
    fun aSentenceWithNoPunctuationIsTheWholeText() {
        Assert.assertEquals("moi mitä kuuluu", GlossText.sentence("moi mitä kuuluu", 0, 3))
    }

    @Test
    fun lineBreaksAndQuestionMarksAlsoEndOne() {
        val text = "Mitä kuuluu?\nHyvää kiitos"

        Assert.assertEquals("Mitä kuuluu?", GlossText.sentence(text, 0, 4))
        Assert.assertEquals("Hyvää kiitos", GlossText.sentence(text, 13, 18))
    }

    @Test
    fun aCommaIsNotAnEnding() {
        // A clause is context: the sentence is the one the reader sees, not the nearest clause.
        Assert.assertEquals(
                "Kun tulin kotiin, näin hänet.",
                GlossText.sentence("Kun tulin kotiin, näin hänet.", 22, 27))
    }

    @Test
    fun theSentenceIsBoundedAndKeepsTheWordsNextToTheTappedOne() {
        val text = StringBuilder()
        for (i in 0 until 200) {
            text.append("sana").append(i).append(' ')
        }
        val long_ = text.toString()
        val wordStart = long_.indexOf("sana150")
        val sentence = GlossText.sentence(long_, wordStart, wordStart + 7)

        Assert.assertTrue(sentence.length <= 240)
        Assert.assertTrue(sentence, sentence.contains("sana150"))
    }

    @Test
    fun noRangeNoSentence() {
        Assert.assertEquals("", GlossText.sentence(null, 0, 3))
        Assert.assertEquals("", GlossText.sentence("moi", -1, 2))
        Assert.assertEquals("", GlossText.sentence("moi", 2, 2))
        Assert.assertEquals("", GlossText.sentence("moi", 1, 9))
    }
}
